package com.guangxuan.audit.common.enums;

/**
 * AI 建议的流转方向（见 03 文档 §7.2 契约的 {@code recommendedStatus}）。
 *
 * <p>模型只能在这三个值里选——它<b>不能</b>建议 CLOSED 或 REJECTED_FALSE_POSITIVE，
 * 因为那些是法务的判断（AGENTS.md 第 8 条）。
 */
public enum RecommendedStatus {

    /** 命中较明确的风险规则，进入法务确认 */
    OPEN("建议确认风险"),

    /** 存在疑点但 AI 无法独立给出确定结论，须人工判断 */
    PENDING_LEGAL_DECISION("建议人工判断"),

    /** 需要补充证明材料 */
    AWAITING_EVIDENCE("建议要求补充材料");

    private final String label;

    RecommendedStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public RiskStatus toRiskStatus() {
        return switch (this) {
            case OPEN -> RiskStatus.OPEN;
            case PENDING_LEGAL_DECISION -> RiskStatus.PENDING_LEGAL_DECISION;
            case AWAITING_EVIDENCE -> RiskStatus.AWAITING_EVIDENCE;
        };
    }
}
