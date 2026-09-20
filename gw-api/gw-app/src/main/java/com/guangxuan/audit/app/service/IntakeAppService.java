package com.guangxuan.audit.app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guangxuan.audit.common.enums.CaseStatus;
import com.guangxuan.audit.common.enums.MaterialType;
import com.guangxuan.audit.common.enums.ParseStatus;
import com.guangxuan.audit.common.error.DomainException;
import com.guangxuan.audit.common.error.ErrorCode;
import com.guangxuan.audit.common.security.PermCode;
import com.guangxuan.audit.domain.security.Actor;
import com.guangxuan.audit.infra.persistence.entity.AuditCaseEntity;
import com.guangxuan.audit.infra.persistence.entity.MaterialEntity;
import com.guangxuan.audit.infra.persistence.entity.MaterialVersionEntity;
import com.guangxuan.audit.infra.persistence.mapper.AuditCaseMapper;
import com.guangxuan.audit.infra.persistence.mapper.MaterialMapper;
import com.guangxuan.audit.infra.persistence.mapper.MaterialVersionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 接收区应用服务（AGENTS.md 第 4 条）。
 *
 * <p>职责边界：创建审核任务、接收宣传材料、记录本次审核要求。
 * <b>刻意不做</b>：不提前展示最终风险结论、不生成正式报告、不处理整改版本、不执行审批。
 * 该边界由本类的可写字段与 {@link com.guangxuan.audit.app.service.RiskAppService} 的
 * 职责划分共同保证。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntakeAppService {

    private final AuditCaseMapper caseMapper;
    private final MaterialMapper materialMapper;
    private final MaterialVersionMapper versionMapper;
    private final ReviewRecordService reviewRecordService;
    private final ObjectMapper objectMapper;

    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    // ── 创建审核任务 ────────────────────────────────────────────────────

    @Transactional
    public AuditCaseEntity createCase(Actor actor, Long projectId, String name,
                                      LocalDateTime deadline) {
        actor.requirePermission(PermCode.CASE_CREATE);

        AuditCaseEntity entity = new AuditCaseEntity();
        entity.setCaseNo(generateCaseNo());
        entity.setName(name);
        entity.setProjectId(projectId);
        entity.setSubmitterId(actor.userId());
        entity.setOwnerId(actor.userId());
        entity.setDeadline(deadline);
        entity.setStatus(CaseStatus.DRAFT.name());
        entity.setMaterialCount(0);
        entity.setLockVersion(0);
        caseMapper.insert(entity);

        reviewRecordService.append(entity.getId(), null, null, actor,
                "CASE_STATUS_CHANGE", "创建审核任务", null, null,
                "{\"caseNo\":\"" + entity.getCaseNo() + "\"}");
        log.info("审核任务已创建: {} ({})", entity.getCaseNo(), entity.getId());
        return entity;
    }

    /**
     * 生成业务编号。
     *
     * <p>用"当日序号"而非全局自增：便于人工按批次识别。
     * 并发下依赖 {@code uk_case_no} 唯一键兜底，冲突时由调用方重试（此处简单重试 3 次）。
     */
    private String generateCaseNo() {
        String prefix = "GX-" + LocalDateTime.now().format(NO_FMT) + "-";
        for (int i = 0; i < 3; i++) {
            int seq = caseMapper.nextSequence(prefix);
            String no = prefix + String.format("%04d", seq);
            if (caseMapper.selectCount(
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<AuditCaseEntity>()
                            .eq("case_no", no)) == 0) {
                return no;
            }
        }
        // 极端并发下的兜底：退化为时间戳后缀，保证唯一
        return prefix + System.currentTimeMillis() % 100000;
    }

    // ── 上传物料 ────────────────────────────────────────────────────────

    /**
     * 登记一个物料版本。
     *
     * <p>关键幂等行为：同一物料下若已存在相同 sha256 的版本，直接复用而不新建
     * （数据库 {@code uk_version_sha} 唯一键是最终保证，AGENTS.md 第 14 条）。
     *
     * @param sha256 MinIO 中对象的 sha256；调用方负责计算与存储
     */
    @Transactional
    public MaterialVersionEntity uploadVersion(Actor actor, Long caseId, Long materialId,
                                               String fileName, MaterialType type,
                                               String objectKey, String sha256, long fileSize,
                                               String mimeType, String uploadReason) {
        actor.requirePermission(PermCode.CASE_UPLOAD);

        AuditCaseEntity auditCase = requireCase(caseId);
        if (!CaseStatus.valueOf(auditCase.getStatus()).allowsUpload()) {
            throw new DomainException(ErrorCode.ILLEGAL_CASE_STATUS,
                    "当前任务状态不允许上传物料：" + auditCase.getStatus());
        }

        // 复用或新建物料
        MaterialEntity material;
        if (materialId == null) {
            material = new MaterialEntity();
            material.setCaseId(caseId);
            material.setName(fileName);
            material.setMaterialType(type.name());
            material.setParseStatus(ParseStatus.PENDING.name());
            materialMapper.insert(material);
        } else {
            material = materialMapper.selectById(materialId);
            if (material == null) {
                throw new DomainException(ErrorCode.NOT_FOUND, "物料不存在：" + materialId);
            }
        }

        // 幂等：同哈希已存在则直接复用
        MaterialVersionEntity existing = versionMapper.findBySha(material.getId(), sha256);
        if (existing != null) {
            log.info("相同文件已存在，复用版本 {}（物料 {}）", existing.getVersionLabel(), material.getId());
            return existing;
        }

        int nextNo = versionMapper.maxVersionNo(material.getId()) + 1;
        MaterialVersionEntity v = new MaterialVersionEntity();
        v.setMaterialId(material.getId());
        v.setVersionNo(nextNo);
        v.setVersionLabel("V" + nextNo);
        v.setFileObjectKey(objectKey);
        v.setFileSha256(sha256);
        v.setFileSize(fileSize);
        v.setMimeType(mimeType);
        v.setUploaderId(actor.userId());
        // V2+ 必填修改说明，数据库 CHECK 约束会兜底
        v.setUploadReason(nextNo == 1 ? (uploadReason == null ? "首次上传" : uploadReason) : uploadReason);
        v.setParentVersionId(material.getCurrentVersionId());
        versionMapper.insert(v);

        material.setCurrentVersionId(v.getId());
        material.setParseStatus(ParseStatus.PENDING.name());
        materialMapper.updateById(material);

        reviewRecordService.append(caseId, null, v.getId(), actor, "UPLOAD_VERSION",
                "上传 " + v.getVersionLabel() + (uploadReason == null ? "" : "：" + uploadReason),
                null, null,
                "{\"versionLabel\":\"" + v.getVersionLabel() + "\",\"sha256\":\"" + sha256 + "\"}");

        // 任务进入解析中
        if (CaseStatus.DRAFT.name().equals(auditCase.getStatus())) {
            transitionCase(actor, auditCase, CaseStatus.PARSING, "MATERIAL_UPLOADED", "物料上传触发解析");
        }
        refreshMaterialCount(caseId);
        return v;
    }

    // ── 审核要求 ────────────────────────────────────────────────────────

    /**
     * 确认本批任务的审核要求。
     *
     * <p>AGENTS.md 第 4 条：系统可以提供常用审核要求模板，但<b>不得把模板内容默认为
     * 本次任务的正式要求</b>，用户选择或确认后才生效。因此"选了模板"与"要求生效"
     * 是两件事——本方法写入 {@code requirementConfirmedBy/At} 后，要求才算生效，
     * 才允许启动 AI 初审。
     */
    @Transactional
    public void confirmRequirement(Actor actor, Long caseId, Object requirement,
                                   String templateCode) {
        actor.requirePermission(PermCode.CASE_REQUIREMENT_CONFIRM);
        AuditCaseEntity c = requireCase(caseId);

        try {
            c.setReviewRequirement(objectMapper.writeValueAsString(requirement));
        } catch (Exception e) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, "审核要求格式不合法");
        }
        c.setRequirementTemplateCode(templateCode);
        c.setRequirementConfirmedBy(actor.userId());
        c.setRequirementConfirmedAt(LocalDateTime.now());
        caseMapper.updateById(c);

        reviewRecordService.append(caseId, null, null, actor, "CASE_STATUS_CHANGE",
                "确认审核要求", null, null,
                "{\"templateCode\":\"" + (templateCode == null ? "" : templateCode) + "\"}");

        // 确认要求可能正是"最后一个待满足条件"（解析早已完成），因此这里必须尝试推进；
        // 否则任务会一直卡在 PARSING，点"开始 AI 初审"会报"当前状态不允许"。
        tryAdvanceToReady(actor, caseId);
    }

    // ── 启动 AI 初审 ────────────────────────────────────────────────────

    /**
     * 启动 AI 初审：任务进入 {@code AI_REVIEWING}。
     *
     * <p>两个硬前置：
     * <ol>
     *   <li>审核要求已确认（否则模板默认生效，违反 AGENTS.md 第 4 条）；</li>
     *   <li>至少有一个物料可用于初审（全部解析失败时应阻止并明确告知）。</li>
     * </ol>
     */
    @Transactional
    public AuditCaseEntity startInitialReview(Actor actor, Long caseId) {
        actor.requirePermission(PermCode.CASE_START_INITIAL_REVIEW);
        AuditCaseEntity c = requireCase(caseId);

        if (c.getRequirementConfirmedAt() == null) {
            throw new DomainException(ErrorCode.REQUIREMENT_NOT_CONFIRMED);
        }
        int reviewable = caseMapper.countReviewableMaterials(caseId);
        if (reviewable == 0) {
            throw new DomainException(ErrorCode.NO_REVIEWABLE_MATERIAL,
                    "没有可用于初审的物料（全部解析失败），请先重传或补充材料");
        }
        if (!CaseStatus.READY_FOR_REVIEW.name().equals(c.getStatus())
                && !CaseStatus.FEEDBACK_PENDING.name().equals(c.getStatus())) {
            throw new DomainException(ErrorCode.ILLEGAL_CASE_STATUS,
                    "当前状态不允许启动初审：" + c.getStatus());
        }

        transitionCase(actor, c, CaseStatus.AI_REVIEWING, "START_INITIAL_REVIEW",
                "启动 AI 初审，可审物料 " + reviewable + " 个");
        return c;
    }

    // ── 解析状态回写 ────────────────────────────────────────────────────

    @Transactional
    public void updateParseStatus(Actor actor, Long materialId, ParseStatus status,
                                  String errorCode, String errorMessage) {
        MaterialEntity m = materialMapper.selectById(materialId);
        if (m == null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "物料不存在：" + materialId);
        }
        m.setParseStatus(status.name());
        m.setParseErrorCode(errorCode);
        m.setParseErrorMessage(errorMessage);
        materialMapper.updateById(m);

        // 解析失败必须明确告诉用户需要重传或补充什么（AGENTS.md 第 4 条）
        if (status == ParseStatus.FAILED) {
            log.warn("物料解析失败: materialId={} code={} msg={}", materialId, errorCode, errorMessage);
        }
    }

    /**
     * 全部物料解析结束后，把任务推进到待启动初审。
     *
     * <p>解析失败的物料不阻塞整体推进——它们会被单独列出且不计入通过率分母，
     * 但必须有成功或部分成功的物料，否则任务无法继续。
     */
    @Transactional
    public void onAllParseFinished(Actor actor, Long caseId) {
        tryAdvanceToReady(actor, caseId);
    }

    /**
     * 尝试把任务从 PARSING 推进到 READY_FOR_REVIEW。
     *
     * <p>推进需要<b>两个条件同时满足</b>：① 全部物料解析结束；② 审核要求已确认。
     * 这两个条件由不同动作分别达成（解析、确认要求），前后顺序也不固定，
     * 因此两侧都要调用本方法——只在其中一侧判断，任务就会卡在 PARSING。
     */
    @Transactional
    public void tryAdvanceToReady(Actor actor, Long caseId) {
        AuditCaseEntity c = requireCase(caseId);
        if (!CaseStatus.PARSING.name().equals(c.getStatus())) {
            return;
        }
        if (c.getRequirementConfirmedAt() == null) {
            log.debug("任务 {} 尚未确认审核要求，暂不推进", caseId);
            return;
        }
        List<MaterialEntity> materials = listMaterials(caseId);
        boolean allSettled = !materials.isEmpty() && materials.stream().allMatch(m ->
                !ParseStatus.PENDING.name().equals(m.getParseStatus())
                        && !ParseStatus.RUNNING.name().equals(m.getParseStatus()));
        if (!allSettled) {
            log.debug("任务 {} 仍有物料在解析中，暂不推进", caseId);
            return;
        }
        transitionCase(actor, c, CaseStatus.READY_FOR_REVIEW, "PARSE_ALL_FINISHED",
                "全部物料解析结束且审核要求已确认");
    }

    // ── 查询 ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public AuditCaseEntity requireCase(Long caseId) {
        AuditCaseEntity c = caseMapper.selectById(caseId);
        if (c == null) {
            // 跨项目访问统一按"不存在"处理，避免通过状态码探测资源（04 文档 T-04 结论）
            throw new DomainException(ErrorCode.RESOURCE_NOT_VISIBLE);
        }
        return c;
    }

    @Transactional(readOnly = true)
    public List<MaterialEntity> listMaterials(Long caseId) {
        return materialMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<MaterialEntity>()
                        .eq("case_id", caseId).orderByAsc("id"));
    }

    @Transactional(readOnly = true)
    public List<MaterialVersionEntity> listVersions(Long materialId) {
        return versionMapper.listByMaterial(materialId);
    }

    private void refreshMaterialCount(Long caseId) {
        Long cnt = materialMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<MaterialEntity>()
                        .eq("case_id", caseId));
        AuditCaseEntity c = caseMapper.selectById(caseId);
        if (c != null) {
            c.setMaterialCount(cnt == null ? 0 : cnt.intValue());
            caseMapper.updateById(c);
        }
    }

    /** 任务状态跃迁（Case 级不走 RiskStateMachine，但同样必须留痕） */
    @Transactional
    public void transitionCase(Actor actor, AuditCaseEntity c, CaseStatus target,
                               String event, String reason) {
        CaseStatus from = CaseStatus.valueOf(c.getStatus());
        if (from == target) {
            return;
        }
        c.setStatus(target.name());
        caseMapper.updateById(c);
        reviewRecordService.append(c.getId(), null, null, actor, "CASE_STATUS_CHANGE",
                reason, null, null,
                "{\"event\":\"" + event + "\",\"from\":\"" + from + "\",\"to\":\"" + target + "\"}");
        log.info("任务 {} 状态流转 {} → {}（{}）", c.getCaseNo(), from, target, event);
    }
}
