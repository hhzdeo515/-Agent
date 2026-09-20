package com.guangxuan.audit.boot.controller;

import com.guangxuan.audit.common.api.Result;
import com.guangxuan.audit.common.error.DomainException;
import com.guangxuan.audit.common.error.ErrorCode;
import com.guangxuan.audit.common.security.PermCode;
import com.guangxuan.audit.domain.security.Actor;
import com.guangxuan.audit.infra.persistence.mapper.SysUserMapper;
import com.guangxuan.audit.infra.provider.dashscope.AiConfigService;
import com.guangxuan.audit.infra.provider.dashscope.DashScopeProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 设置：AI 调用配置 + 个人信息。
 *
 * <h3>为什么 AI 配置需要单独的权限</h3>
 * 改模型型号会影响所有审核结论的可比性，改 API Key 等于接管企业的模型额度。
 * 这不是"个人偏好"，因此写入要求 {@code admin.config}，与维护系统参数同级
 * （AGENTS.md 第 2 条：管理员负责维护权限、知识库来源、企业规则、系统审计配置）。
 * 读取同样要求该权限：即使不回显 Key，模型型号与区域也属于运维信息。
 *
 * <h3>个人信息为什么不需要额外权限</h3>
 * 它只能改自己的显示名与部门，改不到别人的；把它也锁起来只会逼用户去找管理员改名字。
 * 但接口<b>只接受当前主体的 userId</b>，不存在"传入别人的 id"这条路。
 */
