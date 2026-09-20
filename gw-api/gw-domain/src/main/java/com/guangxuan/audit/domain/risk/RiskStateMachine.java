package com.guangxuan.audit.domain.risk;

import com.guangxuan.audit.common.enums.RiskStatus;
import com.guangxuan.audit.common.enums.RiskTransitionRules;
import com.guangxuan.audit.common.error.DomainException;
import com.guangxuan.audit.common.error.ErrorCode;
import com.guangxuan.audit.common.security.PermCode;
import com.guangxuan.audit.domain.security.Actor;

import java.time.LocalDateTime;

/**
 * 风险状态机：<b>所有</b> Risk Case 状态变更的唯一入口。
 *
 * <p>把守卫集中在这里的原因：状态是这套系统里最容易被绕过的部分。若允许各处
 * {@code risk.setStatus(...)}，那么"AI 不能关闭风险""关闭必须先签名""待人工判断必须先处理"
 * 这些规则迟早会被某个新写的 Service 或某个补数据脚本绕过。
 *
 * <p>因此配套三条纪律（见 02 文档 §5.2）：
 * <ol>
 *   <li>禁止直接调用 setter 改状态，必须走本类；</li>
 *   <li>ArchUnit 测试断言没有任何类直接改 {@code status} 字段；</li>
 *   <li>数据库触发器做最后一道兜底（CLOSED 必须有签名、跃迁必须命中白名单）。</li>
 * </ol>
 *
 * <p>本类返回 {@link TransitionResult} 而<b>不</b>直接持久化审计记录，是为了让领域层不依赖仓储；
 * 应用层必须用返回结果写 {@code review_record}，且该断言由集成测试覆盖。
 */
public final class RiskStateMachine {

    private RiskStateMachine() {
    }

    /**
     * 一次状态跃迁的结果，供应用层写入 {@code review_record}。
     *
     * @param action  审计动作名，对应 01 文档 §4.7 的 {@code review_record.action}
     * @param opinion 人工意见/理由
     * @param payload 附加结构化信息（如复审三问结论、reviewScope）
     */
    public record TransitionResult(
            RiskStatus from,
            RiskStatus to,
            String action,
            String opinion,
            String payload) {
    }

    // ── 法务操作 ─────────────────────────────────────────────────

    /** 确认风险（AGENTS.md 第 5 条：只有法务能确认） */
    public static TransitionResult confirm(RiskCase risk, Actor actor, String opinion) {
        actor.requirePermission(PermCode.RISK_CONFIRM);
        return transit(risk, RiskStatus.CONFIRMED, actor, "LEGAL_CONFIRM", opinion, null);
    }

    /**
     * 标记误判。
     *
     * <p>误判记录<b>不能删除</b>，必须保存法务理由，作为后续模型评测和规则优化数据
     * （AGENTS.md 第 5 条）。理由必填同时由数据库 CHECK 约束兜底。
     */
    public static TransitionResult markFalsePositive(RiskCase risk, Actor actor, String opinion) {
        actor.requirePermission(PermCode.RISK_FALSE_POSITIVE);
        if (opinion == null || opinion.isBlank()) {
            throw new DomainException(ErrorCode.FALSE_POSITIVE_REASON_REQUIRED);
        }
        return transit(risk, RiskStatus.REJECTED_FALSE_POSITIVE, actor,
                "LEGAL_FALSE_POSITIVE", opinion, null);
    }

    /** 要求补充证明材料 */
    public static TransitionResult requestEvidence(RiskCase risk, Actor actor, String requiredEvidence) {
        actor.requirePermission(PermCode.RISK_REQUEST_EVIDENCE);
        risk.setRequiredEvidence(requiredEvidence);
        return transit(risk, RiskStatus.AWAITING_EVIDENCE, actor,
                "REQUEST_EVIDENCE", requiredEvidence, null);
    }

