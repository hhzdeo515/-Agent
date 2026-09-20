package com.guangxuan.audit.infra.provider.dashscope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guangxuan.audit.common.error.DomainException;
import com.guangxuan.audit.common.error.ErrorCode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 百炼 HTTP 客户端：所有模型调用的唯一出口。
 *
 * <p>为什么集中到一个类，而不是每个适配器各自 {@code RestClient}：
 * <ul>
 *   <li><b>重试与限流</b>：百炼对 429 / 5xx 的正确处理是退避重试，散着写必然有一处漏；</li>
 *   <li><b>超时</b>：OCR 与视觉是长请求，超时值必须统一且足够，否则表现为"随机失败"；</li>
 *   <li><b>审计</b>：每次调用的模型、耗时、token 都要能落库（{@code ai_invocation}），
 *       出口只有一个才可能不漏账。</li>
 * </ul>
 *
 * <p><b>不做静默降级</b>：失败就抛 {@link DomainException}，由上层决定降级为
 * "待人工判断"。绝不返回空结果冒充成功——那会让"模型没看到"变成"没有风险"。
 */
@Slf4j
@Component
public class DashScopeClient {

    private final DashScopeProperties props;
    private final ObjectMapper objectMapper;
    private final RestClient http;

    public DashScopeClient(DashScopeProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;

        // 用 JDK HttpClient 而非默认 SimpleClientHttpRequestFactory：
        // 后者对长响应没有稳定的超时行为，OCR 这类几十秒的请求会假死。
        HttpClient jdk = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getHttp().getConnectTimeoutMs()))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdk);
        factory.setReadTimeout(Duration.ofMillis(props.getHttp().getReadTimeoutMs()));

        this.http = RestClient.builder().requestFactory(factory).build();
    }

    /**
     * 选了 dashscope 却没给 Key —— 启动就失败，而不是等第一次调用才报错。
     *
     * <p>理由：本项目原来的配置里写着 {@code mock | dashscope} 却没有 dashscope 实现，
     * 把 provider 改成 dashscope 会得到一个语焉不详的启动异常。宁可在这里
     * 用一句话说清"缺什么、去哪配"。
     */
    @PostConstruct
    void validate() {
        if (!props.dashscopeEnabled()) {
            log.info("AI 供应商 = mock（规则实现）。配置 DASHSCOPE_API_KEY 并把 GW_AI_PROVIDER 设为 dashscope 可启用千问模型。");
            return;
        }
        String key = props.getDashscope().getApiKey();
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                    "已选择 GW_AI_PROVIDER=dashscope，但 DASHSCOPE_API_KEY 为空。"
                            + "请在 deploy/.env 中填写阿里云百炼 API Key（形如 sk-xxxx），"
                            + "或把 GW_AI_PROVIDER 改回 mock 使用规则实现。");
        }
        log.info("AI 供应商 = 百炼千问。文本模型={} 视觉={} OCR={}/{} ASR={} 区域={}",
                props.getModels().getText(), props.getModels().getVision(),
                props.getModels().getOcrPrimary(), props.getModels().getOcrFallback(),
                props.getModels().getAsr(), props.getRegion());
    }

    public DashScopeProperties props() {
        return props;
    }

    // ════════════════════════════════════════════════════════════════════
    // 对外调用入口
    // ════════════════════════════════════════════════════════════════════

    /**
     * OpenAI 兼容接口的对话补全。
     *
     * @param model     模型名
     * @param messages  OpenAI 格式的 messages
     * @param jsonMode  是否要求 JSON 输出。⚠️ 只有部分模型支持（OCR 系列明确不支持），
     *                  因此<b>服务端仍必须做容错解析</b>，不能因为传了这个参数就认为一定拿到合法 JSON
     * @param extra     额外顶层参数（如 {@code enable_thinking}）
     * @return 模型返回的文本内容
     */
    public String chat(String model, Object messages, boolean jsonMode, Map<String, Object> extra) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        if (jsonMode) {
            body.put("response_format", Map.of("type", "json_object"));
        }
        if (extra != null) {
            body.putAll(extra);
        }

        JsonNode resp = exchange(
                props.getDashscope().getCompatibleBaseUrl() + "/chat/completions",
                body, model, "chat");
        return extractCompatibleText(resp, model);
    }

    /**
     * DashScope 原生多模态接口。
     *
     * <p>内置 OCR 任务（{@code ocr_options.task}）只有原生接口支持，兼容接口不认这个参数，
     * 这是官方文档明确区分开的两条路径。
     *
     * @return 原始响应 JSON（调用方自行取 {@code output.choices[0].message.content[0].text}）
     */
    public JsonNode multimodalNative(String model, Object messages, Map<String, Object> parameters) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("input", Map.of("messages", messages));
        if (parameters != null && !parameters.isEmpty()) {
            body.put("parameters", parameters);
        }
        return exchange(
                props.getDashscope().getBaseUrl()
                        + "/api/v1/services/aigc/multimodal-generation/generation",
                body, model, "multimodal");
    }

    /** 原生接口的纯文本模型调用（如 rerank、embedding 之外的文本任务） */
    public JsonNode nativePost(String path, Object body, String model, String label) {
        return exchange(props.getDashscope().getBaseUrl() + path, body, model, label);
    }

    /**
     * OpenAI 兼容接口上的任意 POST（{@code /embeddings}、{@code /reranks} 等）。
     *
     * <p>与 {@link #chat} 分开是因为这两类接口的请求体完全自定义，
     * 硬塞进 chat 的 messages 结构只会得到一个 400。
     */
    public JsonNode nativePostOnCompatible(String path, Object body, String model, String label) {
        return exchange(props.getDashscope().getCompatibleBaseUrl() + path, body, model, label);
    }

    /** 带异步头的 POST：ASR Filetrans 这类"提交任务"的调用需要 {@code X-DashScope-Async: enable} */
    public JsonNode postAsync(String path, Object body, String model, String label) {
        return exchange(props.getDashscope().getBaseUrl() + path, body, model, label,
                Map.of("X-DashScope-Async", "enable"));
    }

    /** GET：查询异步任务结果、下载 transcription_url 等 */
    public JsonNode get(String url, String label) {
        return exchange(url, null, null, label, null);
    }

    // ════════════════════════════════════════════════════════════════════
    // 内部
    // ════════════════════════════════════════════════════════════════════

    private JsonNode exchange(String url, Object body, String model, String label) {
        return exchange(url, body, model, label, null);
    }

    /**
     * 统一请求：鉴权、重试、错误映射、耗时日志。
     *
     * <p>重试策略只针对<b>可重试</b>的错误：429（限流）与 5xx（服务端）。
     * 4xx 里的 400/401/403 重试没有意义，重试只会把"配置错了"拖成"跑得慢"。
     */
    private JsonNode exchange(String url, Object body, String model, String label,
                              Map<String, String> extraHeaders) {
        int maxAttempts = Math.max(1, props.getHttp().getMaxAttempts());
        long backoff = props.getHttp().getBackoffBaseMs();
        RuntimeException last = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            long t0 = System.currentTimeMillis();
            try {
                RestClient.RequestBodySpec spec = http.post().uri(url)
                        .header(HttpHeaders.AUTHORIZATION,
                                "Bearer " + props.getDashscope().getApiKey())
                        // 解析 oss:// 临时文件所必需。官方明确：缺了它服务端无法解析该链接。
                        // 无条件加上是因为它只在真的用到 oss:// 时才起作用，
                        // 而"忘了加"表现为一个很像网络故障的报错，排查代价远高于多一个请求头。
                        .header("X-DashScope-OssResourceResolve", "enable")
                        .contentType(MediaType.APPLICATION_JSON);
                if (extraHeaders != null) {
                    extraHeaders.forEach(spec::header);
                }
                String raw;
                if (body == null) {
                    raw = http.get().uri(url)
                            // 只在百炼自己的域名上带 Key：异步任务的结果 URL 常在
                            // 阿里云 OSS 域（transcription_url 等），把 API Key 发到
                            // 第三方主机上属于凭证泄露，即使同一云厂商也不该发。
                            .headers(h -> {
                                if (isFirstParty(url)) {
                                    h.set(HttpHeaders.AUTHORIZATION,
                                            "Bearer " + props.getDashscope().getApiKey());
                                }
                            })
                            .retrieve()
                            .body(String.class);
                } else {
                    raw = spec.body(body).retrieve().body(String.class);
                }
                long cost = System.currentTimeMillis() - t0;
                JsonNode node = parse(raw);
                log.info("[dashscope] {} model={} 耗时={}ms attempt={}", label, model, cost, attempt);
                logUsage(label, model, node);
                return node;
            } catch (Exception e) {
                long cost = System.currentTimeMillis() - t0;
                last = toDomainException(e, label, model, cost);
                boolean retryable = isRetryable(e);
                if (!retryable || attempt == maxAttempts) {
                    log.error("[dashscope] {} model={} 第 {}/{} 次失败（{}ms，可重试={}）：{}",
                            label, model, attempt, maxAttempts, cost, retryable, e.getMessage());
                    throw last;
                }
                long wait = backoff * (1L << (attempt - 1)) + ThreadLocalRandom.current().nextLong(200);
                log.warn("[dashscope] {} model={} 第 {} 次失败，{}ms 后重试：{}",
                        label, model, attempt, wait, e.getMessage());
                sleep(wait);
            }
        }
        throw last == null
                ? new DomainException(ErrorCode.AI_CALL_FAILED, "模型调用失败：" + label) : last;
    }

    private JsonNode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new DomainException(ErrorCode.AI_CALL_FAILED, "模型返回空响应");
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            // 只截前 300 字：原文可能很长，也可能含物料内容，日志不宜全量落盘（AGENTS.md 第 12 条）
            String brief = raw.length() > 300 ? raw.substring(0, 300) + "…" : raw;
            throw new DomainException(ErrorCode.AI_CALL_FAILED,
                    "模型返回的不是合法 JSON：" + brief);
        }
    }

    /**
     * 业务错误码检测。
     *
     * <p>百炼在 HTTP 200 的同时也可能返回带 {@code code} 的业务错误，
     * 只看 HTTP 状态会把失败当成功——那是最危险的一类 bug：解析"成功了"，
     * 但锚点为空，界面上表现为"这份材料没风险"。
     */
    private void assertNoBusinessError(JsonNode node, String label) {
        if (node == null) {
            return;
        }
        JsonNode code = node.get("code");
        if (code != null && !code.isNull() && !"".equals(code.asText())) {
            String msg = node.path("message").asText("");
            throw new DomainException(ErrorCode.AI_CALL_FAILED,
                    "百炼返回业务错误 " + label + "：" + code.asText() + " " + msg);
        }
    }

    private String extractCompatibleText(JsonNode resp, String model) {
        assertNoBusinessError(resp, "chat");
        JsonNode choices = resp.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new DomainException(ErrorCode.AI_CALL_FAILED,
                    "模型 " + model + " 未返回 choices：" + brief(resp));
        }
        JsonNode message = choices.get(0).path("message");
        JsonNode content = message.path("content");
        if (content.isTextual()) {
            return content.asText();
        }
        // 多模态返回的 content 是数组，取其中所有 text 片段
        StringBuilder sb = new StringBuilder();
        if (content.isArray()) {
            for (JsonNode part : content) {
                JsonNode t = part.get("text");
                if (t != null && t.isTextual()) {
                    sb.append(t.asText());
                }
            }
        }
        if (sb.length() == 0) {
            throw new DomainException(ErrorCode.AI_CALL_FAILED,
                    "模型 " + model + " 返回内容为空：" + brief(resp));
        }
        return sb.toString();
    }

    /** 从原生多模态响应里取第一段文本 */
    public String extractNativeText(JsonNode resp, String model) {
        assertNoBusinessError(resp, "multimodal");
        JsonNode content = resp.path("output").path("choices").path(0)
                .path("message").path("content");
        if (content.isArray()) {
            for (JsonNode part : content) {
                JsonNode t = part.get("text");
                if (t != null && t.isTextual() && !t.asText().isBlank()) {
                    return t.asText();
                }
            }
        } else if (content.isTextual()) {
            return content.asText();
        }
        throw new DomainException(ErrorCode.AI_CALL_FAILED,
                "模型 " + model + " 未返回可读文本：" + brief(resp));
    }

    private void logUsage(String label, String model, JsonNode node) {
        JsonNode usage = node.path("usage");
        if (usage.isMissingNode() || usage.isNull()) {
            return;
        }
        // 字段名在不同接口下不一致（ASR 是 duration/seconds），因此逐个探测而不是取单一键
        log.info("[dashscope][usage] {} model={} in={} out={} total={} seconds={}",
                label, model,
                usage.path("input_tokens").asInt(usage.path("prompt_tokens").asInt(0)),
                usage.path("output_tokens").asInt(usage.path("completion_tokens").asInt(0)),
                usage.path("total_tokens").asInt(0),
                usage.path("seconds").asText(usage.path("duration").asText("-")));
    }

    private DomainException toDomainException(Exception e, String label, String model, long cost) {
        if (e instanceof DomainException de) {
            return de;
        }
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        // 常见的三类问题给出可操作的提示，而不是原样抛 HTTP 文本
        String hint = "";
        if (msg.contains("401") || msg.contains("InvalidApiKey")) {
            hint = "（API Key 无效或与所选区域不匹配：北京与新加坡的 Key 不通用）";
        } else if (msg.contains("429") || msg.contains("Throttling") || msg.contains("limit")) {
            hint = "（触发限流：请降低并发，或确认使用的是华北2北京区域的接入点）";
        } else if (msg.contains("timeout") || msg.contains("Timeout")) {
            hint = "（读取超时：可调大 gw.ai.http.read-timeout-ms）";
        }
        return new DomainException(ErrorCode.AI_CALL_FAILED,
                "调用百炼失败 " + label + "（model=" + model + "，" + cost + "ms）：" + msg + hint);
    }

    private boolean isRetryable(Exception e) {
        String msg = String.valueOf(e.getMessage());
        if (msg.contains("429") || msg.contains("Throttling") || msg.contains("500")
                || msg.contains("502") || msg.contains("503") || msg.contains("504")) {
            return true;
        }
        return e instanceof java.io.IOException
                || e instanceof java.net.SocketTimeoutException
                || e instanceof java.net.http.HttpTimeoutException;
    }

    /**
     * 是否是百炼自己的接口地址。
     *
     * <p>用于决定要不要附带 API Key：异步任务产出的 {@code transcription_url}
     * 等结果文件常落在阿里云 OSS 域，把 Bearer 凭证发过去属于凭证泄露。
     * 同一云厂商不等于同一信任域，因此按主机名白名单判断，而不是看后缀里有没有 aliyuncs。
     */
    private boolean isFirstParty(String url) {
        try {
            String host = java.net.URI.create(url).getHost();
            if (host == null) {
                return false;
            }
            String configured = java.net.URI.create(props.getDashscope().getBaseUrl()).getHost();
            if (host.equalsIgnoreCase(configured)) {
                return true;
            }
            // 业务空间专属域名形如 {WorkspaceId}.cn-beijing.maas.aliyuncs.com
            return host.endsWith(".maas.aliyuncs.com")
                    || host.equalsIgnoreCase("dashscope.aliyuncs.com")
                    || host.equalsIgnoreCase("dashscope-intl.aliyuncs.com");
        } catch (Exception e) {
            // 解析不出主机名就当作第三方，宁可不带 Key
            return false;
        }
    }

    private static String brief(JsonNode node) {        if (node == null) {
            return "null";
        }
        String s = node.toString();
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
