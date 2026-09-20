package com.guangxuan.audit.common.enums;

/**
 * 宣传物料类型。
 *
 * <p>解析管线按类型分派（见 03 文档 §3）：图片走 OCR + 视觉语义；视频走 ASR + 抽帧 + 字幕 OCR；
 * 文档走文本层 + 页面渲染；纯文本直接进入审核。
 */
public enum MaterialType {

    IMAGE("图片/海报"),
    VIDEO("宣传视频"),
    TEXT("宣传语/文案"),
    PPT("演示文稿"),
    PDF("PDF 文档"),
    WORD("Word 文档");

    private final String label;

    MaterialType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 是否含画面（决定是否需要视觉模型与锚点定位） */
    public boolean hasVisual() {
        return this == IMAGE || this == VIDEO || this == PPT || this == PDF;
    }

    /** 是否含音频（决定是否需要 ASR） */
    public boolean hasAudio() {
        return this == VIDEO;
    }
}
