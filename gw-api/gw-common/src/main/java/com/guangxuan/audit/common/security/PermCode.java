package com.guangxuan.audit.common.security;

/**
 * 权限点常量（见 02 文档 §4.2）。
 *
 * <p><b>为什么用常量而不是散落的字符串</b>：这些 code 是前端按钮显隐、后端 {@code @PreAuthorize}
 * 与数据库角色校验三处共同的契约。一旦某处写成 {@code risk.closed} 之类的错拼，
 * 前端会静默失效（按钮藏了其实有权限，或反之），且不会报错。
 * 集中定义 + 前端 TS 联合类型同源，才能避免这类静默错误。
 *
 * <p>命名与 {@code sys_permission.code} 逐字一致。
 */
public final class PermCode {

    private PermCode() {
    }

    // ── 审核任务 Case ────────────────────────────────────────────
    public static final String CASE_VIEW = "case.view";
    public static final String CASE_LIST = "case.list";
    public static final String CASE_CREATE = "case.create";
    public static final String CASE_EDIT = "case.edit";
    public static final String CASE_UPLOAD = "case.upload";
    public static final String CASE_PARSE_RETRY = "case.parse_retry";
    public static final String CASE_REQUIREMENT_CONFIRM = "case.requirement_confirm";
    public static final String CASE_START_INITIAL_REVIEW = "case.start_initial_review";
    public static final String CASE_REJECT = "case.reject";
    public static final String CASE_RESUME_REMEDIATION = "case.resume_remediation";
    public static final String CASE_BACK_TO_FEEDBACK = "case.back_to_feedback";
    public static final String CASE_ARCHIVE = "case.archive";

    // ── 风险 ─────────────────────────────────────────────────────
    public static final String RISK_VIEW = "risk.view";
    public static final String RISK_CONFIRM = "risk.confirm";
    public static final String RISK_FALSE_POSITIVE = "risk.false_positive";
    public static final String RISK_REQUEST_EVIDENCE = "risk.request_evidence";
    public static final String RISK_TO_REMEDIATION = "risk.to_remediation";
    public static final String RISK_REREVIEW_TRIGGER = "risk.rereview_trigger";
    public static final String RISK_LEGAL_FINAL_REVIEW = "risk.legal_final_review";
    /** 法务签名：AI 与非法务角色<b>永远</b>不得持有 */
    public static final String RISK_SIGN = "risk.sign";
    /** 关闭风险：AI 与非法务角色<b>永远</b>不得持有 */
    public static final String RISK_CLOSE = "risk.close";

    // ── 物料版本 ─────────────────────────────────────────────────
    public static final String VERSION_VIEW = "version.view";
    public static final String VERSION_UPLOAD = "version.upload";
    public static final String VERSION_DIFF_VIEW = "version.diff_view";

    // ── 最终批准 ─────────────────────────────────────────────────
    /** 标记最终批准版本：AI 与非法务角色<b>永远</b>不得持有 */
    public static final String MATERIAL_APPROVE = "material.approve";
    public static final String MATERIAL_REVOKE_APPROVAL = "material.revoke_approval";

    // ── 报告 ─────────────────────────────────────────────────────
    public static final String REPORT_VIEW = "report.view";
    public static final String REPORT_EXPORT = "report.export";

    // ── AI 法务助手 ──────────────────────────────────────────────
    public static final String ASSISTANT_USE = "assistant.use";
    public static final String ASSISTANT_FILE_UPLOAD = "assistant.file_upload";
    public static final String ASSISTANT_TO_FORMAL_CASE = "assistant.to_formal_case";

    // ── 知识库 ───────────────────────────────────────────────────
    public static final String KB_VIEW = "kb.view";
    public static final String KB_EDIT = "kb.edit";
    public static final String KB_PUBLISH = "kb.publish";

    // ── 管理 ─────────────────────────────────────────────────────
    public static final String ADMIN_USER_MANAGE = "admin.user_manage";
    public static final String ADMIN_RULE_MANAGE = "admin.rule_manage";
    public static final String ADMIN_AUDIT_VIEW = "admin.audit_view";
    public static final String ADMIN_CONFIG = "admin.config";

    /**
     * 只有法务可持有的权限点集合。
     *
     * <p>用于在启动时与运行时做自检：若某个非 LEGAL 角色被授予了这些权限，
     * 属于配置错误，应当拒绝启动（而不是运行到一半才发现管理员的角色配错了）。
     */
    public static final java.util.Set<String> LEGAL_ONLY = java.util.Set.of(
            RISK_CONFIRM,
            RISK_FALSE_POSITIVE,
            RISK_REQUEST_EVIDENCE,
            RISK_TO_REMEDIATION,
            RISK_LEGAL_FINAL_REVIEW,
            RISK_SIGN,
            RISK_CLOSE,
            MATERIAL_APPROVE,
            MATERIAL_REVOKE_APPROVAL,
            CASE_REQUIREMENT_CONFIRM,
            CASE_START_INITIAL_REVIEW,
            CASE_REJECT,
            CASE_RESUME_REMEDIATION,
            CASE_BACK_TO_FEEDBACK,
            KB_PUBLISH);

    /**
     * 无论任何角色都不得授予 AI 服务账号的权限点。
     *
     * <p>这是 AGENTS.md 第 1 条与第 8 条的代码化：AI 可以建议状态，但不得执行
     * 最终批准、法务签名、风险关闭。
     */
    public static final java.util.Set<String> NEVER_FOR_AI = java.util.Set.of(
            RISK_CONFIRM,
            RISK_FALSE_POSITIVE,
            RISK_SIGN,
            RISK_CLOSE,
            MATERIAL_APPROVE,
            MATERIAL_REVOKE_APPROVAL,
            CASE_REJECT,
            CASE_RESUME_REMEDIATION,
            KB_PUBLISH);
}
