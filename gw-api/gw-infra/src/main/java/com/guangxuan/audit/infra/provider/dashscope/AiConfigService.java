package com.guangxuan.audit.infra.provider.dashscope;

import com.guangxuan.audit.infra.config.SysConfigService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 运行时配置：把"设置里改的值"叠加到 {@link DashScopeProperties} 上。
 *
 * <h3>优先级</h3>
 * 数据库配置 &gt; 环境变量 / application.yml。
 * 这样界面上改一次即刻生效且能持久化，而环境变量仍是"出厂默认值"，
 * 换环境部署时不至于把配置写死在镜像里。
 *
 * <h3>为什么是"改配置对象"而不是"每次调用去查库"</h3>
 * 模型客户端在每个请求里都会读配置（超时、模型名）。每次都查库既慢又多余，
 * 而配置变更本来就该是低频动作。因此启动时载入一次，
 * 写入时同步更新内存中的对象——代价是"改完立即生效"依赖同一个进程，
 * 多实例部署时其他实例需重启或刷新；这一点在设置界面里如实说明。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiConfigService {

    private final SysConfigService sysConfigService;
    private final DashScopeProperties props;

    /**
     * 环境变量给的初始值（"出厂默认"）。
     *
     * <p>保留它的原因是修一个真实的 bug：界面里<b>清除</b>某项配置时（例如清空 API Key），
     * 只做"数据库有值就覆盖"是不够的——数据库里没有值了，内存里却还是上一次的值，
     * 表现为"点了清除但没生效"。因此每次叠加前先回到基线，再叠加数据库里的覆盖项。
     */
    private DashScopeProperties baseline;

    /** 数据库里有值就覆盖；没值保留环境变量给的默认值 */
    @PostConstruct
    public synchronized void applyStoredConfig() {
        if (baseline == null) {
            baseline = copyOf(props);
        } else {
            restore(baseline);
        }

        int applied = 0;
        try {
            Map<String, String> stored = sysConfigService.loadAll();
            applied += apply(stored, "ai.provider", v -> props.setProvider(v));
            applied += apply(stored, "ai.region", v -> props.setRegion(v));
            applied += apply(stored, "ai.dashscope.api-key",
                    v -> props.getDashscope().setApiKey(v));
            applied += apply(stored, "ai.dashscope.base-url",
                    v -> props.getDashscope().setBaseUrl(v));
            applied += apply(stored, "ai.dashscope.compatible-base-url",
                    v -> props.getDashscope().setCompatibleBaseUrl(v));

            applied += apply(stored, "ai.models.text", v -> props.getModels().setText(v));
            applied += apply(stored, "ai.models.vision", v -> props.getModels().setVision(v));
            applied += apply(stored, "ai.models.ocr-primary",
                    v -> props.getModels().setOcrPrimary(v));
            applied += apply(stored, "ai.models.ocr-fallback",
                    v -> props.getModels().setOcrFallback(v));
            applied += apply(stored, "ai.models.asr", v -> props.getModels().setAsr(v));
            applied += apply(stored, "ai.models.embedding",
                    v -> props.getModels().setEmbedding(v));
            applied += apply(stored, "ai.models.rerank", v -> props.getModels().setRerank(v));

            applied += apply(stored, "ai.http.read-timeout-ms",
                    v -> props.getHttp().setReadTimeoutMs(parseInt(v, 120_000)));
            applied += apply(stored, "ai.http.connect-timeout-ms",
                    v -> props.getHttp().setConnectTimeoutMs(parseInt(v, 10_000)));
            applied += apply(stored, "ai.http.max-attempts",
                    v -> props.getHttp().setMaxAttempts(parseInt(v, 3)));

            // 数据库里可能存了空串或非法值，清掉以免覆盖掉合理的默认值
            props.normalize();
        } catch (Exception e) {
            // 配置表读不到不能阻止启动：环境变量仍然是一套完整可用的配置。
            // 但必须留痕——否则"设置里的改动没生效"会变成一个查不出原因的现象。
            log.warn("读取数据库中的 AI 配置失败，本次使用环境变量配置：{}", e.getMessage());
        }
        log.info("AI 配置已就绪：provider={} 文本模型={}（数据库覆盖项 {} 个）",
                props.getProvider(), props.getModels().getText(), applied);
    }

    /** 深拷贝当前配置，作为"回到基线"的凭据 */
    private static DashScopeProperties copyOf(DashScopeProperties src) {
        DashScopeProperties d = new DashScopeProperties();
        d.setProvider(src.getProvider());
        d.setRegion(src.getRegion());
        d.setPipelineVersion(src.getPipelineVersion());
        d.setPromptVersion(src.getPromptVersion());
        d.setRulesetVersion(src.getRulesetVersion());

        d.getDashscope().setApiKey(src.getDashscope().getApiKey());
        d.getDashscope().setBaseUrl(src.getDashscope().getBaseUrl());
        d.getDashscope().setCompatibleBaseUrl(src.getDashscope().getCompatibleBaseUrl());

        d.getModels().setText(src.getModels().getText());
        d.getModels().setVision(src.getModels().getVision());
        d.getModels().setOcrPrimary(src.getModels().getOcrPrimary());
        d.getModels().setOcrFallback(src.getModels().getOcrFallback());
        d.getModels().setAsr(src.getModels().getAsr());
        d.getModels().setEmbedding(src.getModels().getEmbedding());
        d.getModels().setRerank(src.getModels().getRerank());

        d.getHttp().setConnectTimeoutMs(src.getHttp().getConnectTimeoutMs());
        d.getHttp().setReadTimeoutMs(src.getHttp().getReadTimeoutMs());
        d.getHttp().setMaxAttempts(src.getHttp().getMaxAttempts());
        d.getHttp().setBackoffBaseMs(src.getHttp().getBackoffBaseMs());
        return d;
    }

    private void restore(DashScopeProperties from) {
        DashScopeProperties copy = copyOf(from);
        props.setProvider(copy.getProvider());
        props.setRegion(copy.getRegion());
        props.getDashscope().setApiKey(copy.getDashscope().getApiKey());
        props.getDashscope().setBaseUrl(copy.getDashscope().getBaseUrl());
        props.getDashscope().setCompatibleBaseUrl(copy.getDashscope().getCompatibleBaseUrl());
        props.getModels().setText(copy.getModels().getText());
        props.getModels().setVision(copy.getModels().getVision());
        props.getModels().setOcrPrimary(copy.getModels().getOcrPrimary());
        props.getModels().setOcrFallback(copy.getModels().getOcrFallback());
        props.getModels().setAsr(copy.getModels().getAsr());
        props.getModels().setEmbedding(copy.getModels().getEmbedding());
        props.getModels().setRerank(copy.getModels().getRerank());
        props.getHttp().setReadTimeoutMs(copy.getHttp().getReadTimeoutMs());
        props.getHttp().setConnectTimeoutMs(copy.getHttp().getConnectTimeoutMs());
        props.getHttp().setMaxAttempts(copy.getHttp().getMaxAttempts());
        props.getHttp().setBackoffBaseMs(copy.getHttp().getBackoffBaseMs());
    }

    /** 写入一项并立即生效 */
    public void update(String key, String value, Long operatorId) {
        sysConfigService.put(key, value, operatorId);
        applyStoredConfig();
    }

    /** 批量写入（界面点一次"保存"会改多项） */
    public void updateAll(Map<String, String> values, Long operatorId) {
        values.forEach((k, v) -> sysConfigService.put(k, v, operatorId));
        applyStoredConfig();
    }

    /**
     * 当前生效的配置快照。
     *
     * <p>API Key 只回掩码：设置界面需要让用户确认"填没填、填的是哪一把"，
     * 但完整回显会让任何有配置权限的人（以及浏览器缓存、代理日志）拿到它。
     */
    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("provider", props.getProvider());
        out.put("region", props.getRegion());

        String key = props.getDashscope().getApiKey();
        out.put("apiKeyConfigured", key != null && !key.isBlank());
        out.put("apiKeyHint", key == null || key.isBlank() ? null : mask(key));

        out.put("baseUrl", props.getDashscope().getBaseUrl());
        out.put("compatibleBaseUrl", props.getDashscope().getCompatibleBaseUrl());

        Map<String, Object> models = new LinkedHashMap<>();
        models.put("text", props.getModels().getText());
        models.put("vision", props.getModels().getVision());
        models.put("ocrPrimary", props.getModels().getOcrPrimary());
        models.put("ocrFallback", props.getModels().getOcrFallback());
        models.put("asr", props.getModels().getAsr());
        models.put("embedding", props.getModels().getEmbedding());
        models.put("rerank", props.getModels().getRerank());
        out.put("models", models);

        Map<String, Object> http = new LinkedHashMap<>();
        http.put("connectTimeoutMs", props.getHttp().getConnectTimeoutMs());
        http.put("readTimeoutMs", props.getHttp().getReadTimeoutMs());
        http.put("maxAttempts", props.getHttp().getMaxAttempts());
        out.put("http", http);
        return out;
    }

    // ── 工具 ────────────────────────────────────────────────────────────

    private static int apply(Map<String, String> stored, String key,
                             java.util.function.Consumer<String> setter) {
        String v = stored.get(key);
        if (v == null || v.isBlank()) {
            return 0;
        }
        setter.accept(v.trim());
        return 1;
    }

    private static int parseInt(String v, int fallback) {
        try {
            int n = Integer.parseInt(v.trim());
            return n > 0 ? n : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String mask(String key) {
        if (key.length() <= 8) {
            return "****";
        }
        return key.substring(0, 3) + "****" + key.substring(key.length() - 4);
    }
}
