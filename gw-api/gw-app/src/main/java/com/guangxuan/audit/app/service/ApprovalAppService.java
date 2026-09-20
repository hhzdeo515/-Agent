package com.guangxuan.audit.app.service;

import com.guangxuan.audit.common.enums.CaseStatus;
import com.guangxuan.audit.common.error.DomainException;
import com.guangxuan.audit.common.error.ErrorCode;
import com.guangxuan.audit.common.security.PermCode;
import com.guangxuan.audit.domain.security.Actor;
import com.guangxuan.audit.infra.persistence.entity.AuditCaseEntity;
import com.guangxuan.audit.infra.persistence.entity.LegalSignatureEntity;
import com.guangxuan.audit.infra.persistence.entity.MaterialApprovalEntity;
import com.guangxuan.audit.infra.persistence.entity.MaterialEntity;
import com.guangxuan.audit.infra.persistence.entity.MaterialVersionEntity;
import com.guangxuan.audit.infra.persistence.entity.RiskCaseEntity;
import com.guangxuan.audit.infra.persistence.mapper.AuditCaseMapper;
import com.guangxuan.audit.infra.persistence.mapper.LegalSignatureMapper;
import com.guangxuan.audit.infra.persistence.mapper.MaterialApprovalMapper;
import com.guangxuan.audit.infra.persistence.mapper.MaterialMapper;
import com.guangxuan.audit.infra.persistence.mapper.MaterialVersionMapper;
import com.guangxuan.audit.infra.persistence.mapper.RiskCaseMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 终审区的签名与批准服务。
 *
 * <p>这是全系统权限最严的一组动作（AGENTS.md 第 1、7、13 条）：
 * <ul>
 *   <li>只有法务可签名、可批准；AI 与非法务角色在领域层、数据库层都被拒绝；</li>
 *   <li>批准必须绑定<b>精确版本</b>并留存文件哈希快照——对外批准的文件要能反向追溯到
 *       批准时使用的准确版本，不能只关联到一个可能被覆盖的文件地址；</li>
 *   <li>存在未关闭阻断性风险时不得批准；</li>
 *   <li>撤销批准必须留撤销人与原因。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApprovalAppService {

    private final RiskAppService riskAppService;
    private final ReviewRecordService reviewRecordService;
    private final LegalSignatureMapper signatureMapper;
    private final MaterialApprovalMapper approvalMapper;
    private final MaterialMapper materialMapper;
    private final MaterialVersionMapper versionMapper;
    private final RiskCaseMapper riskCaseMapper;
    private final AuditCaseMapper caseMapper;

    // ── 风险关闭签名 ────────────────────────────────────────────────────

    /**
     * 为关闭风险做法务签名。
     *
     * <p>签名写入后，{@code risk_case} 才可能被置为 CLOSED——
     * 数据库触发器会独立校验签名是否存在，因此顺序不能颠倒。
     */
    @Transactional
    public LegalSignatureEntity signRiskClose(Actor actor, Long riskId, String comment) {
        actor.requireLegal("风险关闭签名");
        actor.requirePermission(PermCode.RISK_SIGN);

        RiskCaseEntity risk = riskAppService.loadEntity(riskId);
        String sigHash = computeSignatureHash(
                "RISK_CLOSE|" + riskId + "|" + actor.userId() + "|" + risk.getCurrentVersionId());

        LegalSignatureEntity sig = new LegalSignatureEntity();
        sig.setRiskCaseId(riskId);
        sig.setSignerId(actor.userId());
        sig.setSignerRole("LEGAL");
        sig.setSignType("RISK_CLOSE");
        sig.setSignatureHash(sigHash);
        sig.setComment(comment);
        sig.setSignedAt(LocalDateTime.now());
        // 触发器 trg_signature_requires_privilege 会再校验签名人角色
        signatureMapper.insert(sig);

        reviewRecordService.append(risk.getCaseId(), riskId, risk.getCurrentVersionId(),
                actor, "SIGN", comment, null, null,
                "{\"signType\":\"RISK_CLOSE\",\"signatureHash\":\"" + sigHash + "\"}");
        log.info("风险 {} 已完成法务签名（signer={}）", risk.getRiskNo(), actor.userId());
        return sig;
    }

    // ── 物料最终批准 ────────────────────────────────────────────────────

    /**
     * 计算批准影响范围，供前端二次确认展示（AGENTS.md 第 13 条：
     * 高风险动作需二次确认并<b>展示影响范围</b>）。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> approvalImpact(Actor actor, Long materialId, Long versionId) {
        actor.requirePermission(PermCode.MATERIAL_APPROVE);

        MaterialEntity material = requireMaterial(materialId);
        MaterialVersionEntity version = requireVersion(versionId);
        int openBlocking = riskCaseMapper.countOpenBlockingRisks(materialId);
        Long closedRisk = riskCaseMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<
                        com.guangxuan.audit.infra.persistence.entity.RiskCaseEntity>()
                        .eq("material_id", materialId).eq("status", "CLOSED"));

        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("materialId", materialId);
        impact.put("materialName", material.getName());
        impact.put("versionId", versionId);
        impact.put("versionLabel", version.getVersionLabel());
        impact.put("fileSha256", version.getFileSha256());
        impact.put("closedRiskCount", closedRisk == null ? 0 : closedRisk);
        impact.put("openBlockingRiskCount", openBlocking);
        impact.put("blockingRiskOpen", openBlocking > 0);
        return impact;
    }

    /**
     * 列出某物料的全部批准记录。
     *
     * <p>撤销批准需要一个 {@code approvalId}，而在此之前没有任何接口能把 id 交出来，
     * 于是"撤销"这个动作在前端无法被真正执行——只能靠写死 id。
     * 因此补这个只读接口，让撤销路径可用。
     */
    @Transactional(readOnly = true)
    public java.util.List<MaterialApprovalEntity> listApprovals(Actor actor, Long materialId) {
        actor.requirePermission(PermCode.MATERIAL_APPROVE);
        return approvalMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<MaterialApprovalEntity>()
                        .eq("material_id", materialId)
                        .orderByDesc("id"));
    }

    /**
     * 标记最终批准版本。
     *
     * <p>AGENTS.md 第 7 条：<b>只有一份物料关联的所有阻断性风险都关闭后</b>，
     * 该物料版本才可以被标记为最终批准版本。因此这里的守卫不是形式上的——
     * 它直接决定了"能不能对外发布"。
     */
    @Transactional
    public MaterialApprovalEntity approveMaterial(Actor actor, Long materialId, Long versionId,
                                                 String comment) {
        actor.requireLegal("标记最终批准版本");
        actor.requirePermission(PermCode.MATERIAL_APPROVE);

        MaterialEntity material = requireMaterial(materialId);
        MaterialVersionEntity version = requireVersion(versionId);

        if (!version.getMaterialId().equals(materialId)) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, "版本不属于该物料");
        }
        int openBlocking = riskCaseMapper.countOpenBlockingRisks(materialId);
        if (openBlocking > 0) {
            throw new DomainException(ErrorCode.BLOCKING_RISK_OPEN,
                    "仍有 " + openBlocking + " 条阻断性风险未关闭，无法批准该物料版本");
        }
        if (approvalMapper.isVersionApproved(materialId, versionId)) {
            throw new DomainException(ErrorCode.ALREADY_APPROVED);
        }

        // 1) 先写批准签名（含批准时哈希快照）
        String sigHash = computeSignatureHash(
                "MATERIAL_APPROVE|" + materialId + "|" + versionId + "|" + version.getFileSha256());
        LegalSignatureEntity sig = new LegalSignatureEntity();
        sig.setMaterialId(materialId);
        sig.setVersionId(versionId);
        sig.setSignerId(actor.userId());
        sig.setSignerRole("LEGAL");
        sig.setSignType("MATERIAL_APPROVE");
        sig.setFileSha256(version.getFileSha256());
        sig.setSignatureHash(sigHash);
        sig.setComment(comment);
        sig.setSignedAt(LocalDateTime.now());
        signatureMapper.insert(sig);

        // 2) 写批准记录，绑定精确版本 + 哈希快照
        MaterialApprovalEntity approval = new MaterialApprovalEntity();
        approval.setMaterialId(materialId);
        approval.setVersionId(versionId);
        approval.setFileSha256(version.getFileSha256());
        approval.setStatus("APPROVED");
        approval.setApprovedBy(actor.userId());
        approval.setApprovedAt(LocalDateTime.now());
        approval.setSignatureId(sig.getId());
        approvalMapper.insert(approval);

        reviewRecordService.append(material.getCaseId(), null, versionId, actor,
                "APPROVE_MATERIAL", comment, null, null,
                "{\"versionId\":" + versionId + ",\"versionLabel\":\"" + version.getVersionLabel()
                        + "\",\"fileSha256\":\"" + version.getFileSha256() + "\"}");

        // 3) 若任务处于终审中，批准后进入已批准
        AuditCaseEntity c = caseMapper.selectById(material.getCaseId());
        if (c != null && CaseStatus.FINAL_REVIEW.name().equals(c.getStatus())) {
            c.setStatus(CaseStatus.APPROVED.name());
            caseMapper.updateById(c);
            reviewRecordService.append(c.getId(), null, null, actor, "CASE_STATUS_CHANGE",
                    "物料版本获批，任务进入已批准", null, null,
                    "{\"event\":\"MATERIAL_APPROVED\",\"to\":\"APPROVED\"}");
        }

        log.info("物料 {} 版本 {} 已获法务批准（approver={}）",
                materialId, version.getVersionLabel(), actor.userId());
        return approval;
    }

    /**
     * 撤销批准。
     *
     * <p>撤销后该物料回到未批准状态。属于高风险动作，必须填写撤销原因并二次确认
     * （AGENTS.md 第 13 条）。
     */
    @Transactional
    public MaterialApprovalEntity revokeApproval(Actor actor, Long materialId, Long approvalId,
                                                String revokeReason) {
        actor.requireLegal("撤销批准");
        actor.requirePermission(PermCode.MATERIAL_REVOKE_APPROVAL);

        if (revokeReason == null || revokeReason.isBlank()) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, "撤销批准必须填写原因");
        }
        MaterialApprovalEntity approval = approvalMapper.selectById(approvalId);
        if (approval == null || !approval.getMaterialId().equals(materialId)) {
            throw new DomainException(ErrorCode.NOT_FOUND, "批准记录不存在");
        }
        if (!"APPROVED".equals(approval.getStatus())) {
            throw new DomainException(ErrorCode.ILLEGAL_TRANSITION, "该批准已被撤销");
        }

        MaterialEntity material = requireMaterial(materialId);

        // 撤销签名（append-only，原批准签名保留不动）
        LegalSignatureEntity sig = new LegalSignatureEntity();
        sig.setMaterialId(materialId);
        sig.setVersionId(approval.getVersionId());
        sig.setSignerId(actor.userId());
        sig.setSignerRole("LEGAL");
        sig.setSignType("REVOKE");
        sig.setFileSha256(approval.getFileSha256());
        sig.setSignatureHash(computeSignatureHash(
                "REVOKE|" + materialId + "|" + approval.getVersionId()
                        + "|" + actor.userId() + "|" + revokeReason));
        sig.setComment(revokeReason);
        sig.setSignedAt(LocalDateTime.now());
        signatureMapper.insert(sig);

        approval.setStatus("REVOKED");
        approval.setRevokedBy(actor.userId());
        approval.setRevokedAt(LocalDateTime.now());
        approval.setRevokeReason(revokeReason);
        approvalMapper.updateById(approval);

        reviewRecordService.append(material.getCaseId(), null, approval.getVersionId(), actor,
                "REVOKE_APPROVAL", revokeReason, null, null,
                "{\"approvalId\":" + approvalId + "}");

        AuditCaseEntity c = caseMapper.selectById(material.getCaseId());
        if (c != null && CaseStatus.APPROVED.name().equals(c.getStatus())) {
            c.setStatus(CaseStatus.FINAL_REVIEW.name());
            caseMapper.updateById(c);
        }
        log.warn("物料 {} 批准已撤销（operator={}）：{}", materialId, actor.userId(), revokeReason);
        return approval;
    }

    // ── 内部 ────────────────────────────────────────────────────────────

    private MaterialEntity requireMaterial(Long materialId) {
        MaterialEntity m = materialMapper.selectById(materialId);
        if (m == null) {
            throw new DomainException(ErrorCode.RESOURCE_NOT_VISIBLE);
        }
        return m;
    }

    private MaterialVersionEntity requireVersion(Long versionId) {
        MaterialVersionEntity v = versionMapper.selectById(versionId);
        if (v == null) {
            throw new DomainException(ErrorCode.RESOURCE_NOT_VISIBLE);
        }
        return v;
    }

    /** 签名哈希：把签名上下文串起来做摘要，用于事后核验签名对象未被替换 */
    private String computeSignatureHash(String context) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(context.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
