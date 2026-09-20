package com.guangxuan.audit.common.enums;

/**
 * 风险等级：描述<b>潜在影响</b>。
 *
 * <p>注意与 {@code confidence}（AI 对识别结果的把握）、{@code status}（流程阶段）
 * 是三个独立维度，界面与数据模型不得混用（AGENTS.md 第 11 条）。
 */
public enum RiskLevel {
    HIGH("高风险"),
    MEDIUM("中风险"),
    LOW("低风险");

    private final String label;

    RiskLevel(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /**
     * 是否为阻断性风险。
     *
     * <p>阻断性风险未关闭时，物料版本不得被标记为最终批准（AGENTS.md 第 7 条）。
     * LOW 为非阻断：它需要法务知晓，但不阻断上线。
     */
    public boolean isBlocking() {
        return this == HIGH || this == MEDIUM;
    }
}
