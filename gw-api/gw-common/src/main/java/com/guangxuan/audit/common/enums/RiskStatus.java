package com.guangxuan.audit.common.enums;

/**
 * Risk Case 状态（见 02 文档 §3.1）。
 *
 * <p>{@code AGENTS.md} 第 8 条要求即使调整枚举名称，也**不得省略**下列语义：
 * 待人工判断、整改中、已重新提交、AI 复审、法务终审、已关闭、误判留痕。
 * 因此这些状态在 {@link #isMandatorySemantics()} 中显式标注，防止后续重构时被合并掉。
 *
 * <p>枚举值必须与 {@code risk_case.status} 及 {@code risk_status_transition} 表逐字一致。
 */
public enum RiskStatus {

    /** 新建：AI 识别出且置信度高、依据充分 */
    OPEN("新建", false),

    /** 待人工判断：证据不足 / 事实无法核实 / 解释有空间 / 规则冲突 / 低置信度高影响 */
    PENDING_LEGAL_DECISION("待人工判断", true),

    /** 法务确认存在风险 */
    CONFIRMED("法务已确认", false),

    /** 要求补充证明材料 */
    AWAITING_EVIDENCE("等待补充材料", false),

    /** 整改中：已转入终审区等待新版本 */
    AWAITING_REVISION("整改中", true),

    /** 已重新提交：新版本已上传 */
    RESUBMITTED("已重新提交", true),

    /** AI 复审中 */
    AI_REREVIEW("AI 复审中", true),

    /** 法务终审 */
    LEGAL_FINAL_REVIEW("法务终审", true),

    /** 已关闭：已解决并完成签名 */
    CLOSED("已关闭", true),

    /** 误判：记录保留、理由必填（不可删除） */
    REJECTED_FALSE_POSITIVE("误判留痕", true);

    private final String label;
    private final boolean mandatorySemantics;

    RiskStatus(String label, boolean mandatorySemantics) {
        this.label = label;
        this.mandatorySemantics = mandatorySemantics;
    }

    public String label() {
        return label;
    }

    /** AGENTS.md 第 8 条要求不得省略的语义 */
    public boolean isMandatorySemantics() {
        return mandatorySemantics;
    }

    /** 是否为终态 */
    public boolean isTerminal() {
        return this == CLOSED || this == REJECTED_FALSE_POSITIVE;
    }

    /** 是否已计入"已解决"（用于物料批准前的阻断性风险判定） */
    public boolean isResolved() {
        return isTerminal();
    }

    /** 是否属于"待人工处理"——这些风险必须先由法务处置才能继续流转（AGENTS.md 第 5 条） */
    public boolean isPendingHuman() {
        return this == OPEN || this == PENDING_LEGAL_DECISION || this == AWAITING_EVIDENCE;
    }
}
