package com.guangxuan.audit.boot.controller;

import com.guangxuan.audit.app.service.ApprovalAppService;
import com.guangxuan.audit.app.service.RiskAppService;
import com.guangxuan.audit.common.api.Result;
import com.guangxuan.audit.domain.risk.ReviewScope;
import com.guangxuan.audit.domain.security.Actor;
import com.guangxuan.audit.infra.persistence.entity.LegalSignatureEntity;
import com.guangxuan.audit.infra.persistence.entity.MaterialApprovalEntity;
import com.guangxuan.audit.infra.persistence.entity.RiskCaseEntity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 终审区接口（AGENTS.md 第 7 条）。
 *
 * <p><b>本模块明确禁止</b>：重新对所有物料执行一次完整初审。
 * 主要对象是风险记录，不是物料。
 *
 * <p>两个强制约束在接口层就体现出来：
 * <ul>
 *   <li>复审接口{@code 必须}携带 {@code reviewScope}，且服务端拒绝 {@code FULL}——
 *       这样"终审区没有退化为第二次全量初审"是有据可查的；</li>
 *   <li>关闭风险与批准物料都必须由具备法务权限的主体执行，
 *       且领域层与数据库触发器会再各校验一次。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/final-review")
@RequiredArgsConstructor
public class FinalReviewController {

    private final RiskAppService riskAppService;
    private final ApprovalAppService approvalAppService;

    // ── 风险列表（已转入终审区的） ───────────────────────────────────────

    @GetMapping("/risks")
    @PreAuthorize("@perm.has('risk.view')")
    public Result<List<Map<String, Object>>> list(Actor actor, @RequestParam Long caseId) {
        List<RiskCaseEntity> risks = riskAppService.listByCase(caseId).stream()
                .filter(r -> switch (r.getStatus()) {
                    case "CONFIRMED", "AWAITING_EVIDENCE", "AWAITING_REVISION",
                         "RESUBMITTED", "AI_REREVIEW", "LEGAL_FINAL_REVIEW" -> true;
                    default -> false;
                })
                .toList();
        return Result.ok(risks.stream().map(this::riskView).toList());
    }

    // ── AI 复审 ─────────────────────────────────────────────────────────

    public record RereviewRequest(@NotNull ReviewScope reviewScope) {
    }

    /**
     * 启动 AI 复审。
     *
     * <p>{@code reviewScope=FULL} 会被拒绝（HTTP 400）——这是 AGENTS.md 第 7 条
     * "不得把终审区重新做成一次无差别的全量初审"的强制点。
     */
    @PostMapping("/risks/{riskId}/rereview")
    @PreAuthorize("@perm.has('risk.rereview_trigger')")
    public Result<Map<String, Object>> startRereview(Actor actor, @PathVariable Long riskId,
                                                     @RequestBody @Valid RereviewRequest req) {
        return Result.ok(riskView(riskAppService.startRereview(actor, riskId, req.reviewScope())));
    }

    public record RereviewResultRequest(boolean originalResolved,
                                        boolean remainingRisk,
                                        String summary) {
    }

    /**
     * 完成 AI 复审，回答三个必答问题：
     * 原风险是否已解决、当前是否仍有剩余风险、本次修改是否产生新风险。
     *
     * <p>若发现新增风险，应<b>新建关联 Risk Case</b>（{@code parentRiskId} 指向本条），
     * 而不是改写原风险的含义。
     */
    @PostMapping("/risks/{riskId}/rereview/result")
    @PreAuthorize("@perm.has('risk.rereview_trigger')")
    public Result<Map<String, Object>> completeRereview(Actor actor, @PathVariable Long riskId,
                                                        @RequestBody RereviewResultRequest req) {
        return Result.ok(riskView(riskAppService.completeRereview(actor, riskId,
                req.originalResolved(), req.remainingRisk(), req.summary())));
    }

    // ── 法务终审 ────────────────────────────────────────────────────────

    public record FinalReviewRequest(@NotBlank String opinion,
                                     @NotNull Decision decision) {
        public enum Decision {ACCEPT_REREVIEW, REJECT_REREVIEW}
    }

    /** 法务终审 */
    @PostMapping("/risks/{riskId}/legal-final-review")
    @PreAuthorize("@perm.has('risk.legal_final_review')")
    public Result<Map<String, Object>> legalFinalReview(Actor actor, @PathVariable Long riskId,
                                                        @RequestBody @Valid FinalReviewRequest req) {
        RiskCaseEntity r = req.decision() == FinalReviewRequest.Decision.ACCEPT_REREVIEW
                ? riskAppService.completeRereview(actor, riskId, true, false, req.opinion())
                : riskAppService.completeRereview(actor, riskId, false, true, req.opinion());
        return Result.ok(riskView(r));
    }

