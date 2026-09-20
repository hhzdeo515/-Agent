package com.guangxuan.audit.boot.controller;

import com.guangxuan.audit.app.service.IntakeAppService;
import com.guangxuan.audit.app.service.ParseAppService;
import com.guangxuan.audit.app.service.RiskAppService;
import com.guangxuan.audit.common.api.Result;
import com.guangxuan.audit.common.enums.MaterialType;
import com.guangxuan.audit.common.enums.ParseStatus;
import com.guangxuan.audit.common.security.PermCode;
import com.guangxuan.audit.domain.security.Actor;
import com.guangxuan.audit.infra.persistence.entity.AuditCaseEntity;
import com.guangxuan.audit.infra.persistence.entity.MaterialEntity;
import com.guangxuan.audit.infra.persistence.entity.MaterialVersionEntity;
import com.guangxuan.audit.infra.persistence.mapper.MaterialMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 接收区接口（AGENTS.md 第 4 条）。
 *
 * <p><b>本模块明确禁止</b>：展示最终风险结论、生成正式风险报告、
 * 处理整改版本、执行审批。因此这里<b>没有</b>任何返回 {@code risk_case} 明细的接口，
 * 也没有版本 Diff 或审批接口——它们分别在反馈区与终审区。
 */
@Slf4j
@RestController
@RequestMapping("/api/intake")
@RequiredArgsConstructor
public class IntakeController {

    private final IntakeAppService intakeAppService;
    private final RiskAppService riskAppService;
    private final ParseAppService parseAppService;
    private final MaterialMapper materialMapper;

    // ── 创建审核任务 ────────────────────────────────────────────────────

    public record CreateCaseRequest(@NotNull Long projectId,
                                    @NotBlank String name,
                                    LocalDateTime deadline) {
    }

    @PostMapping("/cases")
    @PreAuthorize("@perm.has('case.create')")
    public Result<Map<String, Object>> createCase(Actor actor,
                                                  @RequestBody @Valid CreateCaseRequest req) {
        AuditCaseEntity c = intakeAppService.createCase(actor, req.projectId(), req.name(), req.deadline());
        return Result.ok(caseView(c));
    }

    // ── 上传物料 ────────────────────────────────────────────────────────

    public record UploadRequest(@NotNull Long caseId,
                                Long materialId,
                                @NotBlank String fileName,
                                @NotNull MaterialType materialType,
                                @NotBlank String objectKey,
                                @NotBlank String sha256,
                                @NotNull Long fileSize,
                                @NotBlank String mimeType,
                                String uploadReason) {
    }

    /**
     * 登记物料版本。
     *
     * <p>注意：文件本身由前端直传 MinIO（预签名 URL），本接口只登记元数据。
     * 同一哈希重复上传会被复用而不新建版本（AGENTS.md 第 14 条）。
     */
    @PostMapping("/materials")
    @PreAuthorize("@perm.has('case.upload')")
    public Result<Map<String, Object>> upload(Actor actor, @RequestBody @Valid UploadRequest req) {
        MaterialVersionEntity v = intakeAppService.uploadVersion(actor, req.caseId(), req.materialId(),
                req.fileName(), req.materialType(), req.objectKey(), req.sha256(),
                req.fileSize(), req.mimeType(), req.uploadReason());
        return Result.ok(Map.of(
                "versionId", v.getId(),
                "versionLabel", v.getVersionLabel(),
                "materialId", v.getMaterialId(),
                "fileSha256", v.getFileSha256()));
    }

    // ── 审核要求 ────────────────────────────────────────────────────────

    public record ConfirmRequirementRequest(@NotNull Long caseId,
                                            @NotNull Object requirement,
                                            String templateCode) {
    }

    /**
     * 确认审核要求。模板内容必须经人工确认后才生效（AGENTS.md 第 4 条），
     * 因此这是一个独立动作，而不是随创建 Case 一起提交。
     */
    @PostMapping("/requirements")
    @PreAuthorize("@perm.has('case.requirement_confirm')")
    public Result<Void> confirmRequirement(Actor actor,
                                           @RequestBody @Valid ConfirmRequirementRequest req) {
        intakeAppService.confirmRequirement(actor, req.caseId(), req.requirement(), req.templateCode());
        return Result.ok();
    }

    // ── 启动 AI 初审 ────────────────────────────────────────────────────

