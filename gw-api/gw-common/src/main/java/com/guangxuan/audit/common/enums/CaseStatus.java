package com.guangxuan.audit.common.enums;

/**
 * 审核任务 Case 状态（见 02 文档 §2.1）。
 *
 * <p>枚举值必须与 {@code audit_case.status} 的取值逐字一致——数据库触发器与
 * {@code risk_status_transition} 白名单表都按字面值校验。
 */
public enum CaseStatus {

    /** 草稿：已建任务，材料未齐或审核要求未确认 */
    DRAFT("草稿"),

    /** 解析中：至少一个物料在解析 */
    PARSING("解析中"),

    /** 待启动初审：材料解析完成、审核要求已确认 */
    READY_FOR_REVIEW("待启动初审"),

    /** AI 初审中 */
    AI_REVIEWING("AI 初审中"),

    /** 反馈区待法务判断 */
    FEEDBACK_PENDING("待法务判断"),

    /** 整改中：已有风险确认需修改并进入终审区 */
    REMEDIATION("整改中"),

    /** 终审中：待法务终审签名 */
    FINAL_REVIEW("终审中"),

    /** 已批准 */
    APPROVED("已批准"),

    /** 已驳回 */
    REJECTED("已驳回"),

    /** 已归档 */
    ARCHIVED("已归档");

    private final String label;

    CaseStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 是否为终态（不再流转） */
    public boolean isTerminal() {
        return this == ARCHIVED;
    }

    /**
     * 该状态下是否允许上传物料。
     *
     * <p><b>必须包含整改阶段</b>：整改的实质就是品牌/设计上传修改后的新版本，
     * 若这里只允许 DRAFT/PARSING，整改流程会在第一步就卡死——
     * 这正是端到端验证暴露出的缺陷。
     *
     * <p>不允许 APPROVED / ARCHIVED：已批准的物料不应再被改动，
     * 需要变更时必须先走撤销批准，以保证"批准的是哪份文件"始终可证。
     */
    public boolean allowsUpload() {
        return this == DRAFT
                || this == PARSING
                || this == READY_FOR_REVIEW
                || this == FEEDBACK_PENDING
                || this == REMEDIATION;
    }
}