    // ── 签名与关闭 ──────────────────────────────────────────────────────

    public record SignRequest(String comment) {
    }

    /** 法务签名（风险关闭前置） */
    @PostMapping("/risks/{riskId}/signatures")
    @PreAuthorize("@perm.has('risk.sign')")
    public Result<Map<String, Object>> sign(Actor actor, @PathVariable Long riskId,
                                            @RequestBody(required = false) SignRequest req) {
        LegalSignatureEntity sig = approvalAppService.signRiskClose(actor, riskId,
                req == null ? null : req.comment());
        return Result.ok(Map.of(
                "signatureId", sig.getId(),
                "signType", sig.getSignType(),
                "signatureHash", sig.getSignatureHash(),
                "signedAt", sig.getSignedAt()));
    }

    public record CloseRequest(@NotBlank String closeReason) {
    }

    /**
     * 关闭风险。
     *
     * <p>必须先完成签名；领域层与数据库触发器都会校验。
     * 关闭后风险视为已解决并留痕，历史记录不可删除（AGENTS.md 第 5、9 条）。
     */
    @PostMapping("/risks/{riskId}/close")
    @PreAuthorize("@perm.has('risk.close')")
    public Result<Map<String, Object>> close(Actor actor, @PathVariable Long riskId,
                                             @RequestBody @Valid CloseRequest req) {
        return Result.ok(riskView(riskAppService.signAndClose(actor, riskId, req.closeReason(), null)));
    }

    // ── 物料批准 ────────────────────────────────────────────────────────

    /**
     * 批准影响范围（供前端二次确认展示）。
     *
     * <p>AGENTS.md 第 13 条：高风险动作需要二次确认并<b>展示影响范围</b>。
     */
    @GetMapping("/materials/{materialId}/approval-context")
    @PreAuthorize("@perm.has('material.approve')")
    public Result<Map<String, Object>> approvalContext(Actor actor,
                                                       @PathVariable Long materialId,
                                                       @RequestParam Long versionId) {
        return Result.ok(approvalAppService.approvalImpact(actor, materialId, versionId));
    }

    public record ApproveRequest(@NotNull Long versionId, String comment) {
    }

    /**
     * 标记最终批准版本。
     *
     * <p>存在未关闭阻断性风险时返回 {@code BLOCKING_RISK_OPEN}——
     * 这是"能不能对外发布"的判定，不是形式校验。
     */
    @PostMapping("/materials/{materialId}/approvals")
    @PreAuthorize("@perm.has('material.approve')")
    public Result<Map<String, Object>> approve(Actor actor, @PathVariable Long materialId,
                                               @RequestBody @Valid ApproveRequest req) {
        MaterialApprovalEntity a = approvalAppService.approveMaterial(
                actor, materialId, req.versionId(), req.comment());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("approvalId", a.getId());
        out.put("versionId", a.getVersionId());
        out.put("fileSha256", a.getFileSha256());
        out.put("status", a.getStatus());
        out.put("approvedAt", a.getApprovedAt());
        return Result.ok(out);
    }

    public record RevokeRequest(@NotBlank String revokeReason) {
    }

    /** 撤销批准（必须填写原因） */
    @PostMapping("/materials/{materialId}/approvals/{approvalId}/revoke")
    @PreAuthorize("@perm.has('material.revoke_approval')")
    public Result<Map<String, Object>> revoke(Actor actor, @PathVariable Long materialId,
                                              @PathVariable Long approvalId,
                                              @RequestBody @Valid RevokeRequest req) {
        MaterialApprovalEntity a = approvalAppService.revokeApproval(
                actor, materialId, approvalId, req.revokeReason());
        return Result.ok(Map.of("approvalId", a.getId(), "status", a.getStatus()));
    }

    // ── 视图 ────────────────────────────────────────────────────────────

    private Map<String, Object> riskView(RiskCaseEntity r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("riskNo", r.getRiskNo());
        m.put("caseId", r.getCaseId());
        m.put("materialId", r.getMaterialId());
        m.put("riskType", r.getRiskType());
        m.put("riskLevel", r.getRiskLevel());
        m.put("confidence", r.getConfidence());
        m.put("status", r.getStatus());
        m.put("riskText", r.getRiskText());
        m.put("locationDesc", r.getLocationDesc());
        m.put("blocked", r.getBlocked());
        m.put("assigneeId", r.getAssigneeId());
        m.put("remediationDueAt", r.getRemediationDueAt());
        m.put("firstVersionId", r.getFirstVersionId());
        m.put("currentVersionId", r.getCurrentVersionId());
        m.put("closedAt", r.getClosedAt());
        return m;
    }
}
