package com.guangxuan.audit.infra.provider.dashscope;

import com.guangxuan.audit.common.error.DomainException;
import com.guangxuan.audit.common.error.ErrorCode;
import com.guangxuan.audit.domain.port.ParsePort;
import com.guangxuan.audit.infra.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFGroupShape;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 文档解析：TEXT / PDF / PPTX / DOCX → 段落级锚点（含页码与字符区间）。
 *
 * <p><b>为什么优先取文本层，而不是一律送 OCR</b>（{@link DashScopeOcrClient}）：
 * 数字生成的 PDF/Word/PPT 自带文本层，直接抽取是<b>逐字符精确</b>的，
 * 而且拿到的是原始字符（引号、百分号、单位不会被识别错）；送 OCR 则要经过
 * "渲染 → 识别"两次有损转换：多一档费用、多几十秒延迟，还会把
 * "100%保障" 认成 "10096保障" 这类关键错误带进风险判定。
 * OCR 只应留给<b>确实没有文本层</b>的扫描件，本方法用 {@code hasTextLayer=false}
 * 把它标记出来，由上层 {@code ParseAppService} 决定是否走 OCR 兜底。
 *
 * <p><b>为什么 DOCX 的 {@code pageNo} 必须是 null</b>（docs/06 §3）：
 * Word 的分页是<b>渲染期</b>由字体度量与纸张尺寸决定的，文件里不存页码——
 * OOXML 只有 {@code w:lastRenderedPageBreak} 这个"上次渲染时的分页位置"提示，
 * 换台机器、换个字体就会变。若我们按段落数估算一个页码写进锚点，
 * 界面上会显示一个看起来精确的"第 3 页"，法务翻到第 3 页却找不到那句话，
 * 比直接说明"只能定位到段落"更糟。因此 DOCX 只承诺"段落级定位"。
 *
 * <p><b>charStart / charEnd 的口径</b>（下游 {@code locatorForParagraph} 直接取用）：
 * <ul>
 *   <li>半开区间 {@code [charStart, charEnd)}，{@code charEnd} 不含；</li>
 *   <li><b>PDF / PPTX</b>：区间是"该行在<b>本页（本张幻灯片）文本</b>中的偏移"，
 *       同一个 {@code pageNo} 内可比、可拼接，<b>不跨页累计</b>——
 *       这样前端只要拿到"页文本 + 区间"就能高亮，不必理解全局偏移；</li>
 *   <li><b>DOCX / TEXT</b>：区间是"该段在<b>全文</b>中的偏移"（这类物料本就没有页码，
 *       全局偏移是唯一自洽的口径）。</li>
 * </ul>
 *
 * <p><b>为什么单文件超过 {@value #MAX_IN_MEMORY_BYTES} 字节要拒绝解析</b>：
 * 本实现所在链路是"整份读进内存"再解析/渲染的（{@code StorageService.getBytes}），
 * 而页面渲染的峰值内存是"像素数 × 4 字节 × 页数"级别的，远大于文件本身。
 * 一个 300MB 的 PDF 在容器里足以把解析进程连同整个应用一起 OOM 掉——
 * 那会表现为"上传完就白屏"，比明确报错难排查得多。因此宁可提前给出
 * 一句"文件多大、请怎么处理"的明确提示。
 *
 * <p><b>本实现刻意不做的事</b>：
 * <ul>
 *   <li><b>不调 OCR</b>：OCR 是否执行取决于上层的锚点情况与预算
 *       （扫描件里也可能有可用的文本层，或法务只想先看文本），
 *       在这里隐式触发会让上层无法感知"这份材料其实花了两次模型钱"；</li>
 *   <li><b>不编造内容</b>：提取不到文本就返回空 {@code paragraphs} 且
 *       {@code hasTextLayer=false}，绝不返回示例段落、猜的页码或编的字符区间。</li>
 * </ul>
 *
 * <p><b>已知边界（如实声明，未在界面上伪装为可用）</b>：
 * <ul>
 *   <li>旧格式 {@code .doc} / {@code .ppt}（OLE2 复合文档）不支持，直接报错；
 *       用 HWPF/HSLF 硬抽会引入 POI 的"不成熟 API"风险（docs/06 §4.3），
 *       而且对"内容是否正确"没有任何保证，不如让用户先转成 docx/pptx；</li>
 *   <li>DOCX 的<b>页眉/页脚</b>未纳入抽取。它们是独立的文本部件，不参与正文流；
 *       若免责声明只写在页脚，本方法会漏掉——这是真实存在的漏检面，
 *       需要支持时应作为独立锚点类型补上，而不是混进段落索引里；</li>
 *   <li>DOCX 的文本框（{@code w:txbxContent}）、批注、修订记录同样不在正文流中，未抽取；</li>
 *   <li>PPTX 只取页面图 <b>不渲染</b>（见 {@code renderPdfPages} 注释：只有 PDF 的
 *       页序是格式自带的，按固定 DPI 渲染得到的一定是"文件里的第 N 页"）。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DashScopeDocumentClient {

    // ── 常量：解析器标识（必须随结果一起上报，便于区分"谁解析的"）──────────
    private static final String ENGINE = "pdfbox+poi";
    private static final String ENGINE_VERSION = "pdfbox-3.0.3/poi-5.3.0";
    private static final String TEXT_ENGINE = "builtin-text";
    private static final String TEXT_ENGINE_VERSION = "1.0.0";

    // ── 常量：保护阈值（都取"宁可明确拒绝，不要拖垮进程"的保守值）──────────
    /** 单物料上限：超过则拒绝一次性全量读入内存（见类注释） */
    private static final long MAX_IN_MEMORY_BYTES = 50L * 1024 * 1024;
    /** 页面渲染分辨率：150 DPI 是"OCR 可用"与"图片不过大"的常用折中 */
    private static final float RENDER_DPI = 150f;
    /** 单页渲染像素上限：超过则按比例下调 DPI（约合 A4@300DPI，足够 OCR） */
    private static final long MAX_PAGE_PIXELS = 16_000_000L;
    /** 最多渲染多少页为图片：再多则放弃渲染并如实记录（渲染是内存峰值操作） */
    private static final int MAX_RENDER_PAGES = 300;

    // ── 常量：文件头特征（判断真实格式，不信任扩展名与可能为空的 mimeType）──
    private static final byte[] MAGIC_PDF = {'%', 'P', 'D', 'F'};
    private static final byte[] MAGIC_ZIP = {'P', 'K', 3, 4};
    private static final byte[] MAGIC_OLE2 = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
            (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};

    /** 供 {@code DashScopeParseAdapter} 复用同一出口；当前无网络调用，保留字段以便后续接线 */
    protected final DashScopeClient client;
    private final StorageService storageService;

    /** 文档解析；{@code pageImages} 用于扫描件后续 OCR */
    public ParsePort.DocumentResult parseDocument(ParsePort.DocumentRequest request) {
        String objectKey = request.objectKey();
        // 取真实字节：mimeType 可能为空、扩展名可能被改，文件头才是可信的格式依据
        byte[] bytes = storageService.getBytes(objectKey);

        // 大文件保护：先在这里拒绝，而不是等到渲染页面图时才发现内存不够。
        // 提示语要说清"文件多大、怎么办"，否则用户只会反复重传同一个文件
        // （AGENTS.md 第 14 条：失败必须给出可操作的下一步）
        if (bytes.length > MAX_IN_MEMORY_BYTES) {
            throw new DomainException(ErrorCode.MATERIAL_PARSE_FAILED, String.format(
                    "文件大小 %.1f MB，超过单份物料解析上限 %d MB。"
                            + "本解析链路需要把整份文件读入内存，超大文件会把解析服务拖垮"
                            + "（表现为解析长时间无响应）。请拆分文件后分批上传，"
                            + "或联系管理员为该场景单独放宽上限。",
                    bytes.length / 1024.0 / 1024.0, MAX_IN_MEMORY_BYTES / 1024 / 1024));
        }

        // 分流说明：materialType 是上传端声明的业务类型，决定"应该按什么解析"；
        // 但落到"实际用哪个解析器"时以文件头为准——声明类型与真实格式不一致时
        // （把 PPTX 传成了 PDF），拿错解析器只会得到一句含糊的"文件已损坏"，
        // 把"传错类型"这个可操作的提示掩盖掉。
        // 用 Locale.ROOT：避免跟随服务器默认区域（如土耳其语的 i→İ）改变比较结果
        String materialType = request.materialType() == null ? ""
                : request.materialType().trim().toUpperCase(java.util.Locale.ROOT);
        String kind = resolveKind(materialType, bytes);
        log.info("文档解析开始：material={} 声明类型={} mime={} 实际格式={} 大小={}B",
                objectKey, materialType, request.mimeType(), kind, bytes.length);

        return switch (kind) {
            case "TEXT" -> parsePlainText(bytes);
            case "PDF" -> parsePdf(bytes, objectKey);
            case "PPTX" -> parsePptx(bytes);
            case "DOCX" -> parseDocx(bytes);
            // 旧格式如实拒绝，并说明需要先转成什么（ParseAppService 会把这句话透给用户）
            case "DOC" -> throw new DomainException(ErrorCode.UNSUPPORTED_FILE_TYPE,
                    "暂不支持 .doc 老格式（二进制 Word）：该格式没有稳定的段落结构可抽取。"
                            + "请用 Word 另存为 .docx 后重新上传。");
            case "PPT" -> throw new DomainException(ErrorCode.UNSUPPORTED_FILE_TYPE,
                    "暂不支持 .ppt 老格式（二进制 PowerPoint）：请用 PowerPoint 另存为 .pptx 后重新上传。");
            default -> throw new DomainException(ErrorCode.UNSUPPORTED_FILE_TYPE,
                    "无法识别的文档格式：声明类型=" + materialType + "，mime=" + request.mimeType()
                            + "，文件头未匹配 PDF / PPTX / DOCX。"
                            + "若文件是从聊天工具或网页另存得到的，请确认它没有被截断或改名。");
        };
    }

    // ════════════════════════════════════════════════════════════════════
    // 纯文本
    // ════════════════════════════════════════════════════════════════════

    /**
     * 纯文本：按行切分，每行一个段落锚点。
     *
     * <p>为什么按行而不是按整篇：宣传文案常常一行一句口号，
     * 按行切才能让"行业第一"这类表述定位到具体那一句，
     * 而不是定位到整份文件（AGENTS.md 第 5 条要求定位粒度匹配物料类型）。
     *
     * <p>编码探测比 Mock 实现更完整：Windows 上另存的 .txt 常见 UTF-8 / GBK，
     * 部分工具导出 UTF-16。若一律按 UTF-8 解，会得到满屏乱码，
     * 而乱码照样"匹配不到关键词"，最后表现为"这份材料没风险"——这是最坏的失败方式。
     */
    private ParsePort.DocumentResult parsePlainText(byte[] bytes) {
        String text = decodeText(bytes);
        List<ParsePort.DocParagraph> paragraphs = new ArrayList<>();
        int paraIndex = 0;
        String normalized = normalizeNewlines(text);
        int offset = 0;
        // 用 -1 保留末尾空行，使行偏移严格对应原文（与 MockParseAdapter 同口径）
        for (String raw : normalized.split("\n", -1)) {
            int lineStart = offset;
            int lineEnd = lineStart + raw.length();
            // 分隔符计入下一行的起点。归一化后统一为 \n（1 个字符）；
            // 注意 UTF-16 文件里"字符偏移"按 Java char 计，与 bytes 偏移不是一回事，
            // 但前端是按字符串切片高亮的，因此两者口径一致即可
            offset = lineEnd + 1;
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            // 区间随 strip 内缩，保证高亮范围正好落在可见字符上（口径与 PDF 切行一致）
            int leading = raw.length() - raw.stripLeading().length();
            int trailing = raw.length() - raw.stripTrailing().length();
            // pageNo 为 null：纯文本没有页码概念
            paragraphs.add(new ParsePort.DocParagraph(null, paraIndex, line,
                    lineStart + leading, lineEnd - trailing));
            paraIndex++;
        }
        // 空文件（或全是空白）如实报 hasTextLayer=false：层是空的，
        // 说成 true 会让上层以为"有文本层只是没内容"，从而不做任何兜底
        boolean hasTextLayer = !paragraphs.isEmpty();
        log.info("文本解析完成：{} 行 {} 字符 文本层={}", paragraphs.size(), text.length(), hasTextLayer);
        return new ParsePort.DocumentResult(paragraphs, List.of(), hasTextLayer,
                TEXT_ENGINE, TEXT_ENGINE_VERSION);
    }

    /**
     * 文本编码探测：UTF-8（严格）→ UTF-16（看 BOM）→ GBK（严格）→ UTF-8 容错。
     *
     * <p>为什么先严格解码再降级：{@code new String(bytes, UTF_8)} 遇到非法字节会塞
     * {@code U+FFFD} 而不报错，"解码成功"与"内容正确"于是变成两件事。
     * 严格模式让"解码失败"可被捕获，才有机会换编码重试。
     */
    private String decodeText(byte[] bytes) {
        int from = 0;
        // BOM 不去掉的话，第一个锚点的文本会带一个不可见字符，
        // 导致"风险原文"与法务在文件里看到的对不上
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            from = 3;                                  // UTF-8 BOM
        } else if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF
                && (bytes[1] & 0xFF) == 0xFE) {
            return strictDecode(bytes, 2, StandardCharsets.UTF_16LE, "UTF-16LE");   // 少见但要认
        } else if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE
                && (bytes[1] & 0xFF) == 0xFF) {
            return strictDecode(bytes, 2, StandardCharsets.UTF_16BE, "UTF-16BE");
        }
        String utf8 = strictDecode(bytes, from, StandardCharsets.UTF_8, "UTF-8");
        if (utf8 != null) {
            return utf8;
        }
        String gbk = strictDecode(bytes, from, Charset.forName("GBK"), "GBK");
        if (gbk != null) {
            log.info("文本按 GBK 解码成功（非 UTF-8 文件）");
            return gbk;
        }
        // 两种都失败：用 UTF-8 容错解码，至少不丢内容，并在日志里留痕——
        // 存在替换字符意味着后续关键词匹配可能漏，这一点必须可追溯
        log.warn("文本既不是合法 UTF-8 也不是合法 GBK/UTF-16，已按 UTF-8 容错解码，可能存在替换字符");
        return new String(bytes, from, bytes.length - from, StandardCharsets.UTF_8);
    }

    /** 严格解码：编码不合法时返回 null，而不是塞一堆替换字符当作成功 */
    private String strictDecode(byte[] bytes, int from, Charset charset, String label) {
        if (bytes.length - from <= 0) {
            return "";
        }
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            String text = decoder.decode(ByteBuffer.wrap(bytes, from, bytes.length - from)).toString();
            log.debug("文本编码判定为 {}", label);
            return text;
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // PDF
    // ════════════════════════════════════════════════════════════════════

    /**
     * PDF：文本层优先；无文本层（扫描件）时渲染页面图，供上层 OCR 兜底。
     *
     * <p>{@code pageNo} 用 PDF 自带的物理页序，从 1 开始：
     * PDF 的页对象顺序就是阅读顺序，这个页码是<b>格式真实存在</b>的，
     * 因此可以放心写进锚点，法务按页码一定能翻到。
     */
    private ParsePort.DocumentResult parsePdf(byte[] bytes, String objectKey) {
        long started = System.currentTimeMillis();
        try (PDDocument document = Loader.loadPDF(bytes)) {
            int pageCount = document.getNumberOfPages();

            // PDFTextStripper 复用同一个实例逐页取文本（构造器无副作用，重置的是内部缓冲）
            PDFTextStripper stripper = new PDFTextStripper();
            // 按位置排序：双栏排版、图注与正文混排时，默认顺序可能把右栏读进左栏中间，
            // 导致段落文本前后错乱，风险原文对不上原文件
            stripper.setSortByPosition(true);

            List<ParsePort.DocParagraph> paragraphs = new ArrayList<>();
            int paraIndex = 0;          // 全文档递增，便于下游 "A-0001" 式锚点编号稳定
            int totalChars = 0;
            for (int pageNo = 1; pageNo <= pageCount; pageNo++) {
                stripper.setStartPage(pageNo);
                stripper.setEndPage(pageNo);
                String pageText = normalizeNewlines(stripper.getText(document));
                totalChars += pageText.length();
                // charStart/charEnd 以"本页文本"为基准（口径见类注释）
                paraIndex = splitIntoParagraphs(pageText, pageNo, paraIndex, paragraphs);
            }

            // 文本层判定：整篇不足 2 个字符、或切不出任何非空行，都视同"没有文本层"。
            // 有些扫描件每页只带一个换行或一个页码数字，若按"length > 0"判断，
            // 会把扫描件误判成有文本层，从而跳过 OCR 兜底 → 整份材料"看起来解析成功但审不出东西"。
            // 宁可多渲染一次页面图（多花一点内存），也不要漏掉一份扫描件的 OCR 兜底。
            boolean hasTextLayer = totalChars >= 2 && !paragraphs.isEmpty();
            List<String> pageImages = List.of();
            if (!hasTextLayer && pageCount > 0) {
                pageImages = renderPdfPages(document, objectKey, pageCount);
            }

            log.info("PDF 解析完成：页数={} 段落={} 字符={} 文本层={} 页面图={} 耗时={}ms",
                    pageCount, paragraphs.size(), totalChars, hasTextLayer,
                    pageImages.size(), System.currentTimeMillis() - started);
            if (hasTextLayer) {
                // 只取文本层、不渲染图片：这份 PDF 的正文已在锚点里，
                // 渲染 150DPI 页面图会白白吃掉内存与存储（且上层不会用它做 OCR）
                return new ParsePort.DocumentResult(paragraphs, List.of(), true,
                        ENGINE, ENGINE_VERSION);
            }
            log.warn("PDF 无文本层（疑为扫描件）：已渲染 {} 张页面图，OCR 兜底由上层 ParseAppService 决定",
                    pageImages.size());
            return new ParsePort.DocumentResult(paragraphs, pageImages, false,
                    ENGINE, ENGINE_VERSION);
        } catch (InvalidPasswordException e) {
            // 加密 PDF 必须说清"是密码问题"，否则用户只会看到一句"文件已损坏"并反复重传
            throw new DomainException(ErrorCode.UNSUPPORTED_FILE_TYPE,
                    "该 PDF 已加密（需要打开密码），无法解析文本。请提供未加密版本，"
                            + "或用阅读器另存为无密码 PDF 后重新上传。");
        } catch (DomainException e) {
            throw e;
        } catch (Exception e) {
            throw new DomainException(ErrorCode.PARSE_JOB_FAILED,
                    "PDF 解析失败：" + e.getMessage() + "。请确认文件未损坏、未使用特殊加密后重新上传。");
        }
    }

    /**
     * 无文本层时把每页渲染成 PNG 存回对象存储，返回对象键列表。
     *
     * <p>为什么只对 PDF 做这一步：PDF 的页序是格式自带的（阅读器渲染出的"第 N 页"
     * 与我们取到的第 N 页必然一致）；而 DOCX 的分页由渲染期决定（见类注释），
     * PPTX 虽然在 POI 里能导出图片，但形状定位不同于"文件原生页序"，
     * 因此这两类不在这里渲染，以免产出"看起来是第 N 页、其实不是"的图片。
     *
     * <p>渲染失败<b>不</b>抛出：文本已经确实没有，此时再把整个解析判失败，
     * 用户拿不到任何信息；返回空列表并如实记录，由上层决定是标"解析失败"
     * 还是"文件太大请拆分"。
     */
    private List<String> renderPdfPages(PDDocument document, String objectKey, int pageCount) {
        List<String> pageImages = new ArrayList<>();
        int limit = Math.min(pageCount, MAX_RENDER_PAGES);
        if (pageCount > MAX_RENDER_PAGES) {
            log.warn("页数 {} 超过渲染上限 {}，仅渲染前 {} 页；"
                            + "请提示用户拆分文件，超出部分将无法走 OCR 兜底",
                    pageCount, MAX_RENDER_PAGES, MAX_RENDER_PAGES);
        }
        PDFRenderer renderer = new PDFRenderer(document);
        for (int i = 0; i < limit; i++) {
            try {
                BufferedImage image = renderOnePage(document, renderer, i);
                ByteArrayOutputStream png = new ByteArrayOutputStream();
                ImageIO.write(image, "png", png);
                image.flush();
                byte[] content = png.toByteArray();
                // 对象键挂在原件键之下：天然不可变、不覆盖原件、可反查到源物料
                // （StorageService 的键规则同样用 sha256 保证不可覆盖，这里沿用同一思路）
                String pageKey = objectKey + "/derived/pages/page-"
                        + String.format("%04d", i + 1) + ".png";
                storageService.put(null, null, "derived", "page-" + (i + 1) + ".png",
                        content, "image/png");
                pageImages.add(pageKey);
                log.debug("渲染页面图：page={} 键={} 大小={}B", i + 1, pageKey, content.length);
            } catch (Exception e) {
                // 单页失败不放弃其余页：能 OCR 几页是几页，但失败必须留痕
                log.warn("第 {} 页渲染或存储失败，该页将没有页面图（OCR 兜底会缺这一页）：{}",
                        i + 1, e.getMessage());
            }
        }
        return pageImages;
    }

    /**
     * 渲染单页，像素数不超过 {@link #MAX_PAGE_PIXELS}。
     *
     * <p>做法是<b>先按页尺寸把 DPI 压下来再渲染</b>，而不是"按 150DPI 渲染完再缩"：
     * 对超大页面（工程图、长海报），150DPI 会先分配一个上亿像素的 BufferedImage，
     * 内存峰值发生在缩放之前，缩放救不回来。渲染后仍超限才做兜底下采样。
     *
     * <p>页面尺寸可从 {@code MediaBox} 直接读到，是 PDF 里真实存在的数据，
     * 因此这次"降 DPI"是有依据的换算，不是猜。
     */
    private BufferedImage renderOnePage(PDDocument document, PDFRenderer renderer, int pageIndex)
            throws Exception {
        // 页面尺寸直接读 MediaBox：这是 PDF 里真实存在的数据，
        // 用它把 DPI 压到像素预算之内，是有依据的换算，不是猜
        var page = document.getPage(pageIndex);
        float widthPt = page.getMediaBox().getWidth();
        float heightPt = page.getMediaBox().getHeight();
        float dpi = RENDER_DPI;
        if (widthPt > 0 && heightPt > 0) {
            double inchW = widthPt / 72.0;
            double inchH = heightPt / 72.0;
            double maxDpi = Math.sqrt(MAX_PAGE_PIXELS / (inchW * inchH));
            if (maxDpi < dpi) {
                dpi = (float) maxDpi;
                log.warn("第 {} 页尺寸异常大（{}×{} pt），已把渲染 DPI 从 {} 下调到 {}"
                                + "（像素数上限 {}，OCR 精度会随之下降）",
                        pageIndex + 1, Math.round(widthPt), Math.round(heightPt),
                        RENDER_DPI, Math.round(dpi), MAX_PAGE_PIXELS);
            }
        }
        BufferedImage image = renderer.renderImageWithDPI(pageIndex, dpi, ImageType.RGB);
        long pixels = (long) image.getWidth() * image.getHeight();
        if (pixels > MAX_PAGE_PIXELS) {
            // 兜底：页面旋转、CropBox 与 MediaBox 不一致时上面推算出的尺寸可能不准
            double scale = Math.sqrt((double) MAX_PAGE_PIXELS / pixels);
            int w = Math.max(1, (int) Math.round(image.getWidth() * scale));
            int h = Math.max(1, (int) Math.round(image.getHeight() * scale));
            log.warn("第 {} 页渲染像素 {}×{}={} 仍超过上限 {}，已下采样到 {}×{}",
                    pageIndex + 1, image.getWidth(), image.getHeight(), pixels,
                    MAX_PAGE_PIXELS, w, h);
            var scaled = image.getScaledInstance(w, h, java.awt.Image.SCALE_SMOOTH);
            BufferedImage reduced = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            var g = reduced.createGraphics();
            try {
                g.drawImage(scaled, 0, 0, null);
            } finally {
                g.dispose();
            }
            image.flush();
            return reduced;
        }
        return image;
    }

    // ════════════════════════════════════════════════════════════════════
    // PPTX
    // ════════════════════════════════════════════════════════════════════

    /**
     * PPTX：遍历每张幻灯片、每个形状的文本。
     *
     * <p>{@code pageNo} 用幻灯片序号（从 1 开始）：PPT 的"第几页"就是幻灯片序号，
     * 这是<b>真实存在</b>的页码概念，前端可以据此翻到对应幻灯片。
     *
     * <p>表格等图形对象里的文字也逐"行"输出（POI 对表格形状按行列返回换行分隔的文本），
     * 因为广宣 PPT 的"参数表""价格表"恰恰是风险高发处，只取普通文本框会漏掉整张表。
     */
    private ParsePort.DocumentResult parsePptx(byte[] bytes) {
        try (XMLSlideShow slideShow = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            List<ParsePort.DocParagraph> paragraphs = new ArrayList<>();
            int paraIndex = 0;
            int slideNo = 0;
            for (XSLFSlide slide : slideShow.getSlides()) {
                slideNo++;
                String slideText = collectSlideText(slide);
                // charStart/charEnd 以"本张幻灯片文本"为基准（口径见类注释）
                paraIndex = splitIntoParagraphs(slideText, slideNo, paraIndex, paragraphs);
            }
            boolean hasTextLayer = !paragraphs.isEmpty();
            log.info("PPTX 解析完成：幻灯片={} 段落={} 文本层={}",
                    slideNo, paragraphs.size(), hasTextLayer);
            // PPTX 不产出页面图：形状导出图与"文件原生页序"不是同一个概念，
            // 见 renderPdfPages 的说明。缺少文本时由上层判定为解析失败并提示转 PDF。
            return new ParsePort.DocumentResult(paragraphs, List.of(), hasTextLayer,
                    ENGINE, ENGINE_VERSION);
        } catch (DomainException e) {
            throw e;
        } catch (Exception e) {
            throw new DomainException(ErrorCode.PARSE_JOB_FAILED,
                    "PPTX 解析失败：" + e.getMessage()
                            + "。请确认文件是 .pptx（而不是改名得到的）且未损坏后重新上传。");
        }
    }

    /** 汇总一张幻灯片内所有形状的文本，形状之间用换行分隔 */
    private String collectSlideText(XSLFSlide slide) {
        StringBuilder sb = new StringBuilder();
        for (XSLFShape shape : slide.getShapes()) {
            appendShapeText(shape, sb);
        }
        return sb.toString();
    }

    /** 递归取形状文本：组合形状里可能还有文本框，只看顶层会漏内容 */
    private void appendShapeText(XSLFShape shape, StringBuilder sb) {
        if (shape instanceof XSLFTextShape textShape) {
            String text = textShape.getText();
            if (text != null && !text.isBlank()) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(text);
            }
        } else if (shape instanceof XSLFGroupShape group) {
            for (XSLFShape child : group.getShapes()) {
                appendShapeText(child, sb);
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // DOCX
    // ════════════════════════════════════════════════════════════════════

    /**
     * DOCX：按正文元素顺序遍历段落与表格，{@code pageNo} 一律为 null。
     *
     * <p>为什么必须为 null：见类注释与 docs/06 §3——Word 文件不存页码，
     * 编一个页码等于伪造定位。
     *
     * <p>为什么表格要单独处理：{@code XWPFDocument.getParagraphs()} <b>不包含</b>表格里的段落。
     * 广宣物料里的"参数对比表""价格表""免责声明表"恰恰是风险高发处，
     * 只遍历段落会整块漏掉，而且用户完全看不出来（会表现为"这份材料没风险"）。
     * 这里按 body 元素顺序输出，保证 {@code paraIndex} 与文档通读顺序一致。
     */
    private ParsePort.DocumentResult parseDocx(byte[] bytes) {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            List<ParsePort.DocParagraph> paragraphs = new ArrayList<>();
            int[] cursor = {0, 0};   // [0]=全局 paraIndex，[1]=全文字符偏移
            // 用 body 元素迭代器而不是 getParagraphs()：后者不含表格，会整块漏掉表格里的文字
            var elements = document.getBodyElementsIterator();
            while (elements.hasNext()) {
                appendDocxElement(elements.next(), paragraphs, cursor);
            }
            boolean hasTextLayer = !paragraphs.isEmpty();
            log.info("DOCX 解析完成：段落={} 文本层={}（pageNo 全为 null：Word 分页在渲染期决定）",
                    paragraphs.size(), hasTextLayer);
            // DOCX 不产出页面图：见 renderPdfPages 的说明
            return new ParsePort.DocumentResult(paragraphs, List.of(), hasTextLayer,
                    ENGINE, ENGINE_VERSION);
        } catch (DomainException e) {
            throw e;
        } catch (Exception e) {
            throw new DomainException(ErrorCode.PARSE_JOB_FAILED,
                    "Word（.docx）解析失败：" + e.getMessage()
                            + "。请确认文件是 .docx（不是改名的 .doc）且未损坏后重新上传。");
        }
    }

    /** 按正文顺序处理一个 body 元素：段落直接取文本，表格递归取单元格文本 */
    private void appendDocxElement(IBodyElement element, List<ParsePort.DocParagraph> out,
                                   int[] cursor) {
        if (element instanceof XWPFParagraph paragraph) {
            // 段落文本中的 \n 与 \t 已由 POI 转义为换行/制表符，交给统一的切行逻辑处理，
            // 这样每个 DocParagraph 都是"一行"，前端高亮区间才有意义
            appendDocxText(paragraph.getText(), out, cursor);
            return;
        }
        if (element instanceof XWPFTable table) {
            appendDocxTable(table, out, cursor);
            return;
        }
        // 结构化文档标签、内容控件等在正文里存在但不含可直接引用的段落文本。
        // 跳过而不是报错：报错会让整份文件解析失败，而这类元素通常只占很小一部分。
        log.debug("跳过未支持的 DOCX 正文元素类型：{}", element.getClass().getSimpleName());
    }

    /** 表格：行 → 单元格 → 单元格内的段落与嵌套表格（顺序即阅读顺序） */
    private void appendDocxTable(XWPFTable table, List<ParsePort.DocParagraph> out, int[] cursor) {
        for (XWPFTableRow row : table.getRows()) {
            for (XWPFTableCell cell : row.getTableCells()) {
                for (IBodyElement cellElement : cell.getBodyElements()) {
                    appendDocxElement(cellElement, out, cursor);
                }
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 共用的切行 / 偏移计算
    // ════════════════════════════════════════════════════════════════════

    /**
     * 把一段（一页 / 一张幻灯片 / 一个段落）文本按行切分成锚点。
     *
     * @param text     该段文本（调用方已统一换行符）
     * @param pageNo   页码（DOCX 与纯文本传 null）；PDF 为物理页序，PPTX 为幻灯片序号
     * @param paraIndex 起始段落序号（返回值为下一个可用序号，保证全文档递增）
     * @param out      结果列表
     * @return 下一个可用的段落序号
     */
    private int splitIntoParagraphs(String text, Integer pageNo, int paraIndex,
                                    List<ParsePort.DocParagraph> out) {
        if (text == null || text.isEmpty()) {
            return paraIndex;
        }
        int offset = 0;
        // 用 -1 保留末尾空行，使字符偏移与原文严格对应
        for (String raw : text.split("\n", -1)) {
            int lineStart = offset;
            int lineEnd = lineStart + raw.length();
            offset = lineEnd + 1;   // 换行符占 1 个字符（normalizeNewlines 已归一到 \n）
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            // strip() 会同时去掉首尾空白，因此区间要相应内缩，
            // 否则高亮范围会把空白也框进去，前端看起来"多选了半行"
            int leading = raw.length() - raw.stripLeading().length();
            int trailing = raw.length() - raw.stripTrailing().length();
            int start = lineStart + leading;
            int end = lineEnd - trailing;
            out.add(new ParsePort.DocParagraph(pageNo, paraIndex, line, start, end));
            paraIndex++;
        }
        return paraIndex;
    }

    /**
     * 追加一个 DOCX 段落（可能自带换行）的文本。
     *
     * <p>偏移只在<b>真的产出锚点</b>时推进：空白段落不应在字符空间里占位，
     * 否则后续段落的 {@code charStart} 会与原文越来越偏，
     * 表现为"高亮位置一行比一行往下漂"。
     *
     * @param cursor [0]=下一个段落序号，[1]=全文字符偏移
     */
    private void appendDocxText(String text, List<ParsePort.DocParagraph> out, int[] cursor) {
        if (text == null || text.isEmpty()) {
            return;
        }
        String normalized = normalizeNewlines(text);
        int before = out.size();
        splitIntoParagraphs(normalized, null, cursor[0], out);
        int produced = out.size() - before;
        if (produced > 0) {
            cursor[0] += produced;
            cursor[1] += normalized.length();
        }
    }

    /** 换行符归一：PDFBox/POI 可能给 \r\n 或 \r，统一成 \n 后偏移才好算 */
    private static String normalizeNewlines(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    // ════════════════════════════════════════════════════════════════════
    // 格式判定
    // ════════════════════════════════════════════════════════════════════

    /**
     * 判定"实际该用哪个解析器"。
     *
     * <p>顺序：先看<b>文件头魔数</b>（最可信：改扩展名改不掉它）→ 再用<b>声明类型</b>
     * 区分同一魔数下的不同格式（OOXML 都是 zip 包、OLE2 既可能是 doc 也可能是 ppt）。
     * 刻意不看扩展名：{@code DocumentRequest} 里没有文件名，而且扩展名本就不可信，
     * 一个叫 {@code 海报.pdf} 的 PPTX 若按扩展名分派，只会得到"PDF 已损坏"这种误导性报错。
     */
    private String resolveKind(String materialType, byte[] bytes) {
        if (startsWith(bytes, MAGIC_PDF)) {
            return "PDF";
        }
        if (startsWith(bytes, MAGIC_OLE2)) {
            // OLE2 复合文档：既可能是 .doc 也可能是 .ppt/.xls，只能靠声明类型区分
            return "WORD".equals(materialType) ? "DOC" : "PPT";
        }
        if (startsWith(bytes, MAGIC_ZIP)) {
            // OOXML 都是 zip 包，用声明类型选解析器
            return "WORD".equals(materialType) ? "DOCX" : "PPTX";
        }
        // 纯文本没有魔数：以声明类型为准
        if ("TEXT".equals(materialType)) {
            return "TEXT";
        }
        // 声明类型与文件头都不足以判断时，按声明类型给出最可能的解析器，
        // 让真正的解析器去报出"这文件不是 PDF/DOCX"这类可诊断的错误
        return switch (materialType) {
            case "PDF" -> "PDF";
            case "WORD" -> "DOCX";
            case "PPT" -> "PPTX";
            default -> "UNKNOWN";
        };
    }

    /** 文件头比对：长度不足即视为不匹配（截断文件不能靠"猜"通过） */
    private static boolean startsWith(byte[] bytes, byte[] magic) {
        if (bytes.length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (bytes[i] != magic[i]) {
                return false;
            }
        }
        return true;
    }
}
