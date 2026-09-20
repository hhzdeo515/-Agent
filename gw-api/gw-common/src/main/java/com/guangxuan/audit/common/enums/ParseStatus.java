package com.guangxuan.audit.common.enums;

/**
 * 物料解析状态。
 *
 * <p><b>FAILED 与 PARTIAL 的处理差异是关键设计</b>（见 01 文档 §8.1）：
 * <ul>
 *   <li>{@code FAILED}：严格阻断，不得进入正式初审，且不计入通过率分母（AGENTS.md 第 4 条）。</li>
 *   <li>{@code PARTIAL}：允许进入初审，但必须①界面标注"部分解析"；②该物料下 AI 判断的
 *       confidence 强制封顶；③不得归入"AI 初审通过"。</li>
 * </ul>
 * 理由：视频 ASR 成功而抽帧失败时，口播风险仍应被审出，一律阻断会让大量真实物料无法审核。
 */
public enum ParseStatus {

    PENDING("待解析"),
    RUNNING("解析中"),
    SUCCEEDED("解析成功"),
    FAILED("解析失败"),
    PARTIAL("部分解析");

    private final String label;

    ParseStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 是否可进入正式初审 */
    public boolean isReviewable() {
        return this == SUCCEEDED || this == PARTIAL;
    }

    /** 是否计入初审通过率分母：解析失败的物料单独列出，不拉低通过率 */
    public boolean countsInPassRateDenominator() {
        return isReviewable();
    }

    /** 部分解析时对 AI 置信度的封顶值 */
    public static final double PARTIAL_CONFIDENCE_CAP = 0.6;

    /**
     * 按解析完整度对 AI 置信度封顶。
     *
     * <p>解析不完整意味着"没发现风险"可能只是"没看到"，模型对结论的完整性没有把握。
     */
    public double capConfidence(double confidence) {
        return this == PARTIAL ? Math.min(confidence, PARTIAL_CONFIDENCE_CAP) : confidence;
    }
}
