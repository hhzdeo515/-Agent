package com.guangxuan.audit.domain.risk;

import com.guangxuan.audit.common.enums.RecommendedStatus;
import com.guangxuan.audit.common.enums.RegionHint;
import com.guangxuan.audit.common.enums.RiskLevel;
import com.guangxuan.audit.common.enums.RiskStatus;
import com.guangxuan.audit.common.enums.RiskType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 风险记录领域模型（对应 {@code risk_case} 表）。
 *
 * <p>刻意做成"数据 + 由 {@link RiskStateMachine} 施加的状态跃迁"：
 * 状态变更<b>不允许</b>直接 set，必须经过状态机，以便统一校验跃迁合法性、权限与业务守卫，
 * 并保证每次都留下审计记录（AGENTS.md 第 8 条）。
 *
 * <p>字段命名与 01 文档 §4.6 的 DDL 对齐。
 */
public class RiskCase {

    private Long id;
    private String riskNo;
    private Long caseId;
    private Long materialId;

    /** 首次发现该风险的版本，永不改变（AGENTS.md 第 9 条） */
    private Long firstVersionId;
    /** 当前正在处理的版本 */
    private Long currentVersionId;

    private RiskType riskType;
    /** 潜在影响 */
    private RiskLevel riskLevel;
    /** AI 对识别结果的把握，与 riskLevel 是独立维度 */
    private BigDecimal confidence;
    private RiskStatus status;

    private String riskText;
    private String locationDesc;
    private RegionHint regionHint;
    private String reason;

    /** 知识库依据的 ID 数组（防虚构：只允许引用检索到的真实 ID） */
    private List<Long> ruleRefs;
    /** 模型想引用但检索不到依据的内容；非空则强制转人工 */
    private List<String> unsupportedClaims;

    private String suggestion;
    private String recommendedCopy;
    private String requiredEvidence;

    /** 是否阻断性风险：阻断性未关闭则物料版本不得批准 */
    private boolean blocked;

    private Long assigneeId;
    private LocalDateTime remediationDueAt;
    private Long parentRiskId;

    private String dedupKey;
    private String modelId;
    private String pipelineVersion;
    private String promptVersion;
    private String rulesetVersion;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime closedAt;
    private Integer lockVersion;

    /** 瞬态：是否已存在 RISK_CLOSE 签名，由仓储在加载时填充 */
    private boolean hasLegalCloseSignature;

    /**
     * 领域对象的创建交给持久化层/应用层（它们负责把数据库行映射进来）。
     *
     * <p>注意：{@link #applyStatus} 仍然是包私有的——<b>状态只能由
     * {@link RiskStateMachine} 修改</b>，这是本类最重要的不变式。
     * 可以创建实例，但不能绕过状态机改状态。
     */
    public RiskCase() {
    }

    // ── 状态跃迁（只允许通过 RiskStateMachine 调用） ──────────────────

    void applyStatus(RiskStatus target) {
        this.status = target;
        if (target == RiskStatus.CLOSED) {
            this.closedAt = LocalDateTime.now();
        }
    }

    /**
     * 标记"已存在 RISK_CLOSE 签名"。
     *
     * <p>由应用层在加载时根据数据库查询结果调用——签名是否存在必须是查出来的事实，
     * 不能由应用层"认为"或让调用方传入 true。
     */
    public void markLegalCloseSignaturePresent() {
        this.hasLegalCloseSignature = true;
    }

    // ── 业务判定 ─────────────────────────────────────────────────

    /** 是否需要人工判断（AGENTS.md 第 5 条：待人工判断的风险只有法务处理后才能流转） */
    public boolean needsHumanDecision() {
        return status != null && status.isPendingHuman();
    }

    /** 是否仍占用"阻断性未关闭"名额 */
    public boolean isBlockingOpen() {
        return blocked && !status.isResolved();
    }

    /**
     * 依据充分性检查：无依据或存在无法支撑的引用时，不得作为确定结论。
     *
     * <p>对应 AGENTS.md 第 11 条"模型无法获取关键事实、证明材料或适用规则时，
     * 应输出待人工判断或要求补充材料，而不是为了完成流程编造确定答案"。
     */
    public boolean lacksBasis() {
        return (ruleRefs == null || ruleRefs.isEmpty())
                || (unsupportedClaims != null && !unsupportedClaims.isEmpty());
    }

    // ── getter / setter ─────────────────────────────────────────

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRiskNo() {
        return riskNo;
    }

    public void setRiskNo(String riskNo) {
        this.riskNo = riskNo;
    }

    public Long getCaseId() {
        return caseId;
    }

    public void setCaseId(Long caseId) {
        this.caseId = caseId;
    }

