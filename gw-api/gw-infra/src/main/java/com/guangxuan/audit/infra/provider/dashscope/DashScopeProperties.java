package com.guangxuan.audit.infra.provider.dashscope;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 百炼（DashScope）配置绑定。
 *
 * <p>把散落在 {@code @Value} 里的键集中到一处，是因为模型型号会随 M0 实测结果调整
 * （docs/00 的 M0 验证项 V1/V2/V6），散着写必然漏改。集中绑定后，
 * 换型号只改 {@code application.yml} 或环境变量。
 *
 * <p>型号默认值的取舍：默认写<b>快照版本号</b>而不是 {@code -latest} 之类的动态别名，
 * 避免供应商侧更新导致本项目行为漂移（docs/00 §"锁定快照版本号"）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "gw.ai")
public class DashScopeProperties {

    /** mock | dashscope */
    private String provider = "mock";

    /** 必须在华北2（北京）：RPM 600，而新加坡只有 60（docs/00 §2.1） */
    private String region = "cn-beijing";

    private DashScope dashscope = new DashScope();
    private Models models = new Models();

    /** 版本三元组：必须随每次模型调用落库（docs/03 D-10） */
    private String pipelineVersion = "0.1.0";
    private String promptVersion = "0.1.0";
    private String rulesetVersion = "0.1.0";

    /** 单次 HTTP 调用的超时与重试，可按现场网络调整 */
    private Http http = new Http();

    @Data
    public static class DashScope {
        private String apiKey = "";
        /** 原生 DashScope 接口（内置 OCR 任务等只有原生接口支持） */
        private String baseUrl = "https://dashscope.aliyuncs.com";
        /** OpenAI 兼容接口（文本对话、结构化输出） */
        private String compatibleBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    }

    @Data
    public static class Models {
        private String vision = "qwen3-vl-8b-thinking";
        private String text = "qwen-plus";
        private String ocrPrimary = "qwen-vl-ocr";
        private String ocrFallback = "qwen3.5-ocr";
        private String asr = "qwen3-asr-flash-filetrans";
        private String embedding = "text-embedding-v4";
        private String rerank = "qwen3-rerank";
    }

    @Data
    public static class Http {
        /** 连接超时（毫秒） */
        private int connectTimeoutMs = 10_000;
        /** 读取超时（毫秒）；视觉与 OCR 较慢，默认给足 */
        private int readTimeoutMs = 120_000;
        /** 最大尝试次数（含首次） */
        private int maxAttempts = 3;
        /** 退避基数（毫秒）：第 n 次重试等待 base * 2^(n-1) */
        private long backoffBaseMs = 800;
    }

    public boolean dashscopeEnabled() {
        return "dashscope".equalsIgnoreCase(provider);
    }

    /**
     * 空白值回落到默认值。
     *
     * <p>为什么需要这一步：docker-compose 里写 {@code GW_MODEL_TEXT: ${GW_MODEL_TEXT:-}}
     * 时，变量没配置会变成<b>空字符串而不是不存在</b>，而 Spring 的
     * {@code ${VAR:default}} 只在变量<b>缺失</b>时才用默认值——空字符串会一路传进来，
     * 最终表现为"模型名是空的"，调用时报一个很难定位的错。
     *
     * <p>把兜底放在代码里而不是 compose 里，还避免了默认值写两份、
     * 改一处漏一处。
     */
    @jakarta.annotation.PostConstruct
    void normalize() {
        Models d = new Models();
        models.setText(blankTo(models.getText(), d.getText()));
        models.setVision(blankTo(models.getVision(), d.getVision()));
        models.setOcrPrimary(blankTo(models.getOcrPrimary(), d.getOcrPrimary()));
        models.setOcrFallback(blankTo(models.getOcrFallback(), d.getOcrFallback()));
        models.setAsr(blankTo(models.getAsr(), d.getAsr()));
        models.setEmbedding(blankTo(models.getEmbedding(), d.getEmbedding()));
        models.setRerank(blankTo(models.getRerank(), d.getRerank()));

        DashScope dc = new DashScope();
        dashscope.setBaseUrl(blankTo(dashscope.getBaseUrl(), dc.getBaseUrl()));
        dashscope.setCompatibleBaseUrl(
                blankTo(dashscope.getCompatibleBaseUrl(), dc.getCompatibleBaseUrl()));

        if (http.getReadTimeoutMs() <= 0) {
            http.setReadTimeoutMs(new Http().getReadTimeoutMs());
        }
        if (http.getConnectTimeoutMs() <= 0) {
            http.setConnectTimeoutMs(new Http().getConnectTimeoutMs());
        }
        if (http.getMaxAttempts() <= 0) {
            http.setMaxAttempts(new Http().getMaxAttempts());
        }
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
