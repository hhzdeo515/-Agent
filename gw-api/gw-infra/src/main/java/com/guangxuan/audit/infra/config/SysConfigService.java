package com.guangxuan.audit.infra.config;

import com.guangxuan.audit.common.error.DomainException;
import com.guangxuan.audit.common.error.ErrorCode;
import com.guangxuan.audit.infra.persistence.entity.SysConfigEntity;
import com.guangxuan.audit.infra.persistence.mapper.SysConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统配置读写（含敏感值加密）。
 *
 * <h3>为什么敏感值必须加密</h3>
 * API Key 一旦以明文进库，任何能读库的角色（运维、备份文件、慢查询日志）
 * 都能拿到它并冒用企业的模型额度。加密后即使整库泄露，攻击者还需要
 * 环境变量里的密钥才能还原（AGENTS.md 第 12 条：访问控制与脱敏）。
 *
 * <h3>密钥从哪来</h3>
 * 优先 {@code GW_CONFIG_SECRET}；未配置时从 {@code GW_JWT_SECRET} 派生并打 WARN。
 * 派生而不是直接复用，是为了两者即使同源也不会互相削弱强度。
 * <b>不自动生成随机密钥</b>：那会让重启后无法解密已有配置，
 * 表现为"配置莫名消失"，比启动失败难查得多。
 * 因此密钥缺失时宁可只支持非敏感配置，也不静默生成。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SysConfigService {

    private final SysConfigMapper configMapper;

    @Value("${gw.security.config-secret:}")
    private String configSecret;

    @Value("${gw.security.jwt.secret:}")
    private String jwtSecret;

    private static final String AES = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;

    // ── 读取 ────────────────────────────────────────────────────────────

    /** 全部配置（敏感值已解密），供启动时叠加到配置对象上 */
    @Transactional(readOnly = true)
    public Map<String, String> loadAll() {
        Map<String, String> out = new HashMap<>();
        List<SysConfigEntity> rows = configMapper.selectList(null);
        for (SysConfigEntity e : rows) {
            if (e.getConfigValue() == null || e.getConfigValue().isBlank()) {
                continue;
            }
            String value = isSecret(e) ? decrypt(e.getConfigValue(), e.getConfigKey())
                    : e.getConfigValue();
            if (value != null) {
                out.put(e.getConfigKey(), value);
            }
        }
        return out;
    }

    /** 单个配置；不存在或为空返回 null（调用方据此保留环境变量默认值） */
    @Transactional(readOnly = true)
    public String get(String key) {
        SysConfigEntity e = findByKey(key);
        if (e == null || e.getConfigValue() == null || e.getConfigValue().isBlank()) {
            return null;
        }
        return isSecret(e) ? decrypt(e.getConfigValue(), key) : e.getConfigValue();
    }

    /** 是否为敏感键；接口据此决定是否回显 */
    @Transactional(readOnly = true)
    public boolean isSecretKey(String key) {
        SysConfigEntity e = findByKey(key);
        return e != null && isSecret(e);
    }

    // ── 写入 ────────────────────────────────────────────────────────────

    /**
     * 写入配置。
     *
     * @param value 明文值；为空表示清除该项（回落到环境变量默认值）。
     *              清除而不是存空串，是为了让"没配"与"配成空"不再有歧义
     */
    @Transactional
    public void put(String key, String value, Long operatorId) {
        SysConfigEntity e = findByKey(key);
        boolean blank = value == null || value.isBlank();

        if (e == null) {
            e = new SysConfigEntity();
            e.setConfigKey(key);
            e.setIsSecret(guessSecret(key) ? 1 : 0);
            e.setConfigValue(blank ? null : (isSecret(e) ? encrypt(value, key) : value));
            e.setUpdatedBy(operatorId);
            configMapper.insert(e);
        } else {
            String stored = blank ? null : (isSecret(e) ? encrypt(value, key) : value);
            // 必须用 UpdateWrapper 显式 set，不能用 updateById：
            // MyBatis-Plus 默认的字段策略会**忽略 null 字段**，于是"清除配置"
            // 会变成一次静默的空操作——数据库里旧值还在，界面上却显示已清除。
            // 这个坑在真实使用中表现为"点了清除但重启后又回来了"。
            configMapper.update(null,
                    new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<SysConfigEntity>()
                            .eq("config_key", key)
                            .set("config_value", stored)
                            .set("updated_by", operatorId));
        }
        // 只记键名与是否敏感，绝不记值——日志是最容易被忽略的泄露渠道
        log.info("系统配置已更新：key={} secret={} action={}",
                key, e.getIsSecret() != null && e.getIsSecret() == 1,
                blank ? "clear" : "set");
    }

    // ── 加密 ────────────────────────────────────────────────────────────

    private boolean isSecret(SysConfigEntity e) {
        return e.getIsSecret() != null && e.getIsSecret() == 1;
    }

    private String encrypt(String plain, String key) {
        try {
            byte[] iv = new byte[IV_BYTES];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(key.getBytes(StandardCharsets.UTF_8));
            byte[] cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] packed = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(cipherText, 0, packed, iv.length, cipherText.length);
            return Base64.getEncoder().encodeToString(packed);
        } catch (Exception e) {
            throw new DomainException(ErrorCode.CONFIGURATION_ERROR,
                    "配置加密失败：" + e.getMessage());
        }
    }

    private String decrypt(String packed, String key) {
        try {
            byte[] bytes = Base64.getDecoder().decode(packed);
            if (bytes.length <= IV_BYTES) {
                throw new IllegalArgumentException("密文长度不合法");
            }
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(bytes, 0, iv, 0, IV_BYTES);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            // 把键名作为附加认证数据：防止把 A 配置的密文粘贴到 B 配置上
            cipher.updateAAD(key.getBytes(StandardCharsets.UTF_8));
            byte[] plain = cipher.doFinal(bytes, IV_BYTES, bytes.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 解密失败几乎总是"换过密钥"。不要清空该值（那是删数据），
            // 而是让调用方看到它取不到、从而回落到环境变量
            log.error("配置解密失败（key={}）：{}。通常是 GW_CONFIG_SECRET / GW_JWT_SECRET 变更所致，"
                    + "请在设置里重新填写该配置。", key, e.getMessage());
            return null;
        }
    }

    private SecretKey secretKey() {
        String seed = configSecret != null && !configSecret.isBlank() ? configSecret : jwtSecret;
        if (seed == null || seed.isBlank()) {
            throw new DomainException(ErrorCode.CONFIGURATION_ERROR,
                    "未配置 GW_CONFIG_SECRET 或 GW_JWT_SECRET，无法加密保存敏感配置");
        }
        if (configSecret == null || configSecret.isBlank()) {
            log.warn("未配置 GW_CONFIG_SECRET，敏感配置使用 GW_JWT_SECRET 派生的密钥加密。"
                    + "生产环境建议单独配置 GW_CONFIG_SECRET，避免轮换 JWT 密钥时导致配置无法解密。");
        }
        try {
            // 用 SHA-256 派生出固定长度的 AES-256 密钥；加固定盐避免与 JWT 用同一把密钥
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] key = md.digest(("gw-config|" + seed).getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(key, AES);
        } catch (Exception e) {
            throw new DomainException(ErrorCode.CONFIGURATION_ERROR, "派生配置密钥失败");
        }
    }

    private SysConfigEntity findByKey(String key) {
        return configMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<SysConfigEntity>()
                        .eq("config_key", key));
    }

    /** 键名里带 api-key / secret / password / token 的一律按敏感处理，避免漏标 */
    static boolean guessSecret(String key) {
        String k = key == null ? "" : key.toLowerCase();
        return k.contains("api-key") || k.contains("secret")
                || k.contains("password") || k.contains("token");
    }
}
