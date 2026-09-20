package com.guangxuan.audit.common.enums;

/**
 * 画面类风险的大致区域（见 01 文档 §6.1 与 03 文档 §7.2）。
 *
 * <p><b>用途</b>：当风险来自画面内容而非文字时（例如"画面中出现未标注依据的极限词背景板"），
 * 没有文字锚点可引用，只能用九宫格给出大致位置。
 *
 * <p><b>界面硬要求</b>：此类定位必须如实标注为"大致区域"，<b>不得暗示像素级精度</b>
 * （AGENTS.md 第 15 条：未验证的能力不得伪装成可靠可用）。
 */
public enum RegionHint {

    TOP_LEFT("左上"),
    TOP_CENTER("上中"),
    TOP_RIGHT("右上"),
    MIDDLE_LEFT("左中"),
    MIDDLE_CENTER("正中"),
    MIDDLE_RIGHT("右中"),
    BOTTOM_LEFT("左下"),
    BOTTOM_CENTER("下中"),
    BOTTOM_RIGHT("右下"),
    FULL_FRAME("整幅画面"),
    /** 已有文字锚点、无需区域提示时的取值 */
    NOT_APPLICABLE("不适用");

    private final String label;

    RegionHint(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
