package com.guangxuan.audit.infra.provider.dashscope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guangxuan.audit.common.error.DomainException;
import com.guangxuan.audit.common.error.ErrorCode;
import com.guangxuan.audit.domain.port.ParsePort;
import com.guangxuan.audit.infra.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 百炼 OCR：图片文字识别（qwen-vl-ocr）+ 画面语义理解（qwen3-vl 系列）。
 *
 * <h3>已核实的接口契约</h3>
 * <ul>
 *   <li>内置 OCR 任务<b>只有 DashScope 原生接口支持</b>，兼容接口不认 {@code ocr_options}：<br>
 *       {@code POST {baseUrl}/api/v1/services/aigc/multimodal-generation/generation}<br>
 *       请求体 {@code {"model":"qwen-vl-ocr","input":{"messages":[{"role":"user","content":[{"image":"<url或base64>","min_pixels":3072,"max_pixels":8388608}]}]},"parameters":{"ocr_options":{"task":"text_recognition"}}}}</li>
 *   <li>响应取 {@code output.choices[0].message.content[0].text}（用 {@link DashScopeClient#extractNativeText}）</li>
 *   <li>{@code task} 取值：{@code text_recognition}（纯文本）/ {@code document_parsing}（LaTeX）/
 *       {@code table_parsing}（HTML）/ {@code formula_recognition}（LaTeX）</li>
 *   <li>OCR 模型<b>不支持结构化输出</b>，返回纯文本，必须自行切行</li>
 * </ul>
 *
 * <h3>坐标：本类对"没有坐标"是当正常路径处理的</h3>
 * <p>官方文档没有给出内置 OCR 任务的坐标返回格式，这是项目里已登记的未决项（docs/00 V1）。
 * 已由用户确认的产品决策：拿不到坐标就 {@link ParsePort.OcrResult#localized()} 置 false，
 * 由前端显示"大致区域"，<b>不得</b>补假 bbox。因此本类先做一次保守探测
 * （见 {@link #extractLocalizedLines}）：只有坐标语义能从字段名读出来才采信，
 * 读不出语义就如实返回 {@code localized=false, x=y=w=h=0, confidence=null}。
 *
 * <p><b>未验证项</b>：本类未做过真实 API 调用（开发环境没有 API Key）。
 * 首次接入时请按 docs/05 的 V1 用例跑一张真实海报，
 * 核对 {@code [dashscope-ocr] content 元素字段=…} 这条日志确认坐标形态后再调整探测逻辑。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "gw.ai.provider", havingValue = "dashscope")
public class DashScopeOcrClient {

    protected final DashScopeClient client;
    private final StorageService storageService;
    private final ObjectMapper objectMapper;

    /** 纯文本识别任务。刻意不用 document_parsing：它以 LaTeX 输出，会把海报上的正常文字转义成公式语法 */
    private static final String TASK_TEXT_RECOGNITION = "text_recognition";

    /**
     * 送模型的像素区间。
     *
     * <p>按官方契约显式给出上下限，而不是依赖模型默认值——默认值在供应商侧会变，
     * 而"识别质量"一旦漂移是无声的（与"锁定快照版本号"同一条理由）。
     */
    private static final int MIN_PIXELS = 3072;
    private static final int MAX_PIXELS = 8388608;

    /**
     * 可能承载文字坐标的字段名，按可信度排序。
     *
     * <p>写成候选列表而不是写死一个名字，是因为该返回格式尚未核实（docs/00 V1）。
     * 命中候选名只是"有个像坐标的字段"，还要过 {@link #rectangleOf} 的语义校验才作数。
     */
    private static final List<String> COORD_KEYS = List.of(
            "bbox", "box", "rect", "rectangle", "quad", "polygon", "poly", "points",
            "coordinates", "coordinate", "coord", "position", "location");

    /** 常见的置信度字段名 */
    private static final List<String> CONFIDENCE_KEYS = List.of(
            "confidence", "score", "prob", "probability");

    /** 剥代码围栏：围栏可能带语言标注（```json），也可能只补了开头没补结尾 */
    private static final Pattern FENCE = Pattern.compile("(?i)```[ \\t]*[a-z]*");

    /** 模型名里的日期快照号，兼容 {@code 2025-04-13} 与 {@code 20250413} 两种写法 */
    private static final Pattern SNAPSHOT = Pattern.compile("(\\d{4})[-_]?(\\d{2})[-_]?(\\d{2})");

    // ════════════════════════════════════════════════════════════════════
    // OCR
    // ════════════════════════════════════════════════════════════════════

    /** 图片文字识别 */
    public ParsePort.OcrResult ocr(ParsePort.OcrRequest request) {
        byte[] bytes = readObject(request == null ? null : request.objectKey(), "OCR");
        String image = dataUrl(bytes, request.mimeType());

        // languageHint（调用方传 CHN_ENG）只在日志留痕：官方 ocr_options 契约里没有语言开关，
        // 为了"用上这个参数"塞一个未经核实的字段，换来的只会是 400。
        if (request.languageHint() != null && !request.languageHint().isBlank()) {
            log.debug("[dashscope-ocr] languageHint={}（原生接口无对应参数，未随请求下发）", request.languageHint());
        }

        String primary = client.props().getModels().getOcrPrimary();
        String fallback = client.props().getModels().getOcrFallback();
        try {
            return recognize(primary, image);
        } catch (DomainException e) {
            if (fallback == null || fallback.isBlank() || fallback.equals(primary)) {
                throw e;
            }
            // D-04 双模型策略：主模型（qwen-vl-ocr，Batch 单价减半）失败时换质量兜底模型再跑一次。
            // 兜底放在这一层，是因为只有这里知道"该换哪个模型"；上层重试同一个模型，
            // 对"压根没识别出文字"这类失败毫无意义。
            log.warn("[dashscope-ocr] 主模型 {} 失败，改由兜底模型 {} 重试一次：{}",
                    primary, fallback, e.getMessage());
            return recognize(fallback, image);
        }
    }

    /**
     * 用指定模型跑一次内置 OCR 任务。
     *
     * <p>"没有坐标"在这里走正常返回路径而不是异常：产品已确认它可由前端以"大致区域"呈现。
     */
    private ParsePort.OcrResult recognize(String model, String image) {
        Map<String, Object> imagePart = new LinkedHashMap<>();
        // 用 LinkedHashMap 而不是 Map.of：Map.of 不接受 null 值，且这里的键顺序要与官方示例逐行对照，
        // 排查时一眼看得出少传了哪个字段
        imagePart.put("image", image);
        imagePart.put("min_pixels", MIN_PIXELS);
        imagePart.put("max_pixels", MAX_PIXELS);
        imagePart.put("enable_rotate", false);

        Map<String, Object> parameters = Map.of(
                "ocr_options", Map.of("task", TASK_TEXT_RECOGNITION));

        JsonNode resp = client.multimodalNative(
                model,
                List.of(Map.of("role", "user", "content", List.of(imagePart))),
                parameters);

        String text = requireText(resp, model);
        List<ParsePort.OcrLine> localized = extractLocalizedLines(resp, model);
        List<ParsePort.OcrLine> lines = localized != null ? localized : splitLines(text);
        String version = snapshotOf(model);

        log.info("[dashscope-ocr] model={} 版本={} 文字行={} 是否带坐标={} 字符数={}",
                model, version == null ? "（动态别名，无快照号）" : version,
                lines.size(), localized != null, text.length());
        return new ParsePort.OcrResult(lines, localized != null, model, version);
    }

    /**
     * 取识别文本；取不到就报"物料解析失败"，而不是返回空行列表。
     *
     * <p>为什么必须抛：空结果在上层等价于"这张图没有文字"，风险识别阶段自然也就"没有发现风险"——
     * 一次模型故障会被静默翻译成一次合规通过，这是本项目最不能接受的一类失败。
     */
    private String requireText(JsonNode resp, String model) {
        // 业务错误（模型名不存在、参数非法、额度不足…）由 extractNativeText 内部的
        // assertNoBusinessError 抛 AI_CALL_FAILED，那种错误必须原样保留：改写成"解析失败"
        // 会把排查方向错误地引到物料本身上去
        boolean businessError = resp != null && resp.hasNonNull("code")
                && !resp.path("code").asText().isBlank();
        String text = null;
        try {
            text = client.extractNativeText(resp, model);
        } catch (DomainException e) {
            if (businessError) {
                throw e;
            }
            log.warn("[dashscope-ocr] model={} 响应里没有可读文本：{}", model, e.getMessage());
        }
        if (text == null || text.isBlank()) {
            throw new DomainException(ErrorCode.MATERIAL_PARSE_FAILED,
                    "OCR 未识别出任何文字（model=" + model + "）。响应结构：" + describeResponse(resp)
                            + "。常见原因：图片本身不含文字、图片损坏或格式与 data URL 声明不符、"
                            + "模型名与所选区域不匹配。请确认物料内容后重新上传。");
        }
        return text;
    }

    /**
     * 尝试取回带坐标的文字行；没有可信坐标时返回 null。
     *
     * <p><b>为什么先探测再决定</b>：内置 OCR 任务的坐标返回格式是未决项（docs/00 V1）。
     * 若供应商其实返回了坐标，而我们固定 {@code localized=false}，就把"可精确框选"
     * 平白降级成"只能给大致区域"，白丢定位精度。
     *
     * <p><b>为什么只认语义明确的形状</b>：坐标的字段名/形状决定每个数字的含义。
     * 一个光秃秃的 [a,b,c,d] 既可能是 x,y,w,h，也可能是 x1,y1,x2,y2；猜错就会在界面上
     * 画出一个位置看似精确、内容实际错位的框，法务照着框去找却找不到——这比明说"没有坐标"更糟
     * （docs/00 §3.3 约束 4；AGENTS.md 第 11 条禁止编造）。因此"看得出有坐标、但读不出语义"
     * 的响应只记录结构日志（键名，不含取值），留作 V1 实测的对照依据，不参与坐标计算。
     */
    private List<ParsePort.OcrLine> extractLocalizedLines(JsonNode resp, String model) {
        JsonNode content = resp == null ? null
                : resp.path("output").path("choices").path(0).path("message").path("content");
        if (content == null || !content.isArray()) {
            return null;
        }

        List<ParsePort.OcrLine> lines = new ArrayList<>();
        Set<String> partKeys = new LinkedHashSet<>();
        int lineNo = 0;
        for (JsonNode part : content) {
            partKeys.addAll(fieldNames(part));
            String text = part.path("text").asText("").strip();
            JsonNode coord = firstPresent(part, COORD_KEYS);
            if (coord == null || text.isEmpty()) {
                continue;
            }
            int[] box = rectangleOf(coord);
            if (box == null) {
                log.warn("[dashscope-ocr] model={} 第 {} 段有坐标字段但语义无法确认，已弃用：字段={}",
                        model, lineNo, fieldNames(part));
                continue;
            }
            lines.add(new ParsePort.OcrLine(text, box[0], box[1], box[2], box[3],
                    confidenceOf(part, model), lineNo));
            lineNo++;
        }

        // 只打键名不打取值：响应里带的是物料原文，日志不得落物料内容（AGENTS.md 第 12 条）
        log.info("[dashscope-ocr] model={} content 元素字段={} 可用坐标行={}",
                model, partKeys.isEmpty() ? "（无）" : partKeys, lines.size());
        return lines.isEmpty() ? null : lines;
    }

    /**
     * 把坐标节点规约成 x/y/w/h（左上原点、像素）。
     *
     * <p>只接受两种形状：
     * <ul>
     *   <li><b>具名对象</b>：字段名写明语义——x/left 与 y/top 给左上角，w/width 与 h/height 给尺寸，
     *       或由右下角 x2/y2/right/bottom 换算；</li>
     *   <li><b>点数组</b>：元素是点（{@code {"x":..,"y":..}} 或 {@code [x,y]}），即 polygon/points
     *       的标准写法，取外接矩形。</li>
     * </ul>
     * 其余形状（尤其四个裸数字）一律返回 null：宁可没有坐标，也不猜语义。
     */
    private int[] rectangleOf(JsonNode coord) {
        if (coord == null) {
            return null;
        }
        if (coord.isObject()) {
            Integer x = intField(coord, "x", "left", "x1", "xmin", "x_min");
            Integer y = intField(coord, "y", "top", "y1", "ymin", "y_min");
            if (x == null || y == null) {
                return null;
            }
            Integer w = intField(coord, "w", "width");
            Integer h = intField(coord, "h", "height");
            if (w == null) {
                Integer x2 = intField(coord, "x2", "right", "xmax", "x_max");
                w = x2 == null ? null : x2 - x;
            }
            if (h == null) {
                Integer y2 = intField(coord, "y2", "bottom", "ymax", "y_max");
                h = y2 == null ? null : y2 - y;
            }
            return valid(x, y, w, h, coord);
        }
        if (coord.isArray()) {
            return rectangleOfPoints(coord);
        }
        return null;
    }

    private int[] rectangleOfPoints(JsonNode points) {
        if (points.size() < 2) {
            return null;
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (JsonNode p : points) {
            Integer px = pointCoord(p, 0, "x", "left");
            Integer py = pointCoord(p, 1, "y", "top");
            // 有一个元素不是点就整体不认：既挡掉 [x,y,w,h] 这种扁平数组，
            // 也避免"部分点解析成功"后画出半个框
            if (px == null || py == null) {
                return null;
            }
            minX = Math.min(minX, px);
            minY = Math.min(minY, py);
            maxX = Math.max(maxX, px);
            maxY = Math.max(maxY, py);
        }
        return valid(minX, minY, maxX - minX, maxY - minY, points);
    }

    private Integer pointCoord(JsonNode point, int index, String... names) {
        if (point.isObject()) {
            return intField(point, names);
        }
        if (point.isArray() && point.size() > index) {
            return integerOf(point.get(index));
        }
        return null;
    }

    /**
     * 坐标必须能构成一个真实存在的矩形。
     *
     * <p>宽高为 0 或负（含"全 0"）一律判为无效：全 0 既可能是"没有坐标"，
     * 也可能是"坐标恰好在左上角"，两者必须能被区分开（ParsePort 的设计意图），
     * 所以宁可退回 {@code localized=false}。
     */
    private int[] valid(Integer x, Integer y, Integer w, Integer h, JsonNode raw) {
        if (x == null || y == null || w == null || h == null) {
            log.debug("[dashscope-ocr] 坐标节点不构成有效像素矩形（归一化小数在此会被弃用）：{}",
                    truncate(String.valueOf(raw), 120));
            return null;
        }
        if (x < 0 || y < 0 || w <= 0 || h <= 0) {
            log.debug("[dashscope-ocr] 坐标越界或退化，已弃用：x={} y={} w={} h={}", x, y, w, h);
            return null;
        }
        return new int[]{x, y, w, h};
    }

    /**
     * 行级置信度。
     *
     * <p>只接受 0~1 的写法；超出这个范围说明量纲不明（有的引擎给 0~100 的百分数），
     * 此时按缺失处理，而不是自己除以 100——置信度直接影响"是否需要人工判断"，
     * 猜量纲等于伪造一个能改变流程走向的数字。
     */
    private Double confidenceOf(JsonNode part, String model) {
        for (String key : CONFIDENCE_KEYS) {
            JsonNode v = part.get(key);
            if (v == null || v.isNull()) {
                continue;
            }
            Double d = doubleOf(v);
            if (d == null) {
                continue;
            }
            if (d >= 0 && d <= 1) {
                return d;
            }
            log.warn("[dashscope-ocr] model={} 字段 {}={} 超出 0~1，量纲不明，按缺失处理", model, key, d);
            return null;
        }
        return null;
    }

    /**
     * 按换行切行。
     *
     * <p>OCR 模型不返回行号，因此 {@code lineNo} 用文本块内的顺序号（0 起）：
     * 它至少保证"第几行"与模型返回的文本块严格对应，便于人工逐行核对漏识别；
     * 编一个供应商行号反而是假账。
     */
    private List<ParsePort.OcrLine> splitLines(String text) {
        List<ParsePort.OcrLine> lines = new ArrayList<>();
        int lineNo = 0;
        // 用 -1 保留末尾空行：每个下标都严格对应原文的第几行，与模型输出对得上
        for (String raw : text.split("\r\n|\r|\n", -1)) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            // localized=false 时坐标无意义，一律 0；不填任何"看起来合理"的估计值
            lines.add(new ParsePort.OcrLine(line, 0, 0, 0, 0, null, lineNo));
            lineNo++;
        }
        return lines;
    }

    // ════════════════════════════════════════════════════════════════════
    // 画面语义理解
    // ════════════════════════════════════════════════════════════════════

    /**
     * 画面语义理解。
     *
     * <p>用视觉理解模型（默认 {@code qwen3-vl-8b-thinking}，它<b>支持</b>结构化输出，
     * 与 OCR 系列不同）。要求模型返回 JSON，包含整体描述与逐条画面要素。
     */
    public ParsePort.VisionResult vision(ParsePort.VisionRequest request) {
        byte[] bytes = readObject(request == null ? null : request.objectKey(), "画面语义理解");
        String image = dataUrl(bytes, request.mimeType());
        String model = client.props().getModels().getVision();

        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of("type", "text", "text", visionPrompt(request.question())));
        // 兼容接口的图片写法是 image_url + data URL，与原生接口的 {"image": ...} 不同。
        // 混用的结果是 400，而不是"识别质量差"——所以两种写法在代码里必须分开、不能"看起来差不多就复用"
        content.add(Map.of("type", "image_url", "image_url", Map.of("url", image)));

        // jsonMode 只是"请求"结构化输出：模型仍可能加围栏或前后说明，因此下面必须容错解析。
        // extra 传 null：本调用不需要 enable_thinking 之类的额外开关
        String raw = client.chat(model,
                List.of(Map.of("role", "user", "content", content)), true, null);

        JsonNode json = extractJson(raw, model);
        String description = json.path("description").asText("").strip();
        if (description.isEmpty()) {
            throw new DomainException(ErrorCode.AI_OUTPUT_INVALID,
                    "画面语义输出缺少 description（model=" + model + "）：" + truncate(raw, 200));
        }

        // 字段缺失与"返回空数组"必须区分：前者是"模型没说"，后者是"模型明确说没有"。
        // 把前者当成"画面没有问题"，就是最危险的那类静默降级
        JsonNode findingsNode = json.get("findings");
        if (findingsNode == null || findingsNode.isNull() || !findingsNode.isArray()) {
            throw new DomainException(ErrorCode.AI_OUTPUT_INVALID,
                    "画面语义输出的 findings 不是数组（model=" + model + "）：" + truncate(raw, 200));
        }
        List<String> findings = new ArrayList<>();
        int dropped = 0;
        for (JsonNode f : findingsNode) {
            if (f.isTextual() && !f.asText().isBlank()) {
                findings.add(f.asText().strip());
            } else {
                dropped++;
            }
        }
        if (dropped > 0) {
            // 个别脏元素不至于让整份画面结论作废，但必须留痕：丢掉了几条要看得见
            log.warn("[dashscope-vision] model={} findings 中 {} 项不是非空字符串，已跳过（保留 {} 项）",
                    model, dropped, findings.size());
        }

        log.info("[dashscope-vision] model={} 描述={}字 画面要素={}条",
                model, description.length(), findings.size());
        return new ParsePort.VisionResult(description, findings, model, snapshotOf(model));
    }

    /** 画面语义 Prompt；法务在接收区填的关注点会被拼进来，但不能取代固定输出契约 */
    private static final String VISION_PROMPT = """
            你是广告合规初审的**画面**分析助手。请只描述这张宣传物料里真实可见的内容。

            要求：
            1. 先整体看一遍画面，用中文写出一段客观、具体的整体描述（画面里有什么、在表达什么卖点）。
            2. 再逐条列出**可能与广告合规相关**的画面要素，例如：未标注依据的对比图或实测画面、
               暗示疗效或安全承诺的画面、贬低竞品的呈现方式、无法核实的资质/认证/奖项标识、
               画面中出现的绝对化文字（国家级、最高级、第一等）、容易让人误解的效果示意。
            3. 每条都要说清"画面上看到了什么"（文字原样摘录，并说明它出现在哪个位置），
               不要下法律结论，不要补充画面里看不到的品牌、型号、参数、数据或证明材料。
            4. 没有发现相关画面要素时，findings 返回空数组；**不要为了凑数编造**。
            5. 只输出 JSON，不要输出 JSON 以外的任何文字，也不要加代码围栏。

            输出格式（字段名逐字一致）：
            {"description":"画面整体描述","findings":["可能与广告合规相关的画面要素，一条一个字符串"]}
            """;

    private String visionPrompt(String question) {
        if (question == null || question.isBlank()) {
            return VISION_PROMPT;
        }
        // 审核关注点要真的进 Prompt，否则它在链路上只是界面装饰；
        // 但只能作为"额外关注"，不能替换上面那套固定输出契约，否则解析层会失配
        return VISION_PROMPT + "\n本次审核额外关注：" + question.strip();
    }

    /**
     * 容错取出视觉模型输出的 JSON 对象。
     *
     * <p>思路与 {@code DashScopeAiAdapter.extractJson} 一致（那份是私有方法，跨类用不上；
     * 这里只服务一个调用点，就不为此提前抽公共工具类）。差异是本方法只处理<b>对象</b>输出，
     * 因此直接取第一个左花括号到最后一个右花括号之间，省掉一次注定失败的整体解析。
     *
     * <p>清洗后仍不是合法 JSON 就抛 AI_OUTPUT_INVALID 转人工判断，<b>不返回空结果</b>：
     * 空结果会被下游读成"画面没问题"。
     */
    private JsonNode extractJson(String raw, String model) {
        if (raw == null || raw.isBlank()) {
            throw new DomainException(ErrorCode.AI_OUTPUT_INVALID,
                    "视觉模型 " + model + " 返回空响应，得不到画面语义");
        }
        String cleaned = FENCE.matcher(raw.strip()).replaceAll("");
        int from = cleaned.indexOf('{');
        int to = cleaned.lastIndexOf('}');
        if (from < 0 || to <= from) {
            throw new DomainException(ErrorCode.AI_OUTPUT_INVALID,
                    "视觉模型 " + model + " 的输出里找不到 JSON 对象：" + truncate(raw, 200));
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(cleaned.substring(from, to + 1));
        } catch (Exception e) {
            throw new DomainException(ErrorCode.AI_OUTPUT_INVALID,
                    "视觉模型 " + model + " 的输出不是合法 JSON：" + truncate(raw, 200));
        }
        if (node == null || !node.isObject()) {
            throw new DomainException(ErrorCode.AI_OUTPUT_INVALID,
                    "视觉模型 " + model + " 的输出不是 JSON 对象：" + truncate(raw, 200));
        }
        return node;
    }

    // ════════════════════════════════════════════════════════════════════
    // 素材读取与通用工具
    // ════════════════════════════════════════════════════════════════════

    /** 从对象存储取物料字节；取件本身失败不是"AI 调用失败"，两者的处置方式不同 */
    private byte[] readObject(String objectKey, String label) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new DomainException(ErrorCode.MATERIAL_PARSE_FAILED,
                    label + " 缺少对象键（objectKey），无法取到物料文件");
        }
        return storageService.getBytes(objectKey);
    }

    /**
     * 拼 base64 data URL。
     *
     * <p>内联 base64 而不是先转存一份公网 URL：物料可能含未发布产品信息，
     * 少一个可被外部访问的副本就少一条泄露路径（AGENTS.md 第 12 条）。
     */
    private String dataUrl(byte[] bytes, String mimeType) {
        String mime = imageMime(bytes, mimeType);
        String url = "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes);
        // 体积只记日志、不设阈值：官方文档未给出本接口的图片体积上限，本项目不编造限制值；
        // 但当"随机失败"发生时，这条日志是第一手线索
        log.debug("[dashscope-ocr] 素材 {} bytes，data URL {} chars，mime={}",
                bytes.length, url.length(), mime);
        return url;
    }

    /**
     * 确定 data URL 的媒体类型。
     *
     * <p>优先用调用方给的 mimeType；缺失或不以 image/ 开头时按文件头字节兜底。
     * 为什么不照抄调用方的值：data URL 的 media type 决定模型怎么解码，
     * 声明错了（上传链路里常见 application/octet-stream）会让模型报"图片无法解析"，
     * 而这种报错在界面上看起来像"这份物料本身有问题"，排查方向完全跑偏。
     */
    private String imageMime(byte[] bytes, String mimeType) {
        if (mimeType != null && !mimeType.isBlank()) {
            String mime = mimeType.strip().toLowerCase();
            if (mime.startsWith("image/")) {
                // 少数客户端写 image/jpg，规范名是 image/jpeg
                return "image/jpg".equals(mime) ? "image/jpeg" : mime;
            }
        }
        String sniffed = sniffMime(bytes);
        if (sniffed == null) {
            throw new DomainException(ErrorCode.MATERIAL_PARSE_FAILED,
                    "无法确定物料图片格式（mimeType=" + mimeType + "，文件头也不是已知图片格式），"
                            + "当前只支持 PNG / JPEG / WebP / GIF / BMP。请确认上传的文件确实是图片。");
        }
        log.info("[dashscope-ocr] mimeType={} 不可用，按文件头判定为 {}", mimeType, sniffed);
        return sniffed;
    }

    /** 按文件头识别图片格式；识别不出返回 null（不猜） */
    private static String sniffMime(byte[] b) {
        if (b == null || b.length < 4) {
            return null;
        }
        if ((b[0] & 0xFF) == 0x89 && (b[1] & 0xFF) == 0x50
                && (b[2] & 0xFF) == 0x4E && (b[3] & 0xFF) == 0x47) {
            return "image/png";
        }
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (b.length >= 12 && (b[0] & 0xFF) == 0x52 && (b[1] & 0xFF) == 0x49
                && (b[2] & 0xFF) == 0x46 && (b[3] & 0xFF) == 0x46
                && (b[8] & 0xFF) == 0x57 && (b[9] & 0xFF) == 0x45
                && (b[10] & 0xFF) == 0x42 && (b[11] & 0xFF) == 0x50) {
            return "image/webp";
        }
        if ((b[0] & 0xFF) == 0x47 && (b[1] & 0xFF) == 0x49 && (b[2] & 0xFF) == 0x46) {
            return "image/gif";
        }
        if ((b[0] & 0xFF) == 0x42 && (b[1] & 0xFF) == 0x4D) {
            return "image/bmp";
        }
        return null;
    }

    /**
     * 从模型名里抽快照号。
     *
     * <p>为什么必须有这个值：docs/03 D-10 要求每次模型调用都留下"用的是哪一版"。
     * 模型名里带日期（如 {@code qwen-vl-ocr-2025-04-13}）时它就是快照号；
     * 不带日期（如 {@code qwen-vl-ocr}）说明是动态别名，供应商侧会随时更新，
     * 此时返回 null 而不是编一个版本号——编出来的版本会让"这份结论基于哪版模型"彻底失去可追溯性。
     */
    private static String snapshotOf(String model) {
        if (model == null || model.isBlank()) {
            return null;
        }
        Matcher m = SNAPSHOT.matcher(model);
        return m.find() ? m.group(1) + "-" + m.group(2) + "-" + m.group(3) : null;
    }

    /** 响应结构的键名摘要：只列层级与字段名，不含任何取值（响应里带的是物料原文） */
    private String describeResponse(JsonNode resp) {
        if (resp == null) {
            return "null";
        }
        JsonNode output = resp.path("output");
        JsonNode message = output.path("choices").path(0).path("message");
        JsonNode content = message.path("content");
        List<String> parts = new ArrayList<>();
        if (content.isArray()) {
            for (JsonNode part : content) {
                parts.add(fieldNames(part).toString());
            }
        } else if (content.isTextual()) {
            parts.add("<纯文本 content>");
        }
        return "顶层" + fieldNames(resp) + " output" + fieldNames(output)
                + " message" + fieldNames(message) + " content" + parts;
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new LinkedHashSet<>();
        if (node != null && node.isObject()) {
            node.fieldNames().forEachRemaining(names::add);
        }
        return names;
    }

    /** 取候选字段里第一个有内容的节点（空容器视为没有） */
    private static JsonNode firstPresent(JsonNode node, List<String> keys) {
        if (node == null || !node.isObject()) {
            return null;
        }
        for (String key : keys) {
            JsonNode v = node.get(key);
            if (v != null && !v.isNull() && !(v.isContainerNode() && v.isEmpty())) {
                return v;
            }
        }
        return null;
    }

    private static Integer intField(JsonNode obj, String... names) {
        for (String name : names) {
            JsonNode v = obj.get(name);
            if (v == null || v.isNull()) {
                continue;
            }
            Integer n = integerOf(v);
            if (n != null) {
                return n;
            }
        }
        return null;
    }

    /**
     * 取整数值。
     *
     * <p>只接受整数（含 {@code 120.0} 这类整数写法）。归一化坐标（0~1 的小数）在这里<b>必须被拒</b>：
     * 送模型前本项目没有做过缩放，缺了 {@code scale_ratio} 就无法把归一化值还原成像素
     * （docs/00 §3.3 约束 1 要求坐标基准与送模型图片一致），四舍五入只会得到一堆挤在左上角的错框。
     */
    private static Integer integerOf(JsonNode v) {
        if (v.isIntegralNumber()) {
            return v.asInt();
        }
        Double d = doubleOf(v);
        if (d == null) {
            return null;
        }
        return d == Math.rint(d) ? d.intValue() : null;
    }

    /** 数值或"装着数字的字符串"都接受；解析不出返回 null，而不是当成 0 */
    private static Double doubleOf(JsonNode v) {
        if (v.isNumber()) {
            return v.asDouble();
        }
        if (v.isTextual()) {
            String s = v.asText().strip();
            if (s.isEmpty()) {
                return null;
            }
            try {
                return Double.valueOf(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** 截断仅用于错误信息：限长是为了不让物料内容大段进日志（AGENTS.md 第 12 条） */
    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