    /**
     * 转入整改（进入终审区）。
     *
     * <p>守卫要求责任方已指定——否则整改任务无人承接，风险会静默积压。
     */
    public static TransitionResult toRemediation(RiskCase risk, Actor actor,
                                                 Long assigneeId, LocalDateTime dueAt, String note) {
        actor.requirePermission(PermCode.RISK_TO_REMEDIATION);
        if (assigneeId == null && risk.getAssigneeId() == null) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED,
                    "转入整改必须指定责任方（assigneeId）");
        }
        risk.setAssigneeId(assigneeId != null ? assigneeId : risk.getAssigneeId());
        risk.setRemediationDueAt(dueAt);
        return transit(risk, RiskStatus.AWAITING_REVISION, actor, "TO_REMEDIATION", note, null);
    }

    /** 补充材料通过，回到已确认 */
    public static TransitionResult evidenceAccepted(RiskCase risk, Actor actor, String opinion) {
        actor.requirePermission(PermCode.RISK_REQUEST_EVIDENCE);
        return transit(risk, RiskStatus.CONFIRMED, actor, "LEGAL_CONFIRM", opinion, null);
    }

    /** 材料不足，需改文案 */
    public static TransitionResult evidenceInsufficient(RiskCase risk, Actor actor,
                                                        Long assigneeId, LocalDateTime dueAt, String note) {
        actor.requirePermission(PermCode.RISK_TO_REMEDIATION);
        risk.setAssigneeId(assigneeId != null ? assigneeId : risk.getAssigneeId());
        risk.setRemediationDueAt(dueAt);
        return transit(risk, RiskStatus.AWAITING_REVISION, actor, "TO_REMEDIATION", note, null);
    }

    /** 法务终审：认可 AI 复审结论 → 待关闭 */
    public static TransitionResult legalFinalReviewAccept(RiskCase risk, Actor actor, String opinion) {
        actor.requirePermission(PermCode.RISK_LEGAL_FINAL_REVIEW);
        return transit(risk, RiskStatus.LEGAL_FINAL_REVIEW, actor,
                "LEGAL_FINAL_REVIEW", opinion, null);
    }

    /** 法务终审：不认可 AI 复审结论，退回整改 */
    public static TransitionResult legalFinalReviewReject(RiskCase risk, Actor actor, String opinion) {
        actor.requirePermission(PermCode.RISK_LEGAL_FINAL_REVIEW);
        return transit(risk, RiskStatus.AWAITING_REVISION, actor,
                "LEGAL_FINAL_REVIEW", opinion, "{\"decision\":\"REJECT_REREVIEW\"}");
    }

    /**
     * 关闭风险。
     *
     * <p>三重前置：法务权限 + 必须处于法务终审 + 必须已有 RISK_CLOSE 签名。
     * 数据库触发器 {@code trg_risk_case_close_requires_signature} 会再校验一次签名，
     * 因此即使有人绕过应用层直接改库也关不掉（02 文档 T3/T4）。
     */
    public static TransitionResult close(RiskCase risk, Actor actor, String reason) {
        actor.requirePermission(PermCode.RISK_CLOSE);
        if (!risk.isHasLegalCloseSignature()) {
            throw new DomainException(ErrorCode.SIGNATURE_REQUIRED,
                    "关闭风险前必须先完成法务签名");
        }
        return transit(risk, RiskStatus.CLOSED, actor, "CLOSE", reason, null);
    }

    // ── 系统 / AI 操作 ───────────────────────────────────────────

    /**
     * 转待人工判断。
     *
     * <p>由 AI 或系统在下列情形触发（03 文档 §6.2）：置信度低于阈值、无依据、
     * 存在无法支撑的引用、无锚点可定位、或"低置信度 + 高影响"。
     */
    public static TransitionResult toPendingHuman(RiskCase risk, Actor actor, String reason) {
        return transit(risk, RiskStatus.PENDING_LEGAL_DECISION, actor,
                "STATUS_CHANGE", reason, null);
    }

    /** 新版本上传且解析成功 → 已重新提交 */
    public static TransitionResult markResubmitted(RiskCase risk, Actor actor, Long newVersionId) {
        risk.setCurrentVersionId(newVersionId);
        return transit(risk, RiskStatus.RESUBMITTED, actor, "UPLOAD_VERSION",
                "新版本已上传", "{\"versionId\":" + newVersionId + "}");
    }

    /**
     * 启动 AI 复审。
     *
     * <p>范围校验：拒绝 {@link ReviewScope#FULL}，防止终审区退化为第二次全量初审
     * （AGENTS.md 第 7 条）。范围会被写入审计记录。
     */
    public static TransitionResult startRereview(RiskCase risk, Actor actor, ReviewScope scope) {
        if (scope == null || scope.isForbiddenForRereview()) {
            throw new DomainException(ErrorCode.REVIEW_SCOPE_FORBIDDEN,
                    "复审范围不允许为 FULL（终审区不得重新做全量初审）");
        }
        return transit(risk, RiskStatus.AI_REREVIEW, actor, "AI_REREVIEW",
                "AI 复审中", "{\"reviewScope\":\"" + scope.name() + "\"}");
    }

    /**
     * 完成 AI 复审。
     *
     * <p>复审必须回答三个问题（AGENTS.md 第 7 条）：原风险是否已解决、当前是否仍有剩余风险、
     * 本次修改是否产生新风险。若仍有剩余风险 → 回整改；否则进入法务终审。
     *
     * <p>注意：<b>若发现新增风险，必须新建关联 Risk Case</b>（{@code parent_risk_id} 指向本条），
     * 而不是悄悄改变原风险的含义。新风险的创建由应用层在本方法之外完成。
     */
    public static TransitionResult completeRereview(RiskCase risk, Actor actor,
                                                    boolean originalResolved,
                                                    boolean remainingRisk,
                                                    String summary) {
        boolean pass = originalResolved && !remainingRisk;
        RiskStatus target = pass ? RiskStatus.LEGAL_FINAL_REVIEW : RiskStatus.AWAITING_REVISION;
        String payload = "{\"originalResolved\":" + originalResolved
                + ",\"remainingRisk\":" + remainingRisk
                + ",\"rereviewSummary\":\"" + escape(summary) + "\"}";
        return transit(risk, target, actor, "AI_REREVIEW", summary, payload);
    }

    // ── 通用跃迁内核 ─────────────────────────────────────────────

    private static TransitionResult transit(RiskCase risk, RiskStatus target, Actor actor,
                                            String action, String opinion, String payload) {
        RiskStatus from = risk.getStatus();
        if (from == null) {
            throw new DomainException(ErrorCode.ILLEGAL_TRANSITION, "风险当前状态为空，数据异常");
        }
        if (from == target) {
            throw new DomainException(ErrorCode.ILLEGAL_TRANSITION,
                    "风险已处于目标状态：" + target);
        }

        // 守卫 1：跃迁合法性（白名单）
        if (!RiskTransitionRules.isAllowed(from, target)) {
            throw new DomainException(ErrorCode.ILLEGAL_TRANSITION,
                    String.format("不允许的状态跃迁：%s → %s", from, target));
        }

        // 守卫 2：需要法务的跃迁必须由法务执行
        if (RiskTransitionRules.requiresLegal(from, target)) {
            actor.requireLegal(String.format("状态跃迁 %s → %s", from, target));
        }

        // 守卫 3：业务前置条件
        if (target == RiskStatus.CLOSED && !risk.isHasLegalCloseSignature()) {
            throw new DomainException(ErrorCode.SIGNATURE_REQUIRED);
        }

        risk.applyStatus(target);

        return new TransitionResult(from, target, action, opinion, payload);
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ");
    }
}