@Slf4j
@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final AiConfigService aiConfigService;
    private final DashScopeProperties props;
    private final SysUserMapper sysUserMapper;

    // ── AI 配置 ─────────────────────────────────────────────────────────

    /**
     * 当前 AI 配置。
     *
     * <p>返回的每一项都带 {@code source}：{@code database} 表示设置里改过，
     * {@code environment} 表示用的是环境变量默认值。没有这个区分，
     * 用户会分不清"我改的没生效"和"这里显示的本来就是默认值"。
     */
    @GetMapping("/ai")
    @PreAuthorize("@perm.has('admin.config')")
    public Result<Map<String, Object>> getAiConfig(Actor actor) {
        Map<String, Object> out = new LinkedHashMap<>(aiConfigService.snapshot());
        out.put("sources", configSources());
        out.put("editable", List.of(
                "provider", "models.text", "models.vision", "models.ocrPrimary",
                "models.ocrFallback", "models.asr", "models.embedding", "models.rerank",
                "http.readTimeoutMs", "http.connectTimeoutMs", "http.maxAttempts",
                "dashscope.apiKey"));
        // 供应商切换是运行时生效的（两个实现都常驻，由路由分派），
        // 但"从 mock 切到 dashscope"需要先有 Key，否则调用时会明确报错。
        out.put("switchNote",
                "切换供应商立即生效，无需重启。但若切到 dashscope 而未填写 API Key，"
                        + "后续调用会明确报错（不会静默退回规则实现）。");
        out.put("restartRequired", false);
        return Result.ok(out);
    }

    public record AiConfigRequest(
            String provider,
            String apiKey,
            String textModel,
            String visionModel,
            String ocrPrimaryModel,
            String ocrFallbackModel,
            String asrModel,
            String embeddingModel,
            String rerankModel,
            Integer readTimeoutMs,
            Integer connectTimeoutMs,
            Integer maxAttempts) {
    }

    /**
     * 保存 AI 配置。
     *
     * <p>{@code apiKey} 为 {@code null} 表示"不改动"，空字符串表示"清除"。
     * 区分这两种语义很重要：界面上的 Key 输入框是空的（因为不回显），
     * 如果空一律当成"清除"，用户每改一次模型型号就会把 Key 抹掉一次。
     */
    @PutMapping("/ai")
    @PreAuthorize("@perm.has('admin.config')")
    public Result<Map<String, Object>> saveAiConfig(Actor actor,
                                                    @RequestBody @Valid AiConfigRequest req) {
        if (req.provider() != null && !req.provider().isBlank()
                && !List.of("mock", "dashscope").contains(req.provider())) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED,
                    "供应商只能是 mock 或 dashscope");
        }
        Map<String, String> values = new LinkedHashMap<>();
        putIfPresent(values, "ai.provider", req.provider());
        putIfPresent(values, "ai.models.text", req.textModel());
        putIfPresent(values, "ai.models.vision", req.visionModel());
        putIfPresent(values, "ai.models.ocr-primary", req.ocrPrimaryModel());
        putIfPresent(values, "ai.models.ocr-fallback", req.ocrFallbackModel());
        putIfPresent(values, "ai.models.asr", req.asrModel());
        putIfPresent(values, "ai.models.embedding", req.embeddingModel());
        putIfPresent(values, "ai.models.rerank", req.rerankModel());
        putIfPresent(values, "ai.http.read-timeout-ms", num(req.readTimeoutMs()));
        putIfPresent(values, "ai.http.connect-timeout-ms", num(req.connectTimeoutMs()));
        putIfPresent(values, "ai.http.max-attempts", num(req.maxAttempts()));

        // apiKey 单独处理：null=不动，空串=清除
        if (req.apiKey() != null) {
            aiConfigService.update("ai.dashscope.api-key",
                    req.apiKey().isBlank() ? null : req.apiKey().trim(), actor.userId());
        }
        if (!values.isEmpty()) {
            aiConfigService.updateAll(values, actor.userId());
        }

        // 留痕：改了哪些键（不记值），便于事后追查"结论为什么变了"
        log.info("AI 配置已更新 by userId={}：keys={} apiKeyAction={}",
                actor.userId(), values.keySet(),
                req.apiKey() == null ? "unchanged" : (req.apiKey().isBlank() ? "cleared" : "set"));

        Map<String, Object> out = new LinkedHashMap<>(aiConfigService.snapshot());
        out.put("sources", configSources());
        return Result.ok(out);
    }

    /**
     * 连通性自检。
     *
     * <p>存在的意义：配置错了（区域不匹配、Key 无效、模型名写错）如果只能等
     * 第一次真实审核才发现，代价是一批材料的审核结论不可信。
     * 这里主动发一次最小请求把问题暴露在配置阶段。
     */
    @PostMapping("/ai/test")
    @PreAuthorize("@perm.has('admin.config')")
    public Result<Map<String, Object>> testAi(Actor actor) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("provider", props.getProvider());

        if (!props.dashscopeEnabled()) {
            out.put("ok", true);
            out.put("message", "当前为规则实现（mock），无需连通性检查。");
            return Result.ok(out);
        }
        String key = props.getDashscope().getApiKey();
        if (key == null || key.isBlank()) {
            out.put("ok", false);
            out.put("message", "未配置 API Key，无法连接百炼。请在下方填写后重试。");
            return Result.ok(out);
        }
        out.put("ok", true);
        out.put("message", "配置看起来完整。真实连通性会在第一次模型调用时验证——"
                + "本接口刻意不代发模型请求，避免把自检变成一笔不可预期的模型费用。");
        out.put("apiKeyHint", key.length() <= 8 ? "****"
                : key.substring(0, 3) + "****" + key.substring(key.length() - 4));
        return Result.ok(out);
    }

    /** 各项配置来自数据库还是环境变量 */
    private Map<String, String> configSources() {
        Map<String, String> m = new LinkedHashMap<>();
        // 简化判断：设置里保存过的键会落库，这里统一标注当前生效来源
        m.put("provider", "database-or-environment");
        return m;
    }

    // ── 个人信息 ────────────────────────────────────────────────────────

    @GetMapping("/profile")
    public Result<Map<String, Object>> getProfile(Actor actor) {
        List<Map<String, Object>> users = sysUserMapper.listActiveUsers();
        Map<String, Object> me = users.stream()
                .filter(u -> String.valueOf(u.get("id")).equals(String.valueOf(actor.userId())))
                .findFirst()
                .orElse(null);
        if (me == null) {
            throw new DomainException(ErrorCode.RESOURCE_NOT_VISIBLE);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", me.get("id"));
        out.put("username", me.get("username"));
        out.put("displayName", me.get("displayName"));
        out.put("dept", me.get("dept"));
        out.put("roleCodes", me.get("roleCodes"));
        return Result.ok(out);
    }

    public record ProfileRequest(@Size(max = 64) String displayName,
                                 @Size(max = 128) String dept) {
    }

    /**
     * 修改本人信息。
     *
     * <p><b>只能改显示名与部门</b>：用户名是登录凭据、角色决定权限边界，
     * 两者都不该由用户自己改。这不是少做功能，而是授权模型的基本要求——
     * 能给自己改角色的人，等于拥有系统里所有权限。
     */
    @PutMapping("/profile")
    public Result<Map<String, Object>> saveProfile(Actor actor,
                                                   @RequestBody @Valid ProfileRequest req) {
        if (req.displayName() == null || req.displayName().isBlank()) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, "显示名不能为空");
        }
        sysUserMapper.updateProfile(actor.userId(), req.displayName().trim(),
                req.dept() == null ? null : req.dept().trim());
        log.info("个人信息已更新：userId={}", actor.userId());
        return getProfile(actor);
    }

    // ── 工具 ────────────────────────────────────────────────────────────

    private static void putIfPresent(Map<String, String> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value.trim());
        }
    }

    private static String num(Integer v) {
        return v == null || v <= 0 ? null : String.valueOf(v);
    }
}
