package com.guangxuan.audit.boot.controller;

import com.guangxuan.audit.app.service.ReviewRecordService;
import com.guangxuan.audit.app.service.RiskAppService;
import com.guangxuan.audit.common.api.Result;
import com.guangxuan.audit.common.enums.RiskLevel;
import com.guangxuan.audit.common.enums.RiskType;
import com.guangxuan.audit.infra.persistence.entity.RiskCaseEntity;
import com.guangxuan.audit.infra.persistence.mapper.MaterialMapper;
import com.guangxuan.audit.domain.security.Actor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 反馈区接口（AGENTS.md 第 5、6 条）。
 *
 * <p><b>本模块明确禁止</b>：管理 V1/V2/V3 等整改版本、Version Diff、最终审批、
 * 签名、风险关闭。因此这里<b>没有</b>版本创建接口、<b>没有</b> Diff 接口、
 * <b>没有</b>签名与关闭接口——它们在终审区。
 *
 * <p>本模块回答的是"这一批材料第一次审核发现了什么"，
 * 而不是"这些风险最后是否已经解决"。
 */
@Slf4j
@RestController
@RequestMapping("/api/feedback")
@RequiredArgsConstructor
public class FeedbackController {

    private final RiskAppService riskAppService;
    private final ReviewRecordService reviewRecordService;
    private final MaterialMapper materialMapper;

    // ── 初审结果：三分类看板 ────────────────────────────────────────────

    /**
     * 物料三分类 + 通过率汇总。
     *
     * <p>分类与通过率<b>全部取自数据库视图</b>，不在 Java 里重算——
     * 这是 AGENTS.md 第 6 条"通过率口径必须明确、不能把 Risk Case 数量和物料数量
     * 混为一谈"的落地方式：口径只有一处定义。
     */
    @GetMapping("/cases/{caseId}/summary")
    @PreAuthorize("@perm.has('risk.view')")
    public Result<Map<String, Object>> summary(Actor actor, @PathVariable Long caseId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("materials", materialMapper.selectInitialReviewClasses(caseId));
        out.put("summary", materialMapper.selectInitialReviewSummary(caseId));
        out.put("riskTypeDistribution", riskCaseMapperCountByType(caseId));
        // 口径说明随数据一起返回，界面直接展示，避免各处口径不一致
        out.put("rateDefinition",
                "初审通过率 = 初审通过物料数 ÷ 参与初审物料数；解析失败物料不计入分母，单独列出。"
                        + "\"AI 初审通过\"仅表示 AI 未发现明显风险，不等于法务最终批准。");
        return Result.ok(out);
    }

