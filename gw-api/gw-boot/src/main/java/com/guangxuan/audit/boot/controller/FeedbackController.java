package com.guangxuan.audit.boot.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guangxuan.audit.app.service.InitialReviewReportService;
import com.guangxuan.audit.app.service.ReviewRecordService;
import com.guangxuan.audit.app.service.RiskAppService;
import com.guangxuan.audit.common.api.Result;
import com.guangxuan.audit.common.enums.RiskLevel;
import com.guangxuan.audit.common.enums.RiskType;
import com.guangxuan.audit.infra.persistence.entity.InitialReviewReportEntity;
import com.guangxuan.audit.infra.persistence.entity.RiskCaseEntity;
import com.guangxuan.audit.infra.persistence.mapper.LegalBasisMapper;
import com.guangxuan.audit.infra.persistence.mapper.MaterialMapper;
import com.guangxuan.audit.infra.persistence.mapper.SysUserMapper;
import com.guangxuan.audit.domain.security.Actor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final InitialReviewReportService reportService;
    private final LegalBasisMapper legalBasisMapper;
    private final SysUserMapper sysUserMapper;
    private final com.guangxuan.audit.infra.persistence.mapper.EvidenceAnchorMapper anchorMapper;
    private final com.guangxuan.audit.infra.persistence.mapper.MaterialVersionMapper versionMapper;
    private final ObjectMapper objectMapper;

    // ── 风险定位：原文上下文 ────────────────────────────────────────────

    /** 上下文窗口：命中行前后各取几行 */
    private static final int CONTEXT_SPAN = 2;

    /**
     * 风险在原内容中的位置与上下文。
     *
     * <p>AGENTS.md 第 5 条要求"法务进入风险详情后，应能直接跳转到原内容所在位置，
     * 而不需要从头重新阅读整份材料"。只给一个 {@code locationDesc} 字符串是做不到这一点的——
     * 法务仍然不知道这句话前后写了什么，而"绝对化用语被删掉后整句还通不通"
     * 恰恰取决于上下文。因此这里返回命中锚点及其邻近锚点，前端照原顺序渲染，
     * 命中行高亮。
     *
     * <p>跨版本安全性：锚点按 {@code currentVersionId} 取，且复合外键保证
     * 风险引用的锚点确实属于该版本。
     */
    @GetMapping("/risks/{riskId}/context")
    @PreAuthorize("@perm.has('risk.view')")
    public Result<Map<String, Object>> context(Actor actor, @PathVariable Long riskId) {
        RiskCaseEntity risk = riskAppService.loadEntity(riskId);
        Long versionId = risk.getCurrentVersionId();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("riskId", riskId);
        out.put("riskNo", risk.getRiskNo());
        out.put("riskText", risk.getRiskText());
        out.put("locationDesc", risk.getLocationDesc());
        out.put("regionHint", risk.getRegionHint());
        out.put("firstVersionId", risk.getFirstVersionId());
        out.put("currentVersionId", versionId);
        out.put("canLocate", false);

        if (versionId == null) {
            out.put("reason", "该风险没有关联版本，无法定位（历史数据或解析未完成）");
            out.put("lines", List.of());
            return Result.ok(out);
        }

        var version = versionMapper.selectById(versionId);
        if (version != null) {
            out.put("versionLabel", version.getVersionLabel());
        }

        List<com.guangxuan.audit.infra.persistence.entity.EvidenceAnchorEntity> all =
                anchorMapper.listByVersion(versionId);

        // 该风险引用的锚点（PRIMARY / SUPPORTING 都算命中）
        Set<String> hitIds = new LinkedHashSet<>();
        int primaryOrdinal = -1;
        for (var a : all) {
            if (anchorMapper.countRef(riskId, a.getAnchorId()) > 0) {
                hitIds.add(a.getAnchorId());
                if (primaryOrdinal < 0) {
                    primaryOrdinal = a.getOrdinal() == null ? -1 : a.getOrdinal();
                }
            }
        }

        if (hitIds.isEmpty()) {
            out.put("reason", "该风险未关联到具体锚点（例如画面类风险暂无文字锚点），只能按大致区域定位");
            out.put("lines", List.of());
            return Result.ok(out);
        }

        // 命中行 ±CONTEXT_SPAN 行组成阅读窗口；没有 ordinal 的锚点不参与窗口计算，
        // 但仍会在命中行里出现（否则法务会看到"有风险但没有原文"）
        int from = primaryOrdinal < 0 ? Integer.MIN_VALUE : Math.max(1, primaryOrdinal - CONTEXT_SPAN);
        int to = primaryOrdinal < 0 ? Integer.MAX_VALUE : primaryOrdinal + CONTEXT_SPAN;

        List<Map<String, Object>> lines = new ArrayList<>();
        for (var a : all) {
            int ord = a.getOrdinal() == null ? 0 : a.getOrdinal();
            boolean hit = hitIds.contains(a.getAnchorId());
            if (!hit && (ord < from || ord > to)) {
                continue;
            }
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("anchorId", a.getAnchorId());
            line.put("anchorType", a.getAnchorType());
            line.put("text", a.getText());
            line.put("ordinal", a.getOrdinal());
            line.put("locator", a.getLocator());
            line.put("confidence", a.getConfidence());
            line.put("sourceEngine", a.getSourceEngine());
            line.put("hit", hit);
            lines.add(line);
        }

        out.put("canLocate", true);
        out.put("lines", lines);
        out.put("hitCount", hitIds.size());
        // 定位方式必须说清楚：图片 → 框选区域；视频 → 时间码；文档 → 行/段。
        // 三种定位在界面上的呈现完全不同，混成一种会让法务按错误的方式去找（AGENTS.md 第 5 条）。
        out.put("locatorKind", locatorKind(lines));
        out.put("regionHintLabel", regionHintLabel(risk.getRegionHint()));
        return Result.ok(out);
    }

    /** 由命中锚点的类型推出定位方式；多种类型混合时如实报 MIXED */
    private String locatorKind(List<Map<String, Object>> lines) {
        Set<String> kinds = new LinkedHashSet<>();
        for (Map<String, Object> l : lines) {
            if (!Boolean.TRUE.equals(l.get("hit"))) {
                continue;
            }
            kinds.add(switch (String.valueOf(l.get("anchorType"))) {
                case "TEXT_LINE" -> "IMAGE_REGION";
                case "SPEECH_SENTENCE", "SUBTITLE_LINE" -> "VIDEO_TIMECODE";
                case "DOC_PARAGRAPH", "DOC_SENTENCE" -> "DOC_LINE";
                case "KEY_FRAME" -> "KEY_FRAME";
                default -> "OTHER";
            });
        }
        if (kinds.isEmpty()) {
            return "UNKNOWN";
        }
        return kinds.size() == 1 ? kinds.iterator().next() : "MIXED";
    }

    private String regionHintLabel(String regionHint) {
        try {
            return com.guangxuan.audit.common.enums.RegionHint.valueOf(regionHint).label();
        } catch (Exception e) {
            return null;
        }
    }

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

        // 审核依据批量还原：一屏几十条风险逐条查会变成 N+1
        Map<Long, String> basis = loadBasisText(risks);

        return Result.ok(risks.stream().map(r -> {
            Map<String, Object> m = riskView(r);
            m.put("basisText", basis.getOrDefault(r.getId(), null));
            return m;
        }).toList());
    }

    /**
     * 把 {@code rule_refs} 里的一串知识库版本 ID 还原成人能读的"审核依据"。
     *
     * <p>这正是"AI 结论必须有依据"的兑现方式：模型只能填 ID，原文由知识库补上。
     *
     * <p>关键在于<b>还原的是具体条款而不是整部法规</b>：一个版本 ID 下挂着七八条
     * 条款切片，全贴出来法务看到的就是一整部法律，"这条风险违反了哪一款"反而被淹没。
     * 因此按风险类型选条款，选法与其他调用方共用
     * {@link com.guangxuan.audit.infra.legal.LegalClausePicker}。
     */
    private Map<Long, String> loadBasisText(List<RiskCaseEntity> risks) {
        Set<Long> ids = new LinkedHashSet<>();
        for (RiskCaseEntity r : risks) {
            ids.addAll(ruleRefIds(r));
        }
        if (ids.isEmpty()) {
            return Map.of();
        }

        // kbVersionId → 该法规的全部条款切片
        Map<Long, List<LegalBasisMapper.LegalBasisRow>> byVersion = new LinkedHashMap<>();
        try {
            for (LegalBasisMapper.LegalBasisRow row : legalBasisMapper.findByIds(ids)) {
                if (row.getKbVersionId() == null) {
                    continue;
                }
                byVersion.computeIfAbsent(row.getKbVersionId(), k -> new ArrayList<>()).add(row);
            }
        } catch (Exception e) {
            log.warn("审核依据还原失败: {}", e.getMessage());
            return Map.of();
        }

        Map<Long, String> out = new LinkedHashMap<>();
        for (RiskCaseEntity r : risks) {
            List<String> parts = new ArrayList<>();
            for (Long id : ruleRefIds(r)) {
                List<LegalBasisMapper.LegalBasisRow> rows = byVersion.get(id);
                if (rows == null || rows.isEmpty()) {
                    continue;
                }
                String text = com.guangxuan.audit.infra.legal.LegalClausePicker.pickWithLaw(
                        r.getRiskType(), rows.get(0).getLawTitle(), rows);
                if (!parts.contains(text)) {
                    parts.add(text);
                }
            }
            if (!parts.isEmpty()) {
                out.put(r.getId(), String.join("\n", parts));
            }
        }
        return out;
    }

    /** rule_refs 里存的是知识库版本 ID 数组；解析失败按"无依据"处理，不猜 */
    private List<Long> ruleRefIds(RiskCaseEntity r) {
        if (r.getRuleRefs() == null || r.getRuleRefs().isBlank()) {
            return List.of();
        }
        try {
            List<Long> ids = new ArrayList<>();
            for (Object o : objectMapper.readValue(r.getRuleRefs(), List.class)) {
                if (o instanceof Number n) {
                    ids.add(n.longValue());
                }
            }
            return ids;
        } catch (Exception e) {
            log.debug("rule_refs 解析失败（按无依据处理）riskId={}: {}", r.getId(), r.getRuleRefs());
            return List.of();
        }
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

    // ── AI 初审报告 ─────────────────────────────────────────────────────

    /**
     * 取最新一版 AI 初审报告。
     *
     * <p>没有报告时返回 {@code exists=false} 而不是 404：前端据此显示"生成报告"按钮，
     * 不必靠捕获异常来分支。刻意<b>不</b>在这里自动生成——报告是一次可审计的产出，
     * 不应因为有人刷新了一下页面就多出一版。
     */
    @GetMapping("/cases/{caseId}/report")
    @PreAuthorize("@perm.has('report.view')")
    public Result<Map<String, Object>> latestReport(Actor actor, @PathVariable Long caseId) {
        InitialReviewReportEntity e = reportService.findLatest(caseId);
        Map<String, Object> out = new LinkedHashMap<>();
        if (e == null) {
            out.put("exists", false);
            out.put("rateDefinition", InitialReviewReportService.RATE_DEFINITION);
            return Result.ok(out);
        }
        out.put("exists", true);
        out.put("report", reportView(e));
        return Result.ok(out);
    }

    /**
     * 生成（或重新生成）AI 初审报告。
     *
     * <p>append-only：重新生成会新增一个 revision，不覆盖旧版——
     * 否则"事后无法还原当时看到的报告"（AGENTS.md 第 12 条）。
     */
    @PostMapping("/cases/{caseId}/report")
    @PreAuthorize("@perm.has('report.view')")
    public Result<Map<String, Object>> generateReport(Actor actor, @PathVariable Long caseId) {
        InitialReviewReportEntity e = reportService.generate(actor, caseId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("exists", true);
        out.put("report", reportView(e));
        return Result.ok(out);
    }

    private Map<String, Object> reportView(InitialReviewReportEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("caseId", e.getCaseId());
        m.put("revisionNo", e.getRevisionNo());
        m.put("contentMd", e.getContentMd());
        m.put("contentHash", e.getContentHash());
        m.put("materialCount", e.getMaterialCount());
        m.put("reviewedMaterialCount", e.getReviewedMaterialCount());
        m.put("parseFailedCount", e.getParseFailedCount());
        m.put("initialPassCount", e.getInitialPassCount());
        m.put("pendingHumanCount", e.getPendingHumanCount());
        m.put("riskFailCount", e.getRiskFailCount());
        m.put("initialPassRate", e.getInitialPassRate());
        m.put("riskTypeDistribution", e.getRiskTypeDistribution());
        m.put("modelId", e.getModelId());
        m.put("pipelineVersion", e.getPipelineVersion());
        m.put("promptVersion", e.getPromptVersion());
        m.put("generatedAt", e.getGeneratedAt());
        m.put("rateDefinition", InitialReviewReportService.RATE_DEFINITION);
        return m;
    }

    // ── 沟通话术 ────────────────────────────────────────────────────────

    /** 某个风险下已生成的沟通话术（多受众、多修订都返回） */
    @GetMapping("/risks/{riskId}/scripts")
    @PreAuthorize("@perm.has('risk.view')")
    public Result<List<Map<String, Object>>> scripts(Actor actor, @PathVariable Long riskId) {
        return Result.ok(riskAppService.listScripts(riskId).stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("riskCaseId", s.getRiskCaseId());
            m.put("audience", s.getAudience());
            m.put("revisionNo", s.getRevisionNo());
            m.put("scriptText", s.getScriptText());
            m.put("modelId", s.getModelId());
            m.put("generatedAt", s.getGeneratedAt());
            return m;
        }).toList());
    }

    // ── 人工判断动作 ────────────────────────────────────────────────────

    /**
     * 可担任整改责任方的用户。
     *
     * <p>放在反馈区而不是接收区：责任方是在"转入整改"这一步才确定的，
     * 由谁承接是风险记录上的字段（{@code risk_case.assignee_id}）。
     */
    @GetMapping("/assignees")
    @PreAuthorize("@perm.has('risk.to_remediation')")
    public Result<List<Map<String, Object>>> assignees(Actor actor) {
        return Result.ok(sysUserMapper.listActiveUsers());
    }

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
