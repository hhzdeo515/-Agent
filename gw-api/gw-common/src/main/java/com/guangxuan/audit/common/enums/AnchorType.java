package com.guangxuan.audit.common.enums;

/**
 * 证据锚点类型（见 00 文档 §3.2）。
 *
 * <p>「证据锚点」是本方案的核心抽象：解析阶段为每个物料版本生成一批带稳定 ID 的锚点，
 * 模型输出风险时<b>只引用锚点 ID，不输出坐标</b>。定位 = ID 查表，可 100% 校验。
 * 这样既避免了模型坐标不可信的问题，也避免了用风险原文做字符串模糊匹配的静默失败。
 */
public enum AnchorType {

    /** 图片/帧中的文字行（OCR 文本定位产出，带 bbox） */
    TEXT_LINE("图片文字行"),

    /** 视频口播句（ASR 句级/字级时间戳产出） */
    SPEECH_SENTENCE("视频口播句"),

    /** 视频硬字幕行（抽帧 + OCR 产出，同时带时间与 bbox） */
    SUBTITLE_LINE("视频字幕行"),

    /** 视频关键帧 */
    KEY_FRAME("视频关键帧"),

    /** 文档段落 */
    DOC_PARAGRAPH("文档段落"),

    /** 文档句子（最细粒度） */
    DOC_SENTENCE("文档句子");

    private final String label;

    AnchorType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 是否为基于时间的锚点（用于视频时间轴定位） */
    public boolean isTimeBased() {
        return this == SPEECH_SENTENCE || this == SUBTITLE_LINE || this == KEY_FRAME;
    }

    /** 是否为基于坐标的锚点（用于画面框选定位） */
    public boolean isBoxBased() {
        return this == TEXT_LINE || this == SUBTITLE_LINE;
    }
}
