package com.guangxuan.audit.domain.port;

import java.util.List;

/**
 * 解析端口：把任意格式物料转成"统一可审核内容 + 可定位的证据锚点"。
 *
 * <p><b>为什么必须是接口</b>（docs/00 ADR D-14）：解析能力的部署形态存在分叉——
 * <ul>
 *   <li>路线 A：调用云端百炼（OCR / ASR / 视觉理解），交付快、无需 GPU，
 *       但物料原件必须出内网（待确认项 A1）；</li>
 *   <li>路线 B：本地部署 PaddleOCR + FunASR，原件不出内网，但需 Python 服务与算力。</li>
 * </ul>
 * 两条路线产出的锚点结构必须完全一致，下游逻辑才不需要区分路线。
 *
 * <p><b>硬约束</b>：本接口的方法签名不得出现任何供应商专有类型
 * （如 DashScope 的 TranscriptionResult），否则切换路线时要改业务代码。
 */
public interface ParsePort {

    /**
     * 图片/视频帧文字识别（含坐标）。
     *
     * <p>坐标是"图片风险框选"的基础能力。注意实现必须保证 OCR 与送视觉模型的
     * 是<b>同一张、同尺寸</b>图片并记录 {@code scaleRatio}，否则坐标必然错位
     * （docs/00 §3.3 约束 1）。
     */
    OcrResult ocr(OcrRequest request);

    /**
     * 语音识别（含句级/字级时间戳）。
     *
     * <p>时间戳是"视频风险定位到起止时间"的基础能力。
     * ⚠️ 已知陷阱：千问的同步接口（含 OpenAI 兼容方式）<b>不返回时间戳</b>，
     * 必须走 Filetrans 异步接口；且任务级 {@code end_time} 是日期字符串，
     * 与音频内毫秒时间戳同名不同义，实现时必须用不同类型承载。
     */
    AsrResult asr(AsrRequest request);

    /** 文档解析（PDF / PPTX / DOCX），产出页码/段落/句子级锚点 */
    DocumentResult parseDocument(DocumentRequest request);

    /**
     * 画面语义理解。
     *
     * <p>OCR 只能拿到文字，而广宣风险里有相当一部分<b>不在文字里</b>：
     * 未标注依据的对比图、暗示疗效的画面、把竞品拍得很差的镜头。
     * 这类问题必须靠视觉理解，否则"没识别到文字"会被误读成"没有风险"。
     *
     * <p>实现要注意：送给视觉模型的图片必须与送 OCR 的<b>同一张、同尺寸</b>，
     * 否则画面类风险与文字类风险的定位会互相错位（docs/00 §3.3 约束 1）。
     */
    VisionResult vision(VisionRequest request);

    // ── 请求 / 响应 DTO ──────────────────────────────────────────────────

    /** @param objectKey MinIO 对象键；实现自行取件，不传本机路径 */
    record OcrRequest(String objectKey, String mimeType, String languageHint) {
    }

    /** @param question 针对画面的具体问题；为空时做通用描述 */
    record VisionRequest(String objectKey, String mimeType, String question) {
    }

    /**
     * @param description 画面整体描述
     * @param findings    逐条观察到的、可能与广宣合规相关的画面要素
     */
    record VisionResult(String description, List<String> findings,
                        String engine, String engineVersion) {
    }

    /**
     * @param lines     识别出的文字行
     * @param localized 是否给出了文字坐标。为 false 时表示该引擎只返回文本、
     *                  不返回位置（qwen-vl-ocr 的通用文字识别属于这种），
     *                  此时下游<b>不得</b>编造框选区域，只能标注为"大致区域"。
     *                  把它显式建模而不是用"坐标全 0"隐式表达，
     *                  是因为"没有坐标"与"坐标在左上角"必须能被区分开。
     */
    record OcrResult(List<OcrLine> lines, boolean localized, String engine, String engineVersion) {
    }

    /**
     * @param text       该行文本
     * @param x,y,w,h    像素坐标，左上原点；{@code localized=false} 时无意义
     * @param confidence 行级置信度，可为 null（部分引擎不提供）
     * @param lineNo     引擎返回的原始行号，便于与供应商结果对账
     */
    record OcrLine(String text, int x, int y, int w, int h,
                   Double confidence, int lineNo) {
    }

    /**
     * @param objectKey 音频对象键（视频需先抽音轨）
     */
    record AsrRequest(String objectKey, String mimeType,
                      boolean wordLevelTimestamp, boolean speakerDiarization) {
    }

    record AsrResult(List<AsrSentence> sentences, String language,
                     String engine, String engineVersion, String rawObjectKey) {
    }

    /**
     * @param beginMs   毫秒，句级起点
     * @param endMs     毫秒，句级终点
     * @param words     字级时间戳，可为空（{@code wordLevelTimestamp=false} 时）
     * @param speakerId 说话人分离开启时才有
     */
    record AsrSentence(String text, long beginMs, long endMs, int sentenceId,
                       Integer speakerId, String emotion, List<AsrWord> words) {
    }

    record AsrWord(String text, long beginMs, long endMs) {
    }

    record DocumentRequest(String objectKey, String mimeType, String materialType) {
    }

    /**
     * @param paragraphs 段落（含页码与字符区间）
     * @param pageImages 扫描件/混排场景渲染出的页面图对象键，供后续 OCR 与视觉理解使用
     * @param hasTextLayer 是否存在文本层；false 表示需要走 OCR 兜底
     */
    record DocumentResult(List<DocParagraph> paragraphs, List<String> pageImages,
                          boolean hasTextLayer, String engine, String engineVersion) {
    }

    /**
     * @param pageNo DOCX 时为 null —— Word 的分页在渲染期决定，文件本身不存页码
     *               （docs/06 §3），因此 DOCX 只承诺"定位到段落"
     */
    record DocParagraph(Integer pageNo, int paraIndex, String text,
                        int charStart, int charEnd) {
    }
}
