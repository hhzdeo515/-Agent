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
    private final com.guangxuan.audit.app.service.VersionDiffService versionDiffService;
    private final com.guangxuan.audit.app.service.RiskRereviewService riskRereviewService;

    // ── 版本差异（AGENTS.md 第 7 条）─────────────────────────────────────

    /**
     * 物料两个版本的差异。
     *
     * <p>不传参数时默认「上一版本 → 当前版本」，这是终审区最常用的比对。
     * 差异会落库（{@code version_diff}），同一对版本重复请求复用已有记录——
     * 事后复盘时才能确定"当时看到的是哪一份差异"。
     */
    @GetMapping("/materials/{materialId}/diff")
    @PreAuthorize("@perm.has('version.diff_view')")
    public Result<Map<String, Object>> diff(Actor actor,
                                            @PathVariable Long materialId,
                                            @RequestParam(required = false) Long fromVersionId,
                                            @RequestParam(required = false) Long toVersionId) {
        var view = versionDiffService.generate(actor, materialId, fromVersionId, toVersionId);
        Map<String, Object> out = new LinkedHashMap<>();
        // diffId 是"这份差异就是当时那一份"的凭据：同一对版本重复请求会复用同一条记录
        out.put("diffId", view.entity().getId());
        out.put("materialId", view.entity().getMaterialId());
        out.put("baseVersionId", view.entity().getBaseVersionId());
        out.put("targetVersionId", view.entity().getTargetVersionId());
        out.put("diffType", view.entity().getDiffType());
        out.put("summary", view.entity().getSummary());
        out.put("hasChange", view.entity().getHasChange());
        out.put("engine", view.entity().getEngine());
        out.put("createdAt", view.entity().getCreatedAt());
        out.put("payload", view.payload());
        return Result.ok(out);
    }

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

    /**
     * 执行 AI 复审并落定结论。
     *
     * <p>与旧实现的关键区别：<b>结论由服务端算出来</b>，而不是由前端把
     * 「原风险是否已解决」当成参数传进来。后者等于让调用方直接指定 AI 的判断，
     * 那么"AI 复审"就只是一个状态切换，没有任何实际核查。
     *
     * <p>服务端会依次回答三个问题（原风险是否已解决 / 是否仍有剩余风险 /
     * 是否产生新风险），发现新增风险时<b>新建关联 Risk Case</b>，
     * 然后把结论写入 review_record。
     *
     * <p>{@code reviewScope} 仍为必填且拒绝 FULL。
     */
    @PostMapping("/risks/{riskId}/rereview/result")
    @PreAuthorize("@perm.has('risk.rereview_trigger')")
    public Result<Map<String, Object>> completeRereview(Actor actor, @PathVariable Long riskId,
                                                        @RequestBody @Valid RereviewRequest req) {
        var analysis = riskRereviewService.analyze(actor, riskId, req.reviewScope());

        // 状态流转交给应用层按状态机执行；AI 只给结论，不自己推进流程
        RiskCaseEntity updated = riskAppService.completeRereview(actor, riskId,
                analysis.originalResolved(), analysis.remainingRisk(), analysis.summary());

        Map<String, Object> out = new LinkedHashMap<>(riskView(updated));
        out.put("originalResolved", analysis.originalResolved());
        out.put("remainingRisk", analysis.remainingRisk());
        out.put("pass", analysis.pass());
        out.put("newRiskIds", analysis.newRiskIds());
        out.put("newRisks", analysis.newRiskSummaries());
        out.put("evidence", analysis.evidence());
        out.put("summary", analysis.summary());
        return Result.ok(out);
    }

    // ── 法务终审 ────────────────────────────────────────────────────────

    public record FinalReviewRequest(@NotBlank String opinion,
                                     @NotNull Decision decision) {
        public enum Decision {ACCEPT_REREVIEW, REJECT_REREVIEW}
    }

    /**
     * 法务终审。
     *
     * <p>认可时<b>不改变状态</b>（风险已在 LEGAL_FINAL_REVIEW，自跃迁会被状态机拒绝），
     * 只落一条终审意见；不认可时退回整改。此前这里调用的是 {@code completeRereview}，
     * 而它同样以 LEGAL_FINAL_REVIEW 为目标，因此必然抛 ILLEGAL_TRANSITION——
     * 也就是说这条路径原本是走不通的。
     */
    @PostMapping("/risks/{riskId}/legal-final-review")
    @PreAuthorize("@perm.has('risk.legal_final_review')")
    public Result<Map<String, Object>> legalFinalReview(Actor actor, @PathVariable Long riskId,
                                                        @RequestBody @Valid FinalReviewRequest req) {
        RiskCaseEntity r = req.decision() == FinalReviewRequest.Decision.ACCEPT_REREVIEW
                ? riskAppService.legalFinalReviewAccept(actor, riskId, req.opinion())
                : riskAppService.legalFinalReviewReject(actor, riskId, req.opinion());
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

    /**
     * 某物料的批准记录。
     *
     * <p>存在的意义是让「撤销批准」可用：撤销需要 approvalId，
     * 没有这个只读接口，前端只能靠写死 id 才能调通那条路径。
     */
    @GetMapping("/materials/{materialId}/approvals")
    @PreAuthorize("@perm.has('material.approve')")
    public Result<List<Map<String, Object>>> listApprovals(Actor actor, @PathVariable Long materialId) {
        return Result.ok(approvalAppService.listApprovals(actor, materialId).stream()
                .map(a -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", a.getId());
                    m.put("versionId", a.getVersionId());
                    m.put("fileSha256", a.getFileSha256());
                    m.put("status", a.getStatus());
                    m.put("approvedAt", a.getApprovedAt());
                    m.put("revokedAt", a.getRevokedAt());
                    m.put("revokeReason", a.getRevokeReason());
                    return m;
                })
                .toList());
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
