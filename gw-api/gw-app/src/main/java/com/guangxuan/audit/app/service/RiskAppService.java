package com.guangxuan.audit.app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guangxuan.audit.common.enums.MaterialType;
import com.guangxuan.audit.common.enums.ParseStatus;
import com.guangxuan.audit.common.enums.RegionHint;
import com.guangxuan.audit.common.enums.RiskLevel;
import com.guangxuan.audit.common.enums.RiskStatus;
import com.guangxuan.audit.common.enums.RiskType;
import com.guangxuan.audit.common.error.DomainException;
import com.guangxuan.audit.common.error.ErrorCode;
import com.guangxuan.audit.common.security.PermCode;
import com.guangxuan.audit.domain.ai.RiskDraft;
import com.guangxuan.audit.domain.ai.RiskDraftValidator;
import com.guangxuan.audit.domain.port.AiInferencePort;
import com.guangxuan.audit.domain.risk.ReviewScope;
import com.guangxuan.audit.domain.risk.RiskCase;
import com.guangxuan.audit.domain.risk.RiskStateMachine;
import com.guangxuan.audit.domain.security.Actor;
import com.guangxuan.audit.infra.persistence.entity.EvidenceAnchorEntity;
import com.guangxuan.audit.infra.persistence.entity.MaterialEntity;
import com.guangxuan.audit.infra.persistence.entity.MaterialVersionEntity;
import com.guangxuan.audit.infra.persistence.entity.ReviewRecordEntity;
import com.guangxuan.audit.infra.persistence.entity.RiskCaseEntity;
import com.guangxuan.audit.infra.persistence.mapper.AuditCaseMapper;
import com.guangxuan.audit.infra.persistence.mapper.EvidenceAnchorMapper;
import com.guangxuan.audit.infra.persistence.mapper.MaterialMapper;
import com.guangxuan.audit.infra.persistence.mapper.MaterialVersionMapper;
import com.guangxuan.audit.infra.persistence.mapper.ReviewRecordMapper;
import com.guangxuan.audit.infra.persistence.mapper.RiskCaseMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 反馈区与终审区的风险应用服务。
 *
 * <p><b>模块边界</b>（AGENTS.md 第 5、7 条）：
 * <ul>
 *   <li>反馈区职责：第一次 AI 全量初审、风险分类与定位、法务人工判断。
 *       它回答的是"这一批材料第一次审核发现了什么"，<b>不</b>管理 V1/V2/V3、不做 Diff、
 *       不做最终审批与关闭。</li>
 *   <li>终审区职责：已确认风险的整改、版本推进、AI 复审、终审签名与关闭。
 *       主要对象是风险记录，<b>不</b>重新对所有物料执行一次完整初审。</li>
 * </ul>
 * 二者共用本服务，但方法划分严格对应上述边界。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RiskAppService {

    private final RiskCaseMapper riskCaseMapper;
    private final MaterialMapper materialMapper;
    private final MaterialVersionMapper versionMapper;
    private final EvidenceAnchorMapper anchorMapper;
    private final ReviewRecordMapper reviewRecordMapper;
    private final ReviewRecordService reviewRecordService;
    private final AuditCaseMapper caseMapper;
    private final AiInferencePort aiInferencePort;
    private final ObjectMapper objectMapper;

    @Value("${gw.ai.pipeline-version:0.1.0}")
    private String pipelineVersion;
    @Value("${gw.ai.prompt-version:0.1.0}")
    private String promptVersion;
    @Value("${gw.ai.ruleset-version:0.1.0}")
    private String rulesetVersion;
    @Value("${gw.ai.models.vision:unknown}")
    private String visionModelId;

    // ════════════════════════════════════════════════════════════════════
    // 反馈区：AI 全量初审
    // ════════════════════════════════════════════════════════════════════

    /**
     * 对某 Case 下全部可审物料执行第一次 AI 初审，产出 Risk Case。
     *
     * <p>关键设计（docs/03 §7.2）：候选识别与风险判断<b>分两次调用</b>。
     * 因为两者目标相反——前者要宽召回（宁可多出待人工，不可漏），
     * 后者要收紧误报。合成一次会让两个目标互相污染。
     *
     * @return 本次新建的 Risk Case 数量
     */
    @Transactional
    public int runInitialReview(Actor actor, Long caseId) {
        List<MaterialEntity> materials = materialMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<MaterialEntity>()
                        .eq("case_id", caseId));
        int created = 0;

        for (MaterialEntity m : materials) {
            ParseStatus ps = ParseStatus.valueOf(m.getParseStatus());
            // 解析失败的物料不得进入正式初审（AGENTS.md 第 4 条），
            // 且不计入通过率分母，单独列出
            if (!ps.isReviewable()) {
                continue;
            }
            Long versionId = m.getCurrentVersionId();
            if (versionId == null) {
                continue;
            }
            created += reviewOneMaterial(actor, m, versionId, ps);
        }

        // 初审完成 → 任务进入"反馈区待法务判断"。
        // 若不推进，任务会一直停在 AI_REVIEWING：既不能在反馈区正常流转，
        // 也会阻塞整改阶段的新版本上传（CaseStatus.allowsUpload 依赖状态）。
        var c = caseMapper.selectById(caseId);
        if (c != null && com.guangxuan.audit.common.enums.CaseStatus.AI_REVIEWING.name()
                .equals(c.getStatus())) {
            c.setStatus(com.guangxuan.audit.common.enums.CaseStatus.FEEDBACK_PENDING.name());
            caseMapper.updateById(c);
            reviewRecordService.append(caseId, null, null, actor, "CASE_STATUS_CHANGE",
                    "AI 初审完成，进入反馈区待法务判断", null, null,
                    "{\"event\":\"AI_REVIEW_COMPLETED\",\"to\":\"FEEDBACK_PENDING\","
                            + "\"riskCreated\":" + created + "}");
        }

        log.info("AI 初审完成: caseId={} 新建风险 {} 条", caseId, created);
        return created;
    }

    private int reviewOneMaterial(Actor actor, MaterialEntity material, Long versionId,
                                  ParseStatus parseStatus) {
        List<EvidenceAnchorEntity> anchors = anchorMapper.listByVersion(versionId);
        if (anchors.isEmpty()) {
            log.warn("物料 {} 版本 {} 无可用锚点，跳过初审", material.getId(), versionId);
            return 0;
        }

        // 传给模型的锚点清单：模型只能引用清单内的 ID
        List<AiInferencePort.AnchorLine> lines = anchors.stream()
                .map(a -> new AiInferencePort.AnchorLine(
                        a.getAnchorId(), a.getAnchorType(), a.getText(), null, null))
                .toList();

        String content = anchors.stream()
                .map(EvidenceAnchorEntity::getText)
                .filter(t -> t != null && !t.isBlank())
                .reduce("", (x, y) -> x + "\n" + y);

        // 阶段 2：候选风险识别（宽召回）
        List<RiskDraft> candidates = aiInferencePort.identifyCandidates(
                new AiInferencePort.CandidateRequest(
                        material.getMaterialType(), content, lines, null));

        // 阶段 3：规则与证据检索（此处 Mock 适配器返回空；真实实现走
        // text-embedding-v4 的 dense+sparse 混合检索 + qwen3-rerank 重排）
        List<AiInferencePort.RetrievedRule> retrievedRules = List.of();

        // 阶段 4：风险判断
        List<RiskDraft> judged = aiInferencePort.judge(
                new AiInferencePort.JudgeRequest(candidates, retrievedRules, null, null));

        // 阶段 5：服务端校验（唯一可信关口）
        Set<String> validAnchorIds = new HashSet<>(
                anchors.stream().map(EvidenceAnchorEntity::getAnchorId).toList());
        Set<Long> retrievedKbIds = new HashSet<>(
                retrievedRules.stream().map(AiInferencePort.RetrievedRule::kbItemVersionId).toList());

        RiskDraftValidator.Result validation = RiskDraftValidator.validate(judged,
                RiskDraftValidator.ValidationContext.of(validAnchorIds, retrievedKbIds, parseStatus));

        int created = 0;
        for (RiskDraftValidator.ValidatedDraft vd : validation.drafts()) {
            if (persistRisk(actor, material, versionId, vd)) {
                created++;
            }
        }

        reviewRecordService.append(material.getCaseId(), null, versionId, actor,
                "AI_INITIAL_REVIEW",
                String.format("AI 初审完成：候选 %d 条，校验后入库 %d 条，校验提示 %d 项",
                        candidates.size(), created, validation.totalViolations()),
                null, null,
                "{\"candidates\":" + candidates.size() + ",\"created\":" + created
                        + ",\"violations\":" + validation.totalViolations() + "}");
        return created;
    }

    /**
     * 落库一条风险；若 dedup_key 已存在则跳过（防重复建单，AGENTS.md 第 14 条）。
     */
    private boolean persistRisk(Actor actor, MaterialEntity material, Long versionId,
                               RiskDraftValidator.ValidatedDraft vd) {
        RiskDraft d = vd.draft();

        String primaryAnchor = d.location().anchorIds().isEmpty()
                ? "NONE" : d.location().anchorIds().get(0);
        String dedupKey = sha256(versionId + "|" + d.riskType() + "|" + primaryAnchor
                + "|" + normalize(d.riskText()));

        Long exists = riskCaseMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<RiskCaseEntity>()
                        .eq("dedup_key", dedupKey));
        if (exists != null && exists > 0) {
            log.debug("风险已存在，跳过（dedupKey={}）", dedupKey);
            return false;
        }

        RiskLevel level = safeEnum(RiskLevel.class, d.riskLevel(), RiskLevel.LOW);
        RiskType type = safeEnum(RiskType.class, d.riskType(), RiskType.OTHER);
        RiskStatus status = safeEnum(RiskStatus.class, d.recommendedStatus(), RiskStatus.OPEN);

        RiskCaseEntity e = new RiskCaseEntity();
        e.setRiskNo(generateRiskNo(material.getCaseId()));
        e.setCaseId(material.getCaseId());
        e.setMaterialId(material.getId());
        e.setFirstVersionId(versionId);
        e.setCurrentVersionId(versionId);
        e.setRiskType(type.name());
        e.setRiskLevel(level.name());
        e.setConfidence(BigDecimal.valueOf(d.confidence()).setScale(4, RoundingMode.HALF_UP));
        e.setStatus(status.name());
        e.setRiskText(d.riskText());
        e.setLocationDesc(d.location() == null ? null : d.location().locationDesc());
        e.setRegionHint(d.location() == null ? null
                : safeEnum(RegionHint.class, d.location().regionHint(), RegionHint.NOT_APPLICABLE).name());
        e.setReason(d.reason());
        e.setRuleRefs(toJson(d.ruleReferences().stream()
                .map(RiskDraft.RuleReference::kbItemVersionId).toList()));
        e.setEvidenceRefs(toJson(d.evidenceReferences()));
        e.setUnsupportedClaims(toJson(d.unsupportedClaims()));
        e.setSuggestion(d.suggestion());
        e.setRecommendedCopy(d.recommendedCopy());
        e.setRequiredEvidence(d.requiredEvidence());
        // 阻断性由风险等级决定：HIGH/MEDIUM 阻断上线，LOW 仅提示
        e.setBlocked(level.isBlocking());
        e.setDedupKey(dedupKey);
        e.setModelId(visionModelId);
        e.setPipelineVersion(pipelineVersion);
        e.setPromptVersion(promptVersion);
        e.setRulesetVersion(rulesetVersion);
        e.setLockVersion(0);
        riskCaseMapper.insert(e);

        // 锚点引用：复合外键会校验锚点真实存在于该版本
        for (String anchorId : d.location().anchorIds()) {
            insertAnchorRef(e.getId(), anchorId, versionId,
                    anchorId.equals(primaryAnchor) ? "PRIMARY" : "SUPPORTING");
        }

        log.debug("新建风险 {}（{} / {} / {}）{}",
                e.getRiskNo(), type, level, status, vd.forcedToHuman() ? "[已强制转人工]" : "");
        return true;
    }

    private void insertAnchorRef(Long riskCaseId, String anchorId, Long versionId, String role) {
        // 用 MyBatis-Plus 的通用插入（risk_anchor_ref 无对应实体，走自定义 SQL 亦可）
        anchorMapper.insertAnchorRef(riskCaseId, anchorId, versionId, role);
    }

    private String generateRiskNo(Long caseId) {
        Long cnt = riskCaseMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<RiskCaseEntity>()
                        .eq("case_id", caseId));
        return "RK-" + caseId + "-" + String.format("%03d", (cnt == null ? 0 : cnt) + 1);
    }

    // ════════════════════════════════════════════════════════════════════
    // 反馈区：法务人工判断
    // ════════════════════════════════════════════════════════════════════

    /** 确认风险 */
    @Transactional
    public RiskCaseEntity confirm(Actor actor, Long riskId, String opinion) {
        RiskCase risk = loadDomain(riskId);
        RiskStateMachine.TransitionResult r = RiskStateMachine.confirm(risk, actor, opinion);
        applyAndRecord(risk, r, actor);
        return loadEntity(riskId);
    }

    /**
     * 标记误判。
     *
     * <p>记录<b>不能删除</b>，必须保存法务理由，作为后续模型评测和规则优化数据
     * （AGENTS.md 第 5 条）。理由必填同时由领域守卫与数据库 CHECK 约束双重保证。
     */
    @Transactional
    public RiskCaseEntity markFalsePositive(Actor actor, Long riskId, String opinion) {
        RiskCase risk = loadDomain(riskId);
        RiskStateMachine.TransitionResult r = RiskStateMachine.markFalsePositive(risk, actor, opinion);
        applyAndRecord(risk, r, actor);
        return loadEntity(riskId);
    }

    /** 要求补充证明材料 */
    @Transactional
    public RiskCaseEntity requestEvidence(Actor actor, Long riskId, String requiredEvidence) {
        RiskCase risk = loadDomain(riskId);
        RiskStateMachine.TransitionResult r =
                RiskStateMachine.requestEvidence(risk, actor, requiredEvidence);
        applyAndRecord(risk, r, actor);
        return loadEntity(riskId);
    }

    /** 转入整改（进入终审区）。必须指定责任方，否则整改任务无人承接。 */
    @Transactional
    public RiskCaseEntity toRemediation(Actor actor, Long riskId, Long assigneeId,
                                        LocalDateTime dueAt, String note) {
        RiskCase risk = loadDomain(riskId);
        RiskStateMachine.TransitionResult r =
                RiskStateMachine.toRemediation(risk, actor, assigneeId, dueAt, note);
        applyAndRecord(risk, r, actor);
        return loadEntity(riskId);
    }

    // ════════════════════════════════════════════════════════════════════
    // 终审区：复审与关闭
    // ════════════════════════════════════════════════════════════════════

    /**
     * 启动 AI 复审。
     *
     * <p>{@code AGENTS.md} 第 7 条要求"不得把终审区重新做成一次无差别的全量初审"，
     * 因此范围参数被强制校验：{@link ReviewScope#FULL} 直接拒绝，
     * 且实际范围会写入审计记录——这样"本次没有做全量扫描"是有据可查的。
     */
    @Transactional
    public RiskCaseEntity startRereview(Actor actor, Long riskId, ReviewScope scope) {
        RiskCase risk = loadDomain(riskId);
        RiskStateMachine.TransitionResult r = RiskStateMachine.startRereview(risk, actor, scope);
        applyAndRecord(risk, r, actor);
        return loadEntity(riskId);
    }

    /**
     * 完成 AI 复审（回答三个问题：原风险是否解决、是否仍有剩余风险、是否产生新风险）。
     *
     * <p><b>若发现新增风险，必须新建关联 Risk Case</b>（{@code parentRiskId} 指向本条），
     * 而不是悄悄改变原风险的含义。
     */
    @Transactional
    public RiskCaseEntity completeRereview(Actor actor, Long riskId,
                                          boolean originalResolved, boolean remainingRisk,
                                          String summary) {
        RiskCase risk = loadDomain(riskId);
        RiskStateMachine.TransitionResult r = RiskStateMachine.completeRereview(
                risk, actor, originalResolved, remainingRisk, summary);
        applyAndRecord(risk, r, actor);
        return loadEntity(riskId);
    }

    /**
     * 法务签名并关闭风险。
     *
     * <p>这是全系统权限最严的动作，三重保证：
     * <ol>
     *   <li>领域守卫要求法务角色 + 必须处于 LEGAL_FINAL_REVIEW；</li>
     *   <li>关闭前必须已存在 RISK_CLOSE 签名（{@code hasLegalCloseSignature}）；</li>
     *   <li>数据库触发器 {@code trg_risk_case_close_requires_signature} 再校验一次签名——
     *       即使有人绕过应用层直接改库也关不掉。</li>
     * </ol>
     */
    @Transactional
    public RiskCaseEntity signAndClose(Actor actor, Long riskId, String reason,
                                      String signatureHash) {
        RiskCase risk = loadDomain(riskId);
        RiskStateMachine.TransitionResult r = RiskStateMachine.close(risk, actor, reason);
        applyAndRecord(risk, r, actor);
        return loadEntity(riskId);
    }

    // ════════════════════════════════════════════════════════════════════
    // 查询
    // ════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public List<RiskCaseEntity> listByCase(Long caseId) {
        return riskCaseMapper.listByCase(caseId);
    }

    @Transactional(readOnly = true)
    public RiskCaseEntity loadEntity(Long riskId) {
        RiskCaseEntity e = riskCaseMapper.selectById(riskId);
        if (e == null) {
            throw new DomainException(ErrorCode.RESOURCE_NOT_VISIBLE);
        }
        return e;
    }

    /** 风险详情：含定位锚点（前端据此渲染框选/时间轴/段落高亮） */
    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long riskId) {
        RiskCaseEntity e = loadEntity(riskId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("risk", e);
        out.put("anchors", anchorMapper.listByVersion(e.getCurrentVersionId()).stream()
                .filter(a -> isReferenced(riskId, a.getAnchorId()))
                .toList());
        out.put("records", reviewRecordService.listByRisk(riskId));
        out.put("scripts", List.of());
        return out;
    }

    private boolean isReferenced(Long riskId, String anchorId) {
        return anchorMapper.countRef(riskId, anchorId) > 0;
    }

    @Transactional(readOnly = true)
    public int countOpenBlockingRisks(Long materialId) {
        return riskCaseMapper.countOpenBlockingRisks(materialId);
    }

    // ════════════════════════════════════════════════════════════════════
    // 内部工具
    // ════════════════════════════════════════════════════════════════════

    private RiskCase loadDomain(Long riskId) {
        RiskCaseEntity e = loadEntity(riskId);
        RiskCase r = new RiskCase();
        r.setId(e.getId());
        r.setRiskNo(e.getRiskNo());
        r.setCaseId(e.getCaseId());
        r.setMaterialId(e.getMaterialId());
        r.setFirstVersionId(e.getFirstVersionId());
        r.setCurrentVersionId(e.getCurrentVersionId());
        r.setRiskType(safeEnum(RiskType.class, e.getRiskType(), RiskType.OTHER));
        r.setRiskLevel(safeEnum(RiskLevel.class, e.getRiskLevel(), RiskLevel.LOW));
        r.setConfidence(e.getConfidence());
        r.setStatusDirectly(RiskStatus.valueOf(e.getStatus()));
        r.setRiskText(e.getRiskText());
        r.setLocationDesc(e.getLocationDesc());
        r.setReason(e.getReason());
        r.setSuggestion(e.getSuggestion());
        r.setRecommendedCopy(e.getRecommendedCopy());
        r.setRequiredEvidence(e.getRequiredEvidence());
        r.setBlocked(Boolean.TRUE.equals(e.getBlocked()));
        r.setAssigneeId(e.getAssigneeId());
        r.setLockVersion(e.getLockVersion());
        // AGENTS.md 第 7 条：只有 AI 复审通过 + 法务确认 + 签名后风险才能 Closed。
        // 签名是否存在由数据库查询决定，不由应用层"认为"。
        if (riskCaseMapper.hasCloseSignature(riskId)) {
            r.markLegalCloseSignaturePresent();
        }
        return r;
    }

    /** 施加状态变更并写审计记录——两条必须同事务，否则会出现"状态变了但没留痕" */
    private void applyAndRecord(RiskCase risk, RiskStateMachine.TransitionResult r, Actor actor) {
        int affected = riskCaseMapper.updateStatusWithLock(
                risk.getId(), r.to().name(), risk.getLockVersion());
        if (affected == 0) {
            // 乐观锁冲突：两人同时处理同一风险，后到者应明确失败而不是静默覆盖
            throw new DomainException(ErrorCode.OPTIMISTIC_LOCK_CONFLICT);
        }
        reviewRecordService.appendTransition(risk.getCaseId(), risk.getId(),
                risk.getCurrentVersionId(), actor, r);
        log.info("风险 {} 状态流转 {} → {}（{}）",
                risk.getRiskNo(), r.from(), r.to(), r.action());
    }

    private static <E extends Enum<E>> E safeEnum(Class<E> type, String value, E fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return "[]";
        }
    }

    /** 归一化：去空白、全角转半角、去标点、小写。用于 dedup_key，不保留原文。 */
    private String normalize(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c >= 0xFF01 && c <= 0xFF5E) {
                c = (char) (c - 0xFEE0);           // 全角 → 半角
            }
            if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    private String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
