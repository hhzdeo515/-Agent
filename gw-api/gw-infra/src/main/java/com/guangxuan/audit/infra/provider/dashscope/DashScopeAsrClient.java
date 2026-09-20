package com.guangxuan.audit.infra.provider.dashscope;

import com.fasterxml.jackson.databind.JsonNode;
import com.guangxuan.audit.common.error.DomainException;
import com.guangxuan.audit.common.error.ErrorCode;
import com.guangxuan.audit.domain.port.ParsePort;
import com.guangxuan.audit.infra.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 百炼 ASR：录音文件识别（异步），语音转写 → 可定位到毫秒的句级/字级锚点。
 *
 * <h3>本节契约全部来自官方文档（不凭猜测编码）</h3>
 * <ul>
 *   <li>录音文件识别（Qwen-ASR）API 参考（提交/查询/异步结果结构，qwen3-asr-flash-filetrans 只支持异步）：
 *       <a href="https://www.alibabacloud.com/help/en/model-studio/qwen-asr-api-reference">model-studio/qwen-asr-api-reference</a>；
 *       中文页 <a href="https://help.aliyun.com/zh/model-studio/qwen-asr-api-reference">help.aliyun.com/zh/model-studio/qwen-asr-api-reference</a></li>
 *   <li>非实时语音识别使用指南（异步调用流程、24 小时有效期、说话人分离支持范围）：
 *       <a href="https://www.alibabacloud.com/help/en/model-studio/non-realtime-speech-recognition-user-guide">model-studio/non-realtime-speech-recognition-user-guide</a></li>
 *   <li>Paraformer 录音文件识别 HTTP API（字级 {@code sentences[].words[]} 结构、subtask 语义、
 *       "不支持 Base64、只接受公网 URL"的官方 FAQ）：
 *       <a href="https://www.alibabacloud.com/help/en/model-studio/paraformer-recorded-speech-recognition-restful-api">model-studio/paraformer-recorded-speech-recognition-restful-api</a></li>
 * </ul>
 *
 * <h3>已核实的关键字段</h3>
 * <pre>
 * 提交：POST {baseUrl}/api/v1/services/audio/asr/transcription
 *       Header: Authorization: Bearer &lt;key&gt; / Content-Type: application/json
 *               X-DashScope-Async: enable      ← 官方原文："Do not omit this request header.
 *                                                Otherwise, the task cannot be submitted."
 *       qwen3-asr-flash-filetrans : input.file_url  （单对象字符串）
 *       qwen-audio-3.0-* / fun-asr / paraformer : input.file_urls （数组）
 * 查询：GET  {baseUrl}/api/v1/tasks/{task_id}
 *       output.task_status ∈ PENDING | RUNNING | SUCCEEDED | FAILED | UNKNOWN(任务不存在)
 *       output.result.transcription_url   （qwen3-asr-flash-filetrans，单对象）
 *       output.results[].transcription_url（含 subtask_status，数组形态）
 * 下载：transcription_url 指向公网 JSON，官方明确"valid for 24 hours"
 * </pre>
 *
 * <h3>四个必须写进代码的陷阱（docs/00 §2.2、docs/03 §7）</h3>
 * <ol>
 *   <li><b>必须走 Filetrans 异步接口</b>：{@code qwen3-asr-flash} 走 OpenAI 兼容接口输出
 *       {@code chat.completion}，<b>不返回任何时间戳</b>——没有时间戳就无法把风险定位到视频时间点。
 *       本类因此<b>不提供</b>任何兼容接口方法，从 API 层面杜绝误用（docs/03 ADR D-12）。</li>
 *   <li><b>时间戳单位＝毫秒整数</b>：官方结果说明逐字写着 "The start timestamp of the sentence in
 *       milliseconds"。本类产出的 {@code beginMs}/{@code endMs} 一律是 {@code long} 毫秒。
 *       ⚠️ 任务级 {@code output.end_time} 与音频内 {@code sentences[].end_time}
 *       <b>同名不同义</b>：前者是任务完成时间，且是<b>日期字符串</b>（如
 *       {@code "2025-10-27 13:57:47.079"}）。本方法因此<b>从不读取任务级 end_time</b>，
 *       以"根本不碰"代替"小心不要碰"。</li>
 *   <li><b>transcription_url 仅 24 小时有效</b>：任务一成功就立刻下载并落盘 MinIO
 *       （且<b>先落盘再解析</b>，解析失败时原始结果仍在，可人工复核、不必重跑计费）。</li>
 *   <li><b>结果字段路径因模型而异</b>：{@code qwen3-asr-flash-filetrans} 是
 *       {@code output.result.transcription_url}（单对象）；{@code qwen-audio-3.0-asr-flash-filetrans} /
 *       {@code fun-asr} / {@code paraformer} 是 {@code output.results[].transcription_url}（数组）。
 *       <b>不可写死单一路径</b>，需按模型分派（见 {@link #usesFileUrlArray(String)}）。</li>
 * </ol>
 *
 * <h3>音频如何送达模型服务：结论是"必须公网可访问的 URL"</h3>
 * <p>本次已核实：<b>Filetrans 只接受公网可访问的音频 URL</b>，不支持直传文件、不支持 base64/Data URL。
 * <ul>
 *   <li>官方原文（Qwen3-ASR-Flash-Filetrans）："It accepts only public audio file URLs and
 *       <b>does not support local file uploads</b>"；请求参数 {@code file_url} 注明
 *       "The URL must be accessible over the public network."</li>
 *   <li>官方 FAQ（Paraformer）更直白："Does it support Base64-encoded audio? <b>No.</b>
 *       ... Only audio accessible via publicly accessible URLs is supported.
 *       Binary streams and direct local file recognition are not supported."</li>
 *   <li>文档里出现的 {@code data:<mime>;base64,...} 用法属于 <b>OpenAI 兼容接口</b>
 *       （{@code qwen3-asr-flash}）与声音复刻，<b>与 Filetrans 无关</b>；而兼容接口不返回时间戳，
 *       本项目禁用。所以"用 base64 绕过公网 URL"这条路在本项目里是<b>不通</b>的。</li>
 * </ul>
 * <p>本项目用 MinIO 且未对外暴露，因此默认情况下<b>无法</b>把音频送到模型服务。
 * 这里刻意<b>不假装上传成功</b>：拿不到公网 URL 就抛
 * {@link ErrorCode#AI_PROVIDER_UNAVAILABLE}，把"需要预签名外链或 OSS 中转"这件事说清楚。
 * 部署侧接入后，只需提供一个 {@link AudioUrlResolver} Bean（见该类注释），本方法立即可用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DashScopeAsrClient {

    // ── 官方 endpoint（相对 gw.ai.dashscope.base-url）────────────────────────
    // 北京区域为 https://dashscope.aliyuncs.com/api/v1/...；
    // 官方另有工作空间专属域名 {WorkspaceId}.cn-beijing.maas.aliyuncs.com，改 base-url 即可切换。

    /** 提交转写任务 */
    private static final String ASR_SUBMIT_PATH = "/api/v1/services/audio/asr/transcription";
    /** 查询任务结果：完整地址 {@code /api/v1/tasks/{task_id}} */
    private static final String ASR_TASK_PATH_PREFIX = "/api/v1/tasks/";

    // ── 轮询与上限 ────────────────────────────────────────────────────────
    // 之所以硬编码为常量：DashScopeProperties 里没有 ASR 专属配置项，而本次改动被限制在单个文件内。
    // TODO 待 gw.ai 配置面扩展后，把这三项挪到 gw.ai.asr.poll-timeout-ms / poll-interval-ms / poll-interval-max-ms。

    /** 最大等待时长：10 分钟。官方称异步任务"typically completes within a few minutes"，10 分钟足够且能兜住队列拥塞 */
    private static final long POLL_TIMEOUT_MS = 10 * 60 * 1000L;
    /** 首次轮询间隔 3 秒；查询接口默认 20 QPS，3 秒一次远低于限流线 */
    private static final long POLL_INTERVAL_MS = 3_000L;
    /** 退避上限 15 秒：长音频可能跑十几分钟，固定 3 秒轮询会白白浪费几百次请求 */
    private static final long POLL_INTERVAL_MAX_MS = 15_000L;
    /** 连续多少次既不是终态、也不是 PENDING/RUNNING 的状态就放弃（防止状态语义变更导致死等到超时） */
    private static final int MAX_UNEXPECTED_STATUS = 5;

    /** 官方单文件上限：≤2GB、时长 ≤12 小时（docs/00 §2.2）。超限要在提交前就失败，别浪费一次计费任务 */
    private static final long MAX_AUDIO_BYTES = 2L * 1024 * 1024 * 1024;

    /** 从对象键里取出 case/material/version，用于把原始转写 JSON 落成派生对象 */
    private static final Pattern OBJECT_KEY_PATTERN =
            Pattern.compile("^case/(\\d+)/material/(\\d+)/([^/]+)/.+$");

    protected final DashScopeClient client;
    private final StorageService storage;

    /**
     * 音频公网可达 URL 的可选注入点。
     *
     * <p>为什么做成"可选注入点"而不是在本类里直接实现：当前项目<b>没有</b>预签名能力——
     * {@code gw.storage.presign-expiry-seconds: 900} 只是一个配置键，
     * 代码里没有任何 {@code getPresignedObjectUrl} 调用（README 亦写明"预签名直传 URL 仍未实现"），
     * 且 MinIO 即便签出 URL 也指向内网 endpoint，模型服务取不到。
     * 而 {@code StorageService} 本次不允许修改，因此这里只留扩展点：
     * 部署侧接入对象存储外链（MinIO 前置公网网关 / 阿里云 OSS 中转）后，注册一个
     * {@code AudioUrlResolver} Bean 即可，无需再改本类。
     *
     * <p>用 {@link ObjectProvider} 而不是 {@code @Autowired(required=false)}：
     * 零个实现时容器照样启动，缺失在<b>调用时</b>被翻译成一句可操作的部署提示，而不是启动期报错。
     */
    @FunctionalInterface
    public interface AudioUrlResolver {
        /**
         * @param objectKey MinIO 对象键
         * @param mimeType  物料 MIME 类型（决定中转时的 Content-Type）
         * @return 模型服务可访问的 http(s) URL；拿不到返回 null
         */
        String resolve(String objectKey, String mimeType);
    }

    private final ObjectProvider<AudioUrlResolver> audioUrlResolvers;

    /**
     * 语音识别；视频需先抽音轨。
     *
     * <p>流程：解析公网 URL → 预检音频可读 → 提交异步任务 → 轮询 → 下载转写 JSON（并立即落盘）
     * → 解析为毫秒锚点。任何一个环节拿不到有效结果都抛异常，
     * <b>绝不返回空列表冒充成功</b>——空结果会让下游把"模型没识别到"读成"这份材料没有风险"。
     */
    public ParsePort.AsrResult asr(ParsePort.AsrRequest request) {
        if (request == null || request.objectKey() == null || request.objectKey().isBlank()) {
            throw new DomainException(ErrorCode.AI_CALL_FAILED,
                    "语音转写缺少音频对象键（objectKey 为空），无法提交识别任务");
        }
        String model = client.props().getModels().getAsr();

        // ① 先解决"模型能不能拿到文件"。这一步是零成本的纯配置判断，
        //    放在读文件之前：否则一个部署配置问题会先白白吃掉一次全量音频读取。
        String fileUrl = resolveAudioUrl(request);

        // ② 预检音频对象。读到的字节不送给模型（见类注释：Filetrans 不支持 base64/直传），
        //    这里的价值在于：把"文件取不到/是空的/超过 2GB"这类物料级问题，
        //    在提交计费任务之前就用正确的错误码暴露出来，而不是等 10 分钟拿到
        //    InvalidFile.DownloadFailed 再回头猜。
        //    代价（如实说明）：整个音频进内存。StorageService 只暴露 exists()/getBytes()，
        //    拿不到长度，因此"2GB 上限"这个判断只能在读完之后做——真遇到 2GB 级文件，
        //    readAllBytes 可能先 OOM，那时报出来的是内存错误，而不是这条可操作的提示。
        //    也就是说该上限是"尽力而为的护栏"，不是可靠的前置校验。
        //    TODO 待 StorageService 提供 statObject/长度查询后（本次改动被限制在单个文件内），
        //         改为"先查长度，再决定要不要读"。
        long audioBytes = preflightAudio(request);

        // ③ 提交异步任务
        String taskId = submitTask(model, fileUrl, request);

        // ④ 轮询到终态
        long submittedAt = System.currentTimeMillis();
        JsonNode taskOutput = awaitTask(taskId, model, submittedAt);

        // ⑤ 按模型分派取 transcription_url
        String transcriptionUrl = extractTranscriptionUrl(taskOutput, model, taskId);

        // ⑥ 立刻下载。官方明确该 URL 仅 24 小时有效，过期后任务查询与结果下载都失败，
        //    且重跑要重新计费——所以"下载"紧跟在"SUCCEEDED"之后，中间不做任何别的耗时操作。
        JsonNode transcript = client.get(transcriptionUrl, "asr-transcript");
        if (transcript == null || transcript.isMissingNode() || transcript.isNull()) {
            throw new DomainException(ErrorCode.AI_CALL_FAILED,
                    "语音转写结果下载为空（task_id=" + taskId + "，model=" + model + "）");
        }

        // ⑦ 先落盘、后解析：解析代码将来若因字段变化抛错，原始结果仍在 MinIO 里，
        //    可以人工复核或离线重放，不必重新提交一次计费任务。
        String rawObjectKey = storeRawTranscript(request, transcript, taskId);

        // ⑧ 解析为毫秒锚点
        List<ParsePort.AsrSentence> sentences = toSentences(transcript, request.wordLevelTimestamp());
        String language = detectLanguage(transcript);

        log.info("[dashscope][asr] 完成 model={} task={} 句数={} 字级={} 语言={} 音频={}KB 原始结果={}",
                model, taskId, sentences.size(), request.wordLevelTimestamp(), language,
                audioBytes / 1024, rawObjectKey == null ? "未落盘" : rawObjectKey);

        // engineVersion 只能给 null：官方异步响应里没有模型快照版本号（只有 request_id）。
        // docs/03 期望的 modelSnapshot 无法从响应获取，这里不编造。
        // TODO 若要精确对账，应在提交时把 model 参数与 request_id 一并写入 ai_invocation。
        return new ParsePort.AsrResult(sentences, language, model, null, rawObjectKey);
    }

    // ════════════════════════════════════════════════════════════════════
    // ① 音频送达：公网 URL
    // ════════════════════════════════════════════════════════════════════

    /**
     * 取模型服务可访问的音频 URL。
     *
     * <p>拿不到就抛 {@link ErrorCode#AI_PROVIDER_UNAVAILABLE}，并且把"要做什么"写清楚。
     * 这里<b>不做</b>任何"先用 MinIO 内网地址试试看"的尝试：内网地址模型侧一定下载不到，
     * 试一次只会得到一条语焉不详的 {@code InvalidFile.DownloadFailed}，还会产生一次计费任务。
     */
    private String resolveAudioUrl(ParsePort.AsrRequest request) {
        AudioUrlResolver resolver = audioUrlResolvers.getIfAvailable();
        if (resolver != null) {
            String url = resolver.resolve(request.objectKey(), request.mimeType());
            if (url != null && !url.isBlank()) {
                log.debug("[dashscope][asr] 使用外部解析器提供的音频 URL（objectKey={}）", request.objectKey());
                return url;
            }
            log.warn("[dashscope][asr] AudioUrlResolver 未返回可用 URL（objectKey={}），按未暴露对象存储处理",
                    request.objectKey());
        }
        // 这条消息直接面向部署人员，因此只说"缺什么、怎么补"，不暴露内网对象键细节。
        throw new DomainException(ErrorCode.AI_PROVIDER_UNAVAILABLE,
                "音频文件需要模型服务可访问的 URL，当前对象存储未对外暴露；请在部署配置中开启预签名外链或改用 OSS 中转");
    }

    /**
     * 音频可读性与大小预检。
     *
     * @return 音频字节数
     */
    private long preflightAudio(ParsePort.AsrRequest request) {
        // StorageService.getBytes 在对象不存在时抛 STORAGE_ERROR，这里让它原样冒泡：
        // "文件不在存储里"与"AI 服务调不通"是两类完全不同的故障，错误码不能混。
        byte[] audio = storage.getBytes(request.objectKey());
        if (audio == null || audio.length == 0) {
            throw new DomainException(ErrorCode.STORAGE_ERROR,
                    "音频对象为空（0 字节）：" + request.objectKey() + "；请重新上传该物料的音轨文件");
        }
        if (audio.length > MAX_AUDIO_BYTES) {
            throw new DomainException(ErrorCode.AI_CALL_FAILED,
                    "音频文件超过百炼异步识别的单文件上限 2GB（实际 " + (audio.length / 1024 / 1024)
                            + "MB）：请先抽音轨并压缩后再提交识别");
        }
        // mimeType 不做白名单校验：官方明确支持 aac/wav/mp3 等主流音视频格式，
        // 在这里凭 mimeType 拒绝会造成"假失败"。是否先抽音轨由上层（docs/03 §3.2 的①）决定。
        return audio.length;
    }

    // ════════════════════════════════════════════════════════════════════
    // ③ 提交任务
    // ════════════════════════════════════════════════════════════════════

    /**
     * 提交转写任务，返回 {@code task_id}。
     *
     * <p>请求体只放<b>官方文档里明确列出的参数</b>：没查到的参数宁可不传
     * （传未文档化的参数有被 400 拒绝的风险，而项目文档明确禁止凭猜测编码）。
     */
    private String submitTask(String model, String fileUrl, ParsePort.AsrRequest request) {
        boolean arrayStyle = usesFileUrlArray(model);

        Map<String, Object> input = new LinkedHashMap<>();
        if (arrayStyle) {
            // qwen-audio-3.0-asr-flash-filetrans / fun-asr / paraformer：数组形态（单次仅支持 1 个 URL）
            input.put("file_urls", List.of(fileUrl));
        } else {
            // qwen3-asr-flash-filetrans：单对象形态，官方示例为 "input": {"file_url": "..."}
            input.put("file_url", fileUrl);
        }

        Map<String, Object> parameters = new LinkedHashMap<>();
        // 只识别第 0 轨。官方特别提示多轨分别计费，因此这里固定单轨，不做用户不可控的翻倍花费。
        parameters.put("channel_id", List.of(0));

        if (arrayStyle) {
            // 这几个模型的句级/字级时间戳官方标注为"固定开启"，文档未列出 enable_words / enable_itn，
            // 因此不传。说话人分离则是它们独有的参数（qwen3-asr-flash-filetrans 不支持）。
            if (request.speakerDiarization()) {
                parameters.put("diarization_enabled", true);
                // 官方建议：开启说话人分离时音频控制在 2 小时以内，否则可能识别失败或超时。
                log.info("[dashscope][asr] 已开启说话人分离（官方建议音频 ≤2 小时）");
            }
            // TODO language_hints 暂不传：官方只说明 paraformer-v2 适用、
            //      qwen-audio-3.0 示例中出现过，支持范围不确定，故不猜。需要时按型号单独核实。
        } else {
            // enable_words：官方对 qwen3-asr-flash-filetrans 的定义是
            //   false（默认）→ 句级时间戳，且断句仅基于 VAD；
            //   true         → 字级时间戳，断句基于 VAD + 标点。
            // 即它同时改变"返回粒度"和"断句规则"，不只是多一个字段，所以必须显式传，不能省。
            parameters.put("enable_words", request.wordLevelTimestamp());
            // enable_itn 官方默认 false，这里显式写 false 并说明理由：
            // ITN 会把"百分之百"归一成"100%"、把口语数字转成阿拉伯数字。
            // 广宣审核要审的是受众实际看到/听到的表述，改写用词会削弱"证据原文"的可信度，
            // 也可能让风险规则（如绝对化用语）匹配不到。因此保持模型原始用词。
            parameters.put("enable_itn", false);

            if (request.speakerDiarization()) {
                // 官方未给 qwen3-asr-flash-filetrans 列出 diarization_enabled（docs/00 §2.2 该列亦为"—"）。
                // 不传该参数，但必须让"你要的说话人分离这次没有"可见，而不是静默降级成"speakerId 全 null"。
                log.warn("[dashscope][asr] 请求了说话人分离，但模型 {} 的官方文档未列出 diarization_enabled，"
                        + "本次不发送该参数，结果中 speakerId 将为 null。"
                        + "若必须分离说话人，请改用 qwen-audio-3.0-asr-flash-filetrans / fun-asr / paraformer", model);
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("input", input);
        body.put("parameters", parameters);

        // postAsync 负责带上 X-DashScope-Async: enable —— 官方原文：漏掉这个头任务根本提交不了。
        JsonNode resp = client.postAsync(ASR_SUBMIT_PATH, body, model, "asr-submit");
        assertNoBusinessError(resp, "asr-submit");

        String taskId = resp.path("output").path("task_id").asText("");
        if (taskId.isBlank()) {
            throw new DomainException(ErrorCode.AI_CALL_FAILED,
                    "提交语音转写任务未返回 task_id（model=" + model + "）：" + brief(resp));
        }
        log.info("[dashscope][asr] 已提交转写任务 task_id={} model={} 字级={}", taskId, model,
                request.wordLevelTimestamp());
        return taskId;
    }

    // ════════════════════════════════════════════════════════════════════
    // ④ 轮询
    // ════════════════════════════════════════════════════════════════════

    /**
     * 轮询任务直到终态，返回 {@code output} 节点。
     *
     * <p>为什么必须有最大等待时长：查询接口的 {@code PENDING} 可能因队列拥塞长时间不推进，
     * 没有上限就会把一个 HTTP 工作线程永久挂住，表现为"解析卡住"而无人知道原因。
     */
    private JsonNode awaitTask(String taskId, String model, long submittedAt) {
        String url = client.props().getDashscope().getBaseUrl() + ASR_TASK_PATH_PREFIX + taskId;
        long interval = POLL_INTERVAL_MS;
        int unexpected = 0;

        while (true) {
            long waitedMs = System.currentTimeMillis() - submittedAt;
            if (waitedMs >= POLL_TIMEOUT_MS) {
                throw new DomainException(ErrorCode.AI_CALL_FAILED,
                        "语音转写超时（已等待 " + waitedMs / 1000 + " 秒，上限 " + POLL_TIMEOUT_MS / 1000
                                + " 秒）：task_id=" + taskId + "，model=" + model
                                + "。可用该 task_id 在百炼控制台继续核查；常见原因是队列拥塞、"
                                + "音频过大，或音频 URL 模型侧下载不稳定");
            }

            JsonNode resp = client.get(url, "asr-query");
            assertNoBusinessError(resp, "asr-query");
            JsonNode output = resp.path("output");
            String status = output.path("task_status").asText("");

            if ("SUCCEEDED".equalsIgnoreCase(status)) {
                log.info("[dashscope][asr] 任务完成 task_id={} 等待={}s", taskId, waitedMs / 1000);
                return output;
            }
            if ("FAILED".equalsIgnoreCase(status)) {
                // 官方：失败时 output.code / output.message 才有值（如 FILE_403_FORBIDDEN
                // 表示音频 URL 模型侧取不到——正是"没有公网外链"的典型症状）。
                throw new DomainException(ErrorCode.AI_CALL_FAILED,
                        "语音转写任务失败：task_id=" + taskId + "，model=" + model
                                + "，code=" + output.path("code").asText("(未返回)")
                                + "，message=" + output.path("message").asText("(未返回)"));
            }
            if ("UNKNOWN".equalsIgnoreCase(status)) {
                // 官方定义：任务不存在或状态未知。继续等没有意义。
                throw new DomainException(ErrorCode.AI_CALL_FAILED,
                        "语音转写任务不存在或状态未知（task_id=" + taskId
                                + "）：请确认 API Key 的区域与任务提交区域一致（北京与新加坡的 Key 不通用）");
            }

            if ("PENDING".equalsIgnoreCase(status) || "RUNNING".equalsIgnoreCase(status)) {
                unexpected = 0;
            } else {
                // 出现没见过的状态：容忍几次（可能是官方新增中间态），但连续多次就失败，
                // 避免把"状态语义变了"伪装成"一直很慢"直到超时。
                unexpected++;
                log.warn("[dashscope][asr] 未知任务状态 '{}'（{}/{}）task_id={}", status, unexpected,
                        MAX_UNEXPECTED_STATUS, taskId);
                if (unexpected >= MAX_UNEXPECTED_STATUS) {
                    throw new DomainException(ErrorCode.AI_CALL_FAILED,
                            "语音转写返回了无法识别的任务状态 '" + status + "'（task_id=" + taskId
                                    + "）：接口语义可能已变更，需要更新解析代码");
                }
            }

            sleepInterruptibly(interval, taskId, submittedAt);
            // 温和退避 1.5 倍：既不会像固定 3 秒那样在长音频上打出几百次请求，
            // 也不会像指数退避那样把"刚跑完"的结果拖到十几秒后才被取走。
            interval = Math.min(interval * 3 / 2, POLL_INTERVAL_MAX_MS);
        }
    }

    private void sleepInterruptibly(long ms, String taskId, long submittedAt) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            // 恢复中断标记：解析是跑在线程池里的，吞掉中断会让"取消任务"失效。
            Thread.currentThread().interrupt();
            throw new DomainException(ErrorCode.AI_CALL_FAILED,
                    "语音转写等待被中断（已等待 " + (System.currentTimeMillis() - submittedAt) / 1000
                            + " 秒，task_id=" + taskId + "）");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // ⑤ 结果路径分派（陷阱 4）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 取 {@code transcription_url}，<b>按模型分派</b>而非写死单一路径。
     *
     * <p>若按文档预期取不到，会再按另一种结构兜一次并打 WARN：运营期换型号不应让整条链路直接断掉，
     * 但也不能静默——WARN 日志保证"字段结构和预期不一致"这件事可被观测到。
     */
    private String extractTranscriptionUrl(JsonNode output, String model, String taskId) {
        if (usesFileUrlArray(model)) {
            String url = fromResultsArray(output, taskId);
            if (url != null) {
                return url;
            }
            log.warn("[dashscope][asr] 模型 {} 预期为 results[] 数组结构但未取到 transcription_url，"
                    + "回退尝试 result 单对象结构", model);
            String fallback = output.path("result").path("transcription_url").asText("");
            if (!fallback.isBlank()) {
                return fallback;
            }
        } else {
            String url = output.path("result").path("transcription_url").asText("");
            if (!url.isBlank()) {
                return url;
            }
            log.warn("[dashscope][asr] 模型 {} 预期为 result 单对象结构但未取到 transcription_url，"
                    + "回退尝试 results[] 数组结构", model);
            String fallback = fromResultsArray(output, taskId);
            if (fallback != null) {
                return fallback;
            }
        }
        throw new DomainException(ErrorCode.AI_CALL_FAILED,
                "转写任务已完成但未返回 transcription_url（task_id=" + taskId + "，model=" + model
                        + "）：结果字段路径可能与预期不同，请核对官方文档后更新分派逻辑");
    }

    /**
     * 数组结构：{@code output.results[].transcription_url} + {@code subtask_status}。
     *
     * <p>官方明确：只要有一个子任务成功，整个任务的 {@code task_status} 就是 {@code SUCCEEDED}，
     * 因此<b>不能</b>看到 SUCCEEDED 就直接取第 0 个元素的 URL，必须逐个看 {@code subtask_status}。
     *
     * @return 可用的 URL；没有任何可用子任务时返回 null
     */
    private String fromResultsArray(JsonNode output, String taskId) {
        JsonNode results = output.path("results");
        if (!results.isArray() || results.isEmpty()) {
            return null;
        }
        List<String> failures = new ArrayList<>();
        for (JsonNode r : results) {
            String subStatus = r.path("subtask_status").asText("");
            String url = r.path("transcription_url").asText("");
            if (!"FAILED".equalsIgnoreCase(subStatus) && !url.isBlank()) {
                return url;
            }
            failures.add(r.path("code").asText("(无 code)") + " " + r.path("message").asText(""));
        }
        throw new DomainException(ErrorCode.AI_CALL_FAILED,
                "语音转写全部子任务失败（task_id=" + taskId + "）：" + String.join("；", failures)
                        + "。若 code 为 InvalidFile.DownloadFailed / FILE_403_FORBIDDEN，"
                        + "说明模型侧取不到音频，请检查音频 URL 是否公网可达、且不含未编码的中文或空格");
    }

    /**
     * 单对象形态对应的是 Qwen3-ASR-Flash-Filetrans：{@code input.file_url}（单数）。
     *
     * <p>用前缀判定而不是精确型号枚举：官方随时会出新的快照/别名（如 {@code fun-asr-mtl}），
     * 前缀判定能让新型号自动落到正确的分支上，而不是掉进"未知型号"的缝隙里。
     */
    private static boolean usesFileUrlArray(String model) {
        String m = model == null ? "" : model.toLowerCase(Locale.ROOT);
        return m.startsWith("qwen-audio") || m.startsWith("fun-asr") || m.startsWith("paraformer");
    }

    // ════════════════════════════════════════════════════════════════════
    // ⑦ 原始结果落盘（陷阱 3：24 小时有效）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 把原始转写 JSON 存成 MinIO 派生对象，返回对象键；存不了就返回 {@code null} 并留 ERROR 日志。
     *
     * <p>目的：让"当时模型到底返回了什么"可追溯（docs/03 要求写 {@code parse_artifact.raw_object_key}）。
     * 落盘失败<b>不阻断</b>主链路——识别结果本身已经拿到，为了审计副本丢掉整次识别反而更糟；
     * 但必须打 ERROR，因为 {@code rawObjectKey} 为空意味着这次的证据链缺了一环。
     *
     * <p>两点如实说明：
     * <ol>
     *   <li>存的是 {@code JsonNode} 重新序列化后的等价 JSON，不是字节级原文。
     *       要做到字节级留存，需要 {@link DashScopeClient} 暴露未解析的响应体，
     *       而本次改动被限制在本文件内。</li>
     *   <li>派生路径借 {@code versionLabel} 传 {@code "<versionLabel>/derived"}：
     *       {@link StorageService#put} 只有那五个槽位，无法在不改 {@code StorageService} 的前提下
     *       指定任意派生路径。TODO 若后续为 StorageService 增加 {@code putDerived(...)}，此处应改调它。</li>
     * </ol>
     */
    private String storeRawTranscript(ParsePort.AsrRequest request, JsonNode transcript, String taskId) {
        Matcher m = OBJECT_KEY_PATTERN.matcher(request.objectKey());
        if (!m.matches()) {
            log.warn("[dashscope][asr] 无法从对象键解析出 case/material/version，原始转写 JSON 未落盘：{}",
                    request.objectKey());
            return null;
        }
        try {
            StorageService.UploadedFile uploaded = storage.put(
                    Long.valueOf(m.group(1)), Long.valueOf(m.group(2)),
                    m.group(3) + "/derived",
                    "asr-transcription-" + taskId + ".json",
                    transcript.toString().getBytes(StandardCharsets.UTF_8),
                    "application/json");
            return uploaded.objectKey();
        } catch (DomainException e) {
            log.error("[dashscope][asr] 原始转写 JSON 落盘失败（task_id={}，objectKey={}）：{}"
                            + "——本次识别的证据链缺少原始结果副本，需人工核查",
                    taskId, request.objectKey(), e.getMessage());
            return null;
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // ⑧ 解析：毫秒锚点
    // ════════════════════════════════════════════════════════════════════

    /**
     * 转写 JSON → 句级锚点（毫秒）。
     *
     * <p>结构（官方异步结果说明）：
     * <pre>
     * { "transcripts": [ { "channel_id":0, "text":"…",
     *     "sentences": [ { "begin_time":100, "end_time":3820, "text":"…",
     *                      "sentence_id":0, "language":"zh", "speaker_id":0,
     *                      "words":[{"begin_time":100,"end_time":596,"text":"你好","punctuation":""}] } ] } ] }
     * </pre>
     * {@code begin_time}/{@code end_time} 单位是<b>毫秒整数</b>（官方逐字："in milliseconds"）。
     *
     * <p><b>绝不给缺失的时间戳兜底成 0</b>：0 会被下游当成"视频第 0 毫秒"渲染成一个精确的时间点，
     * 法务据此回放却找不到内容，比直接说明"这句没有时间戳、已跳过"更糟。
     */
    private List<ParsePort.AsrSentence> toSentences(JsonNode root, boolean wantWords) {
        JsonNode transcripts = root.path("transcripts");
        if (!transcripts.isArray() || transcripts.isEmpty()) {
            throw new DomainException(ErrorCode.AI_CALL_FAILED,
                    "语音转写结果为空：transcription JSON 中没有 transcripts[]。"
                            + "常见原因：音频没有有效人声、抽音轨失败，或提交的文件其实不是音频。"
                            + "本项目不把空结果当成功——空结果会让下游把\"没识别到\"误读成\"没有风险\"");
        }

        List<ParsePort.AsrSentence> out = new ArrayList<>();
        int ordinal = 0;
        int skippedNoTimestamp = 0;
        int skippedBlankText = 0;
        int missingWords = 0;

        for (JsonNode track : transcripts) {
            for (JsonNode s : sentenceNodes(track)) {
                // 毫秒整数。用 asLong(哨兵值) 而不是 asLong(0)：0 是合法时间戳，不能拿来表示"缺失"。
                long beginMs = s.path("begin_time").asLong(-1L);
                long endMs = s.path("end_time").asLong(-1L);
                if (beginMs < 0 || endMs < beginMs) {
                    skippedNoTimestamp++;
                    continue;
                }
                String text = s.path("text").asText("");
                if (text.isBlank()) {
                    skippedBlankText++;
                    continue;
                }
                ordinal++;
                // sentence_id：优先用供应商原值（Qwen3 官方说明从 0 开始，Paraformer 示例从 1 开始，
                // 本项目不纠正、不重排，原样透传以便与供应商结果对账）；缺失时才用递增序号兜底。
                int sentenceId = s.path("sentence_id").asInt(ordinal);

                // speaker_id 只在开启说话人分离时出现；没有就是 null。
                // 用 canConvertToInt 判定而不是仅仅非空：显式 null 也要落成 null，不能变成 0（0 是合法说话人编号）。
                Integer speakerId = null;
                JsonNode speakerNode = s.get("speaker_id");
                if (speakerNode != null && speakerNode.canConvertToInt()) {
                    speakerId = speakerNode.asInt();
                }

                List<ParsePort.AsrWord> words = null;
                if (wantWords) {
                    words = toWords(s);
                    if (words == null) {
                        missingWords++;
                    }
                }

                out.add(new ParsePort.AsrSentence(text, beginMs, endMs, sentenceId, speakerId,
                        textOrNull(s.get("emotion")), words));
            }
        }

        if (out.isEmpty()) {
            throw new DomainException(ErrorCode.AI_CALL_FAILED,
                    "语音转写未产出任何可用句子（跳过缺时间戳 " + skippedNoTimestamp + " 句、空文本 "
                            + skippedBlankText + " 句）：结果结构或音频内容异常，已按失败处理");
        }
        if (skippedNoTimestamp > 0 || skippedBlankText > 0 || missingWords > 0) {
            // 降级必须可见：这些句子不会被送进审核，法务需要知道"少看了什么"。
            log.warn("[dashscope][asr] 解析降级：跳过缺时间戳 {} 句、空文本 {} 句；"
                            + "请求了字级时间戳但有 {} 句未返回字级数据",
                    skippedNoTimestamp, skippedBlankText, missingWords);
        }
        return out;
    }

    /**
     * 取句级数组。
     *
     * <p>官方异步结果说明把 {@code sentences} 标成 {@code object}，但描述是
     * "A list of sentence-level recognition results"，而示例与通用实践都是数组。
     * 这是文档自身的不一致，因此这里两种都接受：数组按列表遍历；
     * 单个对象（带 {@code begin_time}）按一句处理；都不像就打 WARN 跳过，
     * 而不是让整条链路因为一处文档歧义直接崩掉。
     */
    private List<JsonNode> sentenceNodes(JsonNode track) {
        JsonNode sentences = track.path("sentences");
        if (sentences.isArray()) {
            List<JsonNode> list = new ArrayList<>(sentences.size());
            sentences.forEach(list::add);
            return list;
        }
        if (sentences.has("begin_time")) {
            return List.of(sentences);
        }
        if (!sentences.isMissingNode() && !sentences.isNull()) {
            log.warn("[dashscope][asr] sentences 结构无法识别（既不是数组也不是单句对象），已跳过该音轨");
        }
        return List.of();
    }

    /**
     * 字级时间戳。
     *
     * <p>字段名以官方 filetrans 结果说明为准：{@code sentences[].words[]}，元素含
     * {@code begin_time}/{@code end_time}（毫秒整数）、{@code text}、{@code punctuation}
     * （Paraformer HTTP API 的"识别结果说明"逐项列出）。
     * ⚠️ Qwen-ASR 的异步结果说明<b>只列到句级</b>，没有写出字级字段名；{@code enable_words=true}
     * 的语义是"返回字级时间戳"（官方原文），但键名未在文档中给出。
     * 因此这里额外容忍 {@code words_info} 这一别名，并在首次命中别名时打 WARN 记录下来——
     * 目的是"遇到没见过的键名要能被看见"，而不是猜一个字段名硬编码进解析逻辑。
     *
     * <p>{@code punctuation} 刻意丢弃：{@link ParsePort.AsrWord} 没有承载标点的槽位，
     * 拼进 {@code text} 会改变模型原始输出（本项目的证据原文必须与模型返回一致）；
     * 句级 {@code text} 已带完整标点，检索与展示以句级为准。
     *
     * @return 字级列表；未返回时 null（<b>不返回空列表</b>，空列表会掩盖"字级没拿到"这件事）
     */
    private List<ParsePort.AsrWord> toWords(JsonNode sentence) {
        JsonNode words = sentence.get("words");
        if (words == null || !words.isArray() || words.isEmpty()) {
            JsonNode alias = sentence.get("words_info");
            if (alias != null && alias.isArray() && !alias.isEmpty()) {
                log.warn("[dashscope][asr] 字级时间戳出现在 words_info 字段上（官方 Qwen-ASR 结果说明未列出该键名），"
                        + "本次按 words 的等价结构解析；请核对官方文档并更新注释");
                words = alias;
            } else {
                return null;
            }
        }
        List<ParsePort.AsrWord> out = new ArrayList<>(words.size());
        for (JsonNode w : words) {
            long beginMs = w.path("begin_time").asLong(-1L);
            long endMs = w.path("end_time").asLong(-1L);
            if (beginMs < 0 || endMs < beginMs) {
                continue; // 与句级同样处理：时间戳缺失就丢弃该字，不编造 0
            }
            out.add(new ParsePort.AsrWord(w.path("text").asText(""), beginMs, endMs));
        }
        return out.isEmpty() ? null : out;
    }

    /** 语言：官方在 {@code sentences[].language} 上给出，取第一处非空值 */
    private String detectLanguage(JsonNode root) {
        for (JsonNode track : root.path("transcripts")) {
            for (JsonNode s : sentenceNodes(track)) {
                String lang = textOrNull(s.get("language"));
                if (lang != null) {
                    return lang;
                }
            }
        }
        return null;
    }

    // ════════════════════════════════════════════════════════════════════
    // 工具
    // ════════════════════════════════════════════════════════════════════

    /**
     * 业务错误检测。
     *
     * <p>{@link DashScopeClient} 的同类检查是私有的、且只用在其文本路径上，
     * 而 {@code get}/{@code postAsync} 不做检查。百炼有可能在 HTTP 200 的同时返回带
     * {@code code} 的业务错误——只看 HTTP 状态会把失败当成功，那是最危险的一类 bug：
     * 解析"成功了"，但锚点为空，界面上表现为"这份材料没风险"。
     */
    private void assertNoBusinessError(JsonNode node, String label) {
        if (node == null) {
            return;
        }
        JsonNode code = node.get("code");
        if (code != null && !code.isNull() && !code.asText().isBlank()) {
            throw new DomainException(ErrorCode.AI_CALL_FAILED,
                    "百炼返回业务错误 " + label + "：" + code.asText() + " " + node.path("message").asText(""));
        }
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isNull() || !node.isTextual()) {
            return null;
        }
        String s = node.asText();
        return s.isBlank() ? null : s;
    }

    /** 只截前 300 字：响应里可能带物料相关内容，日志不宜全量落盘（AGENTS.md 第 12 条） */
    private static String brief(JsonNode node) {
        if (node == null) {
            return "null";
        }
        String s = node.toString();
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