    private List<Map<String, Object>> riskCaseMapperCountByType(Long caseId) {
        return riskAppService.listByCase(caseId).stream()
                .collect(Collectors.groupingBy(RiskCaseEntity::getRiskType, Collectors.counting()))
                .entrySet().stream()
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("riskType", e.getKey());
                    m.put("riskTypeLabel", safeLabel(e.getKey()));
                    m.put("count", e.getValue());
                    return m;
                })
                .sorted(Comparator.comparingLong(m -> -((Long) m.get("count"))))
                .toList();
    }

    private String safeLabel(String code) {
        try {
            return RiskType.valueOf(code).label();
        } catch (Exception e) {
            return code;
        }
    }

    // ── 风险列表与详情 ──────────────────────────────────────────────────

    /**
     * 风险列表，支持筛选。
     *
     * <p>风险等级、置信度、状态是三个独立维度，因此筛选参数也分开——
     * 界面不能用同一套控件表达它们（AGENTS.md 第 11 条）。
     */
    @GetMapping("/risks")
    @PreAuthorize("@perm.has('risk.view')")
    public Result<List<Map<String, Object>>> listRisks(Actor actor,
                                                       @RequestParam Long caseId,
                                                       @RequestParam(required = false) RiskLevel level,
                                                       @RequestParam(required = false) RiskType type,
                                                       @RequestParam(required = false) String status) {
        List<RiskCaseEntity> risks = riskAppService.listByCase(caseId).stream()
                .filter(r -> level == null || level.name().equals(r.getRiskLevel()))
                .filter(r -> type == null || type.name().equals(r.getRiskType()))
                .filter(r -> status == null || status.equals(r.getStatus()))
                .toList();
        return Result.ok(risks.stream().map(this::riskView).toList());
    }

    /** 风险详情：含定位锚点与完整处理轨迹 */
    @GetMapping("/risks/{riskId}")
    @PreAuthorize("@perm.has('risk.view')")
    public Result<Map<String, Object>> detail(Actor actor, @PathVariable Long riskId) {
        return Result.ok(riskAppService.detail(riskId));
    }

    @GetMapping("/risks/{riskId}/records")
    @PreAuthorize("@perm.has('risk.view')")
    public Result<List<?>> records(Actor actor, @PathVariable Long riskId) {
        return Result.ok(reviewRecordService.listByRisk(riskId));
    }

    // ── 人工判断动作 ────────────────────────────────────────────────────

    public record OpinionRequest(@NotBlank String opinion) {
    }

    /** 确认风险 */
    @PostMapping("/risks/{riskId}/confirm")
    @PreAuthorize("@perm.has('risk.confirm')")
    public Result<Map<String, Object>> confirm(Actor actor, @PathVariable Long riskId,
                                               @RequestBody @Valid OpinionRequest req) {
        return Result.ok(riskView(riskAppService.confirm(actor, riskId, req.opinion())));
    }

    /**
     * 标记误判。
     *
     * <p>理由必填——记录不能删除，必须保存法务理由，作为后续模型评测与规则优化数据
     * （AGENTS.md 第 5 条）。
     */
    @PostMapping("/risks/{riskId}/false-positive")
    @PreAuthorize("@perm.has('risk.false_positive')")
    public Result<Map<String, Object>> falsePositive(Actor actor, @PathVariable Long riskId,
                                                     @RequestBody @Valid OpinionRequest req) {
        return Result.ok(riskView(riskAppService.markFalsePositive(actor, riskId, req.opinion())));
    }

    public record EvidenceRequest(@NotBlank String requiredEvidence) {
    }

    /** 要求补充证明材料 */
    @PostMapping("/risks/{riskId}/request-evidence")
    @PreAuthorize("@perm.has('risk.request_evidence')")
    public Result<Map<String, Object>> requestEvidence(Actor actor, @PathVariable Long riskId,
                                                       @RequestBody @Valid EvidenceRequest req) {
        return Result.ok(riskView(riskAppService.requestEvidence(
                actor, riskId, req.requiredEvidence())));
    }

    public record RemediationRequest(@NotNull Long assigneeId,
                                     java.time.LocalDateTime dueAt,
                                     String note) {
    }

    /**
     * 转入整改（风险自动进入终审区）。
     *
     * <p>必须指定责任方——否则整改任务无人承接，风险会静默积压。
     */
    @PostMapping("/risks/{riskId}/to-remediation")
    @PreAuthorize("@perm.has('risk.to_remediation')")
    public Result<Map<String, Object>> toRemediation(Actor actor, @PathVariable Long riskId,
                                                     @RequestBody @Valid RemediationRequest req) {
        return Result.ok(riskView(riskAppService.toRemediation(
                actor, riskId, req.assigneeId(), req.dueAt(), req.note())));
    }

    // ── 视图组装 ────────────────────────────────────────────────────────

    private Map<String, Object> riskView(RiskCaseEntity r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("riskNo", r.getRiskNo());
        m.put("caseId", r.getCaseId());
        m.put("materialId", r.getMaterialId());
        m.put("riskType", r.getRiskType());
        m.put("riskTypeLabel", safeLabel(r.getRiskType()));
        m.put("riskLevel", r.getRiskLevel());
        m.put("confidence", r.getConfidence());
        m.put("status", r.getStatus());
        m.put("riskText", r.getRiskText());
        m.put("locationDesc", r.getLocationDesc());
        m.put("regionHint", r.getRegionHint());
        m.put("reason", r.getReason());
        m.put("suggestion", r.getSuggestion());
        m.put("recommendedCopy", r.getRecommendedCopy());
        m.put("requiredEvidence", r.getRequiredEvidence());
        m.put("blocked", r.getBlocked());
        m.put("assigneeId", r.getAssigneeId());
        m.put("remediationDueAt", r.getRemediationDueAt());
        m.put("firstVersionId", r.getFirstVersionId());
        m.put("currentVersionId", r.getCurrentVersionId());
        m.put("parentRiskId", r.getParentRiskId());
        m.put("unsupportedClaims", r.getUnsupportedClaims());
        m.put("createdAt", r.getCreatedAt());
        return m;
    }
}