    /**
     * 启动 AI 初审，并同步执行首轮审核。
     *
     * <p>真实环境应改为提交异步任务并立即返回 taskId（AGENTS.md 第 14 条要求
     * 展示真实进度与重试入口）；此处为便于联调同步执行。
     */
    @PostMapping("/cases/{caseId}/initial-review")
    @PreAuthorize("@perm.has('case.start_initial_review')")
    public Result<Map<String, Object>> startInitialReview(Actor actor, @PathVariable Long caseId) {
        AuditCaseEntity c = intakeAppService.startInitialReview(actor, caseId);
        int created = riskAppService.runInitialReview(actor, caseId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("caseId", caseId);
        out.put("caseStatus", c.getStatus());
        out.put("riskCreated", created);
        out.put("nextModule", "feedback");
        return Result.ok(out);
    }

    // ── 查询 ────────────────────────────────────────────────────────────

    /**
     * 任务详情 + 物料清单 + 解析状态。
     *
     * <p>刻意<b>不返回</b>风险明细：接收区只负责把任务与材料规范地送入审核系统，
     * 不在该页面提前展示最终风险结论。
     */
    @GetMapping("/cases/{caseId}")
    @PreAuthorize("@perm.has('case.view')")
    public Result<Map<String, Object>> getCase(Actor actor, @PathVariable Long caseId) {
        AuditCaseEntity c = intakeAppService.requireCase(caseId);
        List<MaterialEntity> materials = intakeAppService.listMaterials(caseId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("case", caseView(c));
        out.put("materials", materials.stream().map(this::materialView).toList());
        out.put("parseProgress", parseProgress(materials));
        return Result.ok(out);
    }

    @GetMapping("/materials/{materialId}/versions")
    @PreAuthorize("@perm.has('version.view')")
    public Result<List<MaterialVersionEntity>> listVersions(Actor actor,
                                                            @PathVariable Long materialId) {
        return Result.ok(intakeAppService.listVersions(materialId));
    }

    public record ParseStatusRequest(@NotNull ParseStatus status, String errorCode, String errorMessage) {
    }

    /** 解析服务回写解析状态（真实环境由 MQ 消费者调用） */
    @PostMapping("/materials/{materialId}/parse-status")
    public Result<Void> updateParseStatus(Actor actor, @PathVariable Long materialId,
                                          @RequestBody @Valid ParseStatusRequest req) {
        intakeAppService.updateParseStatus(actor, materialId, req.status(),
                req.errorCode(), req.errorMessage());
        return Result.ok();
    }

    /**
     * 触发解析：把物料版本转成可审核内容 + 证据锚点。
     *
     * <p>真实环境应改为提交异步任务并立即返回 taskId（AGENTS.md 第 14 条要求
     * 展示真实进度与失败重试入口）；此处同步执行以便端到端联调。
     *
     * @return 产出的锚点数量；为 0 说明解析失败或未提取到可审核内容
     */
    @PostMapping("/materials/{materialId}/parse")
    @PreAuthorize("@perm.has('case.parse_retry')")
    public Result<Map<String, Object>> parse(Actor actor, @PathVariable Long materialId) {
        int anchors = parseAppService.parse(actor, materialId);
        MaterialEntity m = materialMapper.selectById(materialId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("materialId", materialId);
        out.put("anchorCount", anchors);
        out.put("parseStatus", m == null ? null : m.getParseStatus());
        out.put("parseErrorMessage", m == null ? null : m.getParseErrorMessage());
        return Result.ok(out);
    }

    // ── 视图组装 ────────────────────────────────────────────────────────

    private Map<String, Object> caseView(AuditCaseEntity c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("caseNo", c.getCaseNo());
        m.put("name", c.getName());
        m.put("projectId", c.getProjectId());
        m.put("submitterId", c.getSubmitterId());
        m.put("ownerId", c.getOwnerId());
        m.put("deadline", c.getDeadline());
        m.put("status", c.getStatus());
        m.put("materialCount", c.getMaterialCount());
        m.put("requirementConfirmed", c.getRequirementConfirmedAt() != null);
        m.put("requirementConfirmedAt", c.getRequirementConfirmedAt());
        m.put("reviewRequirement", c.getReviewRequirement());
        m.put("createdAt", c.getCreatedAt());
        return m;
    }

    private Map<String, Object> materialView(MaterialEntity m) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", m.getId());
        v.put("name", m.getName());
        v.put("materialType", m.getMaterialType());
        v.put("parseStatus", m.getParseStatus());
        v.put("parseErrorCode", m.getParseErrorCode());
        // 解析失败必须明确告诉用户需要重传或补充什么（AGENTS.md 第 4 条）
        v.put("parseErrorMessage", m.getParseErrorMessage());
        v.put("currentVersionId", m.getCurrentVersionId());
        return v;
    }

    /**
     * 解析进度。
     *
     * <p>进度用"已完成物料数 / 总物料数"这种<b>可计量单位</b>表达，
     * 而不是一个凭感觉的百分比——否则后续换成真实进度时前端语义要返工（docs/06 §4.5）。
     */
    private Map<String, Object> parseProgress(List<MaterialEntity> materials) {
        int total = materials.size();
        int done = (int) materials.stream()
                .filter(m -> !"PENDING".equals(m.getParseStatus()) && !"RUNNING".equals(m.getParseStatus()))
                .count();
        int failed = (int) materials.stream()
                .filter(m -> ParseStatus.FAILED.name().equals(m.getParseStatus())).count();
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("total", total);
        p.put("finished", done);
        p.put("failed", failed);
        p.put("percent", total == 0 ? 0 : Math.round(done * 100.0 / total));
        return p;
    }
}