    public Long getMaterialId() {
        return materialId;
    }

    public void setMaterialId(Long materialId) {
        this.materialId = materialId;
    }

    public Long getFirstVersionId() {
        return firstVersionId;
    }

    public void setFirstVersionId(Long firstVersionId) {
        this.firstVersionId = firstVersionId;
    }

    public Long getCurrentVersionId() {
        return currentVersionId;
    }

    public void setCurrentVersionId(Long currentVersionId) {
        this.currentVersionId = currentVersionId;
    }

    public RiskType getRiskType() {
        return riskType;
    }

    public void setRiskType(RiskType riskType) {
        this.riskType = riskType;
    }

    public RiskLevel getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(RiskLevel riskLevel) {
        this.riskLevel = riskLevel;
    }

    public BigDecimal getConfidence() {
        return confidence;
    }

    public void setConfidence(BigDecimal confidence) {
        this.confidence = confidence;
    }

    public RiskStatus getStatus() {
        return status;
    }

    public String getRiskText() {
        return riskText;
    }

    public void setRiskText(String riskText) {
        this.riskText = riskText;
    }

    public String getLocationDesc() {
        return locationDesc;
    }

    public void setLocationDesc(String locationDesc) {
        this.locationDesc = locationDesc;
    }

    public RegionHint getRegionHint() {
        return regionHint;
    }

    public void setRegionHint(RegionHint regionHint) {
        this.regionHint = regionHint;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public List<Long> getRuleRefs() {
        return ruleRefs;
    }

    public void setRuleRefs(List<Long> ruleRefs) {
        this.ruleRefs = ruleRefs;
    }

    public List<String> getUnsupportedClaims() {
        return unsupportedClaims;
    }

    public void setUnsupportedClaims(List<String> unsupportedClaims) {
        this.unsupportedClaims = unsupportedClaims;
    }

    public String getSuggestion() {
        return suggestion;
    }

    public void setSuggestion(String suggestion) {
        this.suggestion = suggestion;
    }

    public String getRecommendedCopy() {
        return recommendedCopy;
    }

    public void setRecommendedCopy(String recommendedCopy) {
        this.recommendedCopy = recommendedCopy;
    }

    public String getRequiredEvidence() {
        return requiredEvidence;
    }

    public void setRequiredEvidence(String requiredEvidence) {
        this.requiredEvidence = requiredEvidence;
    }

    public boolean isBlocked() {
        return blocked;
    }

    public void setBlocked(boolean blocked) {
        this.blocked = blocked;
    }

    public Long getAssigneeId() {
        return assigneeId;
    }

    public void setAssigneeId(Long assigneeId) {
        this.assigneeId = assigneeId;
    }

    public LocalDateTime getRemediationDueAt() {
        return remediationDueAt;
    }

    public void setRemediationDueAt(LocalDateTime remediationDueAt) {
        this.remediationDueAt = remediationDueAt;
    }

    public Long getParentRiskId() {
        return parentRiskId;
    }

    public void setParentRiskId(Long parentRiskId) {
        this.parentRiskId = parentRiskId;
    }

    public String getDedupKey() {
        return dedupKey;
    }

    public void setDedupKey(String dedupKey) {
        this.dedupKey = dedupKey;
    }

    public String getModelId() {
        return modelId;
    }

    public void setModelId(String modelId) {
        this.modelId = modelId;
    }

    public String getPipelineVersion() {
        return pipelineVersion;
    }

    public void setPipelineVersion(String pipelineVersion) {
        this.pipelineVersion = pipelineVersion;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public void setPromptVersion(String promptVersion) {
        this.promptVersion = promptVersion;
    }

    public String getRulesetVersion() {
        return rulesetVersion;
    }

    public void setRulesetVersion(String rulesetVersion) {
        this.rulesetVersion = rulesetVersion;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public LocalDateTime getClosedAt() {
        return closedAt;
    }

    public Integer getLockVersion() {
        return lockVersion;
    }

    public void setLockVersion(Integer lockVersion) {
        this.lockVersion = lockVersion;
    }

    public boolean isHasLegalCloseSignature() {
        return hasLegalCloseSignature;
    }

    /** 供测试与持久化层使用 */
    public void setStatusDirectly(RiskStatus status) {
        this.status = status;
    }

    public static RecommendedStatus toRecommended(RiskStatus status) {
        return switch (status) {
            case OPEN -> RecommendedStatus.OPEN;
            case PENDING_LEGAL_DECISION -> RecommendedStatus.PENDING_LEGAL_DECISION;
            case AWAITING_EVIDENCE -> RecommendedStatus.AWAITING_EVIDENCE;
            default -> null;
        };
    }
}
