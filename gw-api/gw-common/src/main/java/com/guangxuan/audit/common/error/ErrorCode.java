package com.guangxuan.audit.common.error;

/**
 * 统一错误码。
 *
 * <p>分段规则（见 04 文档 §1.6）。具体数值属 04 文档 T-01 待确认项，此处给出一套可用分配，
 * 一经上线不宜再改号，因此集中定义、禁止散落硬编码。
 *
 * <pre>
 *   400xx  请求参数与格式
 *   401xx  认证
 *   403xx  授权（含模块边界越权）
 *   404xx  资源不存在
 *   409xx  并发与幂等冲突
 *   422xx  状态机拒绝
 *   430xx  审核流程
 *   431xx  物料与版本
 *   432xx  风险处理
 *   433xx  签名与批准
 *   450xx  解析与 AI 调用
 *   5xxxx  系统内部错误
 * </pre>
 */
public enum ErrorCode {

    // ── 400xx 请求参数与格式 ───────────────────────────────────────
    BAD_REQUEST(40001, "请求参数不合法"),
    VALIDATION_FAILED(40002, "参数校验失败"),
    MISSING_IDEMPOTENCY_KEY(40003, "缺少 Idempotency-Key 请求头"),

    // ── 401xx 认证 ────────────────────────────────────────────────
    UNAUTHENTICATED(40101, "未登录或凭证已失效"),
    TOKEN_EXPIRED(40102, "登录凭证已过期，请重新登录"),

    // ── 403xx 授权 ────────────────────────────────────────────────
    FORBIDDEN(40301, "无权执行该操作"),
    /** 模块边界越权：例如在接收区调用终审区的审批能力（AGENTS.md 第 1、8 条） */
    MODULE_BOUNDARY_VIOLATION(40302, "该操作不属于当前模块职责范围"),
    NOT_PROJECT_MEMBER(40303, "不是该项目成员"),

    // ── 404xx 资源不存在 ──────────────────────────────────────────
    NOT_FOUND(40401, "资源不存在"),
    /**
     * 跨项目访问统一返回 404 而非 403，避免通过状态码探测资源是否存在
     * （04 文档 §8.0 T-04 结论）。
     */
    RESOURCE_NOT_VISIBLE(40402, "资源不存在或不可见"),

    // ── 409xx 并发与幂等 ─────────────────────────────────────────
    OPTIMISTIC_LOCK_CONFLICT(40901, "数据已被他人修改，请刷新后重试"),
    VERSION_CONFLICT(40902, "版本号冲突，请刷新后重试"),
    DUPLICATE_REQUEST(40903, "该请求已提交过，已返回首次处理结果"),
    DUPLICATE_FILE(40904, "相同文件已存在，已复用已有版本"),

    // ── 422xx 状态机拒绝 ─────────────────────────────────────────
    ILLEGAL_TRANSITION(42201, "当前状态不允许该操作"),
    CASE_NOT_READY(42202, "审核任务尚未就绪，无法执行该操作"),
    RISK_NOT_PENDING(42203, "该风险无需人工判断"),
    REVIEW_SCOPE_FORBIDDEN(42204, "复审范围不允许为全量（终审区不得退化为第二次全量初审）"),

    // ── 430xx 审核流程 ────────────────────────────────────────────
    ILLEGAL_CASE_STATUS(43001, "审核任务状态不允许该操作"),
    REQUIREMENT_NOT_CONFIRMED(43005, "审核要求尚未确认，无法开始 AI 初审"),
    NO_REVIEWABLE_MATERIAL(43006, "没有可用于初审的物料（全部解析失败）"),
    /** AI 与非法务角色不得关闭风险（AGENTS.md 第 1、8 条） */
    AI_CANNOT_CLOSE_RISK(43007, "AI 与非法务角色无权关闭风险"),
    AI_CANNOT_APPROVE(43008, "AI 与非法务角色无权批准物料版本"),

    // ── 431xx 物料与版本 ─────────────────────────────────────────
    MATERIAL_PARSE_FAILED(43101, "物料解析失败，无法进入正式初审"),
    UNSUPPORTED_FILE_TYPE(43102, "不支持的文件类型"),
    FILE_HASH_MISMATCH(43103, "文件哈希与记录不一致，文件可能已被替换"),

    // ── 432xx 风险处理 ────────────────────────────────────────────
    BLOCKING_RISK_OPEN(43201, "仍有阻断性风险未关闭，无法批准该物料版本"),
    ANCHOR_NOT_FOUND(43202, "风险定位所引用的锚点不存在，已转人工判断"),
    FALSE_POSITIVE_REASON_REQUIRED(43203, "标记误判必须填写理由"),

    // ── 433xx 签名与批准 ─────────────────────────────────────────
    SIGNATURE_REQUIRED(43301, "该操作必须先完成法务签名"),
    SIGNER_NOT_PRIVILEGED(43302, "签名人须具备法务角色"),
    ALREADY_APPROVED(43303, "该版本已批准，如需变更请先撤销批准"),

    // ── 450xx 解析与 AI ──────────────────────────────────────────
    PARSE_JOB_FAILED(45001, "解析任务失败"),
    AI_OUTPUT_INVALID(45002, "AI 输出未通过结构校验，已降级为待人工判断"),
    AI_CALL_FAILED(45003, "AI 调用失败"),
    AI_PROVIDER_UNAVAILABLE(45004, "AI 服务暂不可用"),

    // ── 5xxxx 系统 ────────────────────────────────────────────────
    INTERNAL_ERROR(50001, "系统内部错误"),
    STORAGE_ERROR(50002, "对象存储操作失败"),
    CONFIGURATION_ERROR(50003, "系统配置缺失或不合法");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int code() {
        return code;
    }

    public String message() {
        return message;
    }
}
