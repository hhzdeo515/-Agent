package com.guangxuan.audit.common.enums;

/**
 * 风险类型（见 AGENTS.md 第 6 条要求的"风险类型分布"）。
 *
 * <p>取值必须与 {@code risk_rule.rule_type} 及前端字典一致。
 */
public enum RiskType {

    ABSOLUTE_CLAIM("绝对化宣传"),
    EVIDENCE_MISSING("产品性能缺乏证明"),
    SAFETY_PROMISE("安全承诺"),
    COMPETITOR_COMPARISON("竞品比较依据不足"),
    PRICE_CLAIM("价格宣传"),
    DISCLAIMER_MISSING("免责声明缺失"),
    MISLEADING("可能误导消费者的描述"),
    OTHER("其他");

    private final String label;

    RiskType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
