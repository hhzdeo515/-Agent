package com.guangxuan.audit.app.service;

import com.guangxuan.audit.common.enums.RiskLevel;
import com.guangxuan.audit.common.enums.RiskStatus;
import com.guangxuan.audit.common.enums.RiskType;
import com.guangxuan.audit.common.error.DomainException;
import com.guangxuan.audit.common.error.ErrorCode;
import com.guangxuan.audit.common.security.PermCode;
import com.guangxuan.audit.domain.port.AiInferencePort;
import com.guangxuan.audit.domain.risk.ReviewScope;
import com.guangxuan.audit.domain.security.Actor;
import com.guangxuan.audit.infra.persistence.entity.EvidenceAnchorEntity;
import com.guangxuan.audit.infra.persistence.entity.RiskCaseEntity;
import com.guangxuan.audit.infra.persistence.mapper.EvidenceAnchorMapper;
import com.guangxuan.audit.infra.persistence.mapper.RiskCaseMapper;
import com.guangxuan.audit.infra.provider.RiskKeywordRules;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI 复审（AGENTS.md 第 7 条）。
 *
 * <p>复审必须优先回答三个问题，缺一不可：
 * <ol>
 *   <li><b>原风险是否已解决</b>——该风险的原文在新版本里还在不在；</li>
 *   <li><b>当前是否仍有剩余风险</b>——新版本中是否仍命中同类关键词；</li>
 *   <li><b>本次修改是否产生新风险</b>——新版本新增的锚点里是否出现同类关键词。</li>
 * </ol>
 * 若发现新增风险，<b>新建关联 Risk Case</b>（{@code parent_risk_id} 指向原风险），
 * 而不是改写原风险的含义——后者会让"这条风险当初是什么"永久丢失。
 *
 * <h3>这层判断的性质</h3>
 * 它做的是<b>文本比对</b>，不是法律判断。因此结论里始终带上这句话，
 * 并给出命中的锚点 ID 与原文，让法务能自行核对。AGENTS.md 第 1 条要求
 * AI 不得冒充法务给出不可撤销的结论，这里的措辞与数据结构都遵守该边界。
 *
 * <h3>为什么不改状态</h3>
 * 本类只做分析与新建风险，<b>不</b>推进 {@code risk_case.status}，
 * 也不触碰签名、终审、关闭——那些是法务的动作。状态流转由应用层在拿到
 * 本类的结论后按状态机执行。这样从依赖关系上就做不到"AI 自己关掉风险"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RiskRereviewService {

    private final RiskCaseMapper riskCaseMapper;
    private final EvidenceAnchorMapper anchorMapper;
    private final AiInferencePort aiInferencePort;

    @Value("${gw.ai.pipeline-version:0.1.0}")
    private String pipelineVersion;
    @Value("${gw.ai.prompt-version:0.1.0}")
    private String promptVersion;
    @Value("${gw.ai.ruleset-version:0.1.0}")
    private String rulesetVersion;

    /** 复审判定的算法标识，写入新建风险的 model_id 便于区分来源 */
    private static final String REVIEWER = "keyword-recheck";

    // ════════════════════════════════════════════════════════════════════
    // 对外入口
    // ════════════════════════════════════════════════════════════════════

    /**
     * 复审结论。
     *
     * @param originalResolved   原风险原文是否已从新版本中消失
     * @param remainingRisk      新版本中是否仍命中同类关键词
     * @param newRiskIds         本次新建的关联风险 id（可能为空）
     * @param newRiskSummaries   新建风险的简述，供界面直接展示
     * @param evidence           判定依据：命中的锚点 ID 与文本
     * @param summary            可直接写入 review_record 的结论文本
     */
    public record RereviewAnalysis(boolean originalResolved, boolean remainingRisk,
                                   List<Long> newRiskIds, List<Map<String, Object>> newRiskSummaries,
                                   Map<String, Object> evidence, String summary) {

        /** 是否建议通过：原风险已解决且无剩余风险 */
        public boolean pass() {
            return originalResolved && !remainingRisk;
        }
    }

    /**
     * 分析一次复审。
     *
     * <p>调用前提：风险已处于 {@code AI_REREVIEW} 状态、且新版本已上传并解析。
     * 本方法不做状态校验——那是状态机的职责，重复校验会让"谁能改状态"
     * 出现两个判断来源。
     */
    @Transactional
    public RereviewAnalysis analyze(Actor actor, Long riskId, ReviewScope scope) {
        actor.requirePermission(PermCode.RISK_REREVIEW_TRIGGER);
        if (scope == null || scope.isForbiddenForRereview()) {
            throw new DomainException(ErrorCode.REVIEW_SCOPE_FORBIDDEN,
                    "复审范围不允许为 FULL（终审区不得重新做全量初审）");
        }

        RiskCaseEntity risk = riskCaseMapper.selectById(riskId);
        if (risk == null) {
            throw new DomainException(ErrorCode.RESOURCE_NOT_VISIBLE);
        }

        Long baseVersionId = risk.getFirstVersionId();
        Long targetVersionId = risk.getCurrentVersionId();
        if (targetVersionId == null || targetVersionId.equals(baseVersionId)) {
            // 没有新版本就无从复审。如实说明，而不是给一个"没问题"的默认结论
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("reason", "尚未上传新版本，或当前版本与首次发现风险的版本相同");
            ev.put("baseVersionId", baseVersionId);
            ev.put("targetVersionId", targetVersionId);
            return new RereviewAnalysis(false, true, List.of(), List.of(), ev,
                    "未检测到新版本，无法判断整改结果。请先上传修改后的版本。");
        }

        List<EvidenceAnchorEntity> baseAnchors = anchorMapper.listByVersion(baseVersionId);
        List<EvidenceAnchorEntity> targetAnchors = anchorMapper.listByVersion(targetVersionId);

        List<EvidenceAnchorEntity> baseText = textual(baseAnchors);
        List<EvidenceAnchorEntity> targetText = textual(targetAnchors);

        // 优先交给模型复审；模型不可用时回落到下面的规则比对。
        // 顺序刻意是"模型优先"：判断"换个说法之后是不是还在打擦边球"需要语义理解，
        // 而规则只能判断"原句还在不在"，遇到改写后继续违规的情况会漏。
        RereviewAnalysis byModel = tryModelRereview(risk, scope, baseText, targetText,
                targetVersionId);
        if (byModel != null) {
            return byModel;
        }
        log.info("复审走规则比对（当前 AI 实现不支持模型复审）：risk={}", risk.getRiskNo());

        // ── 问题 1：原风险是否已解决 ──────────────────────────────────
        String riskBody = VersionDiffService.normalize(risk.getRiskText());
        List<Map<String, Object>> stillPresent = new ArrayList<>();
        for (EvidenceAnchorEntity a : targetText) {
            String body = VersionDiffService.normalize(a.getText());
            if (!riskBody.isEmpty() && body.contains(riskBody)) {
                stillPresent.add(anchorBrief(a, "风险原文在新版本中仍然存在"));
            }
        }
        boolean originalResolved = stillPresent.isEmpty();

        // ── 问题 2：是否仍有剩余风险 ──────────────────────────────────
        // 即便原句被删掉，同类问题可能换了说法继续存在，因此按风险类型再扫一遍
        List<String> typeKeywords = RiskKeywordRules.keywordsOf(risk.getRiskType());
        List<Map<String, Object>> remainingHits = new ArrayList<>();
        Set<String> targetAnchorIdsWithRisk = new HashSet<>();
        for (EvidenceAnchorEntity a : targetText) {
            List<String> hits = RiskKeywordRules.hitsOfType(a.getText(), risk.getRiskType());
            if (!hits.isEmpty()) {
                targetAnchorIdsWithRisk.add(a.getAnchorId());
                remainingHits.add(anchorBrief(a, "命中同类关键词：" + String.join("、", hits)));
            }
        }
        boolean remainingRisk = !remainingHits.isEmpty();

        // ── 问题 3：是否产生新风险 ────────────────────────────────────
        // 只看"新版本新增的锚点"，避免把上一轮就存在的问题当成新发现
        Set<String> baseTexts = new HashSet<>();
        for (EvidenceAnchorEntity a : baseText) {
            baseTexts.add(VersionDiffService.normalize(a.getText()));
        }
        List<EvidenceAnchorEntity> inserted = new ArrayList<>();
        for (EvidenceAnchorEntity a : targetText) {
            if (!baseTexts.contains(VersionDiffService.normalize(a.getText()))) {
                inserted.add(a);
            }
        }
        List<Long> newRiskIds = new ArrayList<>();
        List<Map<String, Object>> newRiskSummaries = new ArrayList<>();
        for (EvidenceAnchorEntity a : inserted) {
            List<String> hits = RiskKeywordRules.hitsOfType(a.getText(), risk.getRiskType());
            if (hits.isEmpty()) {
                continue;
            }
            // 与既存同类风险去重：同一锚点已在"剩余风险"里报过就不重复建单
            if (targetAnchorIdsWithRisk.contains(a.getAnchorId())) {
                continue;
            }
            Long newId = createRelatedRisk(risk, targetVersionId, a, hits);
            if (newId != null) {
                newRiskIds.add(newId);
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("riskId", newId);
                s.put("anchorId", a.getAnchorId());
                s.put("text", a.getText());
                s.put("hitKeywords", hits);
                newRiskSummaries.add(s);
            }
        }

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("baseVersionId", baseVersionId);
        evidence.put("targetVersionId", targetVersionId);
        evidence.put("reviewScope", scope.name());
        evidence.put("riskTextNormalized", riskBody);
        evidence.put("originalStillPresent", stillPresent);
        evidence.put("remainingHits", remainingHits);
        evidence.put("insertedAnchors", inserted.size());
        evidence.put("reviewedAnchorCount", targetText.size());
        evidence.put("newRiskCount", newRiskIds.size());

        String summary = buildSummary(risk, originalResolved, remainingRisk,
                stillPresent, remainingHits, newRiskSummaries, targetText.size());

        log.info("AI 复审: risk={} 原风险已解决={} 仍有剩余={} 新增={}（依据锚点 {} 个）",
                risk.getRiskNo(), originalResolved, remainingRisk, newRiskIds.size(), targetText.size());

        return new RereviewAnalysis(originalResolved, remainingRisk, newRiskIds,
                newRiskSummaries, evidence, summary);
    }

    // ════════════════════════════════════════════════════════════════════
    // 模型复审
    // ════════════════════════════════════════════════════════════════════

    /**
     * 交给模型做复审。
     *
     * @return 模型结论；{@code null} 表示当前 AI 实现不支持模型复审，调用方应回落规则比对
     */
    private RereviewAnalysis tryModelRereview(RiskCaseEntity risk, ReviewScope scope,
                                             List<EvidenceAnchorEntity> baseText,
                                             List<EvidenceAnchorEntity> targetText,
                                             Long targetVersionId) {
        AiInferencePort.RereviewAnswer answer;
        try {
            answer = aiInferencePort.rereview(new AiInferencePort.RereviewRequest(
                    risk.getRiskText(), risk.getRiskType(), risk.getRiskLevel(),
                    risk.getLocationDesc(), scope.name(),
                    toAnchorLines(baseText), toAnchorLines(targetText),
                    buildChangeSummary(baseText, targetText)));
        } catch (Exception e) {
            // 模型调用失败不能把整条复审链路打断，但必须留痕：
            // 静默回落规则比对会让"这次其实是模型挂了"无从察觉
            log.warn("模型复审调用失败，回落规则比对：risk={} err={}",
                    risk.getRiskNo(), e.getMessage());
            return null;
        }
        if (answer == null) {
            return null;
        }

        // 新建关联风险：必须校验模型给的锚点 ID 真实存在于新版本，
        // 否则会建出一条无法定位的风险——那比不建更糟（AGENTS.md 第 5 条）
        Set<String> validAnchorIds = new HashSet<>();
        for (EvidenceAnchorEntity a : targetText) {
            validAnchorIds.add(a.getAnchorId());
        }
        List<Long> newRiskIds = new ArrayList<>();
        List<Map<String, Object>> newRiskSummaries = new ArrayList<>();
        for (AiInferencePort.NewRisk nr : answer.newRisks()) {
            if (nr.anchorId() == null || !validAnchorIds.contains(nr.anchorId())) {
                log.warn("模型报告的新增风险锚点不存在，已丢弃：risk={} anchor={}",
                        risk.getRiskNo(), nr.anchorId());
                continue;
            }
            EvidenceAnchorEntity anchor = targetText.stream()
                    .filter(a -> nr.anchorId().equals(a.getAnchorId()))
                    .findFirst().orElse(null);
            if (anchor == null) {
                continue;
            }
            Long id = createRelatedRisk(risk, targetVersionId, anchor,
                    List.of(nr.riskType()), nr.riskLevel(), nr.riskType(), nr.reason());
            if (id != null) {
                newRiskIds.add(id);
                Map<String, Object> brief = new LinkedHashMap<>();
                brief.put("riskId", id);
                brief.put("anchorId", nr.anchorId());
                brief.put("text", nr.text());
                brief.put("riskType", nr.riskType());
                newRiskSummaries.add(brief);
            }
        }

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("reviewedBy", "MODEL");
        evidence.put("targetVersionId", targetVersionId);
        evidence.put("reviewScope", scope.name());
        evidence.put("remainingEvidence", answer.remainingEvidence());
        evidence.put("modelConfidence", answer.confidence());
        evidence.put("newRiskCount", newRiskIds.size());
        evidence.put("reviewedAnchorCount", targetText.size());
        evidence.put("note", "结论由大模型基于新旧版本原文比对与语义理解给出，非法律判断");

        return new RereviewAnalysis(answer.originalResolved(), answer.remainingRisk(),
                newRiskIds, newRiskSummaries, evidence, answer.summary());
    }

    private List<AiInferencePort.AnchorLine> toAnchorLines(List<EvidenceAnchorEntity> anchors) {
        List<AiInferencePort.AnchorLine> out = new ArrayList<>();
        for (EvidenceAnchorEntity a : anchors) {
            out.add(new AiInferencePort.AnchorLine(
                    a.getAnchorId(), a.getAnchorType(), a.getText(), null, null));
        }
        return out;
    }

    /** 两版差异摘要：让模型聚焦改动区域，而不是从两段长文本里自己找 */
    private String buildChangeSummary(List<EvidenceAnchorEntity> base,
                                      List<EvidenceAnchorEntity> target) {
        Set<String> baseTexts = new HashSet<>();
        for (EvidenceAnchorEntity a : base) {
            baseTexts.add(VersionDiffService.normalize(a.getText()));
        }
        List<String> removed = new ArrayList<>();
        for (EvidenceAnchorEntity a : base) {
            boolean still = target.stream().anyMatch(t ->
                    VersionDiffService.normalize(t.getText())
                            .contains(VersionDiffService.normalize(a.getText())));
            if (!still) {
                removed.add(a.getText());
            }
        }
        List<String> added = new ArrayList<>();
        for (EvidenceAnchorEntity a : target) {
            if (!baseTexts.contains(VersionDiffService.normalize(a.getText()))) {
                added.add(a.getText());
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("原版本锚点 ").append(base.size())
                .append(" 个，新版本 ").append(target.size()).append(" 个。");
        if (removed.isEmpty()) {
            sb.append("未检测到删除的原文。");
        } else {
            sb.append("新版本中已消失：").append(String.join("；", removed)).append("。");
        }
        if (added.isEmpty()) {
            sb.append("未检测到新增原文。");
        } else {
            sb.append("新版本中新增：").append(String.join("；", added)).append("。");
        }
        return sb.toString();
    }

    // ════════════════════════════════════════════════════════════════════
    // 新建关联风险
    // ════════════════════════════════════════════════════════════════════

    /**
     * 为整改过程中新发现的问题新建一条 Risk Case，并关联原风险。
     *
     * <p>新风险状态置为 {@code PENDING_LEGAL_DECISION}——它由文本比对发现，
     * 是否成立需要法务判断，不能直接当成已确认的风险。
     *
     * @return 新建风险的 id；若因去重键冲突已存在则返回 null
     */
    private Long createRelatedRisk(RiskCaseEntity origin, Long versionId,
                                   EvidenceAnchorEntity anchor, List<String> hits) {
        RiskKeywordRules.Rule rule = RiskKeywordRules.of(hits.get(0));
        RiskLevel level = rule == null ? RiskLevel.MEDIUM : rule.level();
        RiskType type = rule == null
                ? safeType(origin.getRiskType())
                : rule.type();
        return createRelatedRisk(origin, versionId, anchor, hits,
                level.name(), type.name(),
                rule == null ? "整改后新增内容命中风险关键词" : rule.reason());
    }

    /**
     * 新建关联风险（可指定等级、类型与原因）。
     *
     * <p>分成两个入口是因为两类调用方的信息来源不同：规则比对拿到的是命中的关键词，
     * 类型与等级由规则表决定；模型复审拿到的是模型自己的判断，已经给了类型与等级。
     * 把两者硬塞进一个签名，只会让其中一方被迫传假值。
     */
    private Long createRelatedRisk(RiskCaseEntity origin, Long versionId,
                                   EvidenceAnchorEntity anchor, List<String> hits,
                                   String levelOverride, String typeOverride,
                                   String reasonOverride) {
        RiskLevel level = safeLevel(levelOverride);
        RiskType type = safeType(typeOverride == null ? origin.getRiskType() : typeOverride);

        String dedupKey = sha256(versionId + "|" + type.name() + "|" + anchor.getAnchorId()
                + "|" + VersionDiffService.normalize(anchor.getText()));

        Long exists = riskCaseMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<RiskCaseEntity>()
                        .eq("dedup_key", dedupKey));
        if (exists != null && exists > 0) {
            log.debug("关联风险已存在，跳过（dedupKey={}）", dedupKey);
            return null;
        }

        RiskCaseEntity e = new RiskCaseEntity();
        e.setRiskNo(nextRiskNo(origin.getCaseId()));
        e.setCaseId(origin.getCaseId());
        e.setMaterialId(origin.getMaterialId());
        // 新风险的"首次发现版本"就是产生它的那个版本——它确实是在这一版才出现的
        e.setFirstVersionId(versionId);
        e.setCurrentVersionId(versionId);
        e.setRiskType(type.name());
        e.setRiskLevel(level.name());
        e.setConfidence(BigDecimal.valueOf(0.7).setScale(4, RoundingMode.HALF_UP));
        e.setStatus(RiskStatus.PENDING_LEGAL_DECISION.name());
        e.setRiskText(anchor.getText());
        e.setLocationDesc("锚点 " + anchor.getAnchorId() + "（整改后新增内容）");
        e.setReason(reasonOverride
                + "；由 AI 复审在版本比对中发现，需法务判断是否成立");
        e.setRuleRefs("[]");
        e.setEvidenceRefs("[]");
        e.setUnsupportedClaims("[]");
        e.setSuggestion("建议删除或改为有依据的客观表述");
        e.setRecommendedCopy("参照同类型风险的合规表述");
        e.setRequiredEvidence("如保留原表述，需提供相应证明材料");
        e.setBlocked(level.isBlocking());
        e.setParentRiskId(origin.getId());
        e.setDedupKey(dedupKey);
        e.setModelId(REVIEWER);
        e.setPipelineVersion(pipelineVersion);
        e.setPromptVersion(promptVersion);
        e.setRulesetVersion(rulesetVersion);
        e.setLockVersion(0);
        riskCaseMapper.insert(e);

        // 锚点引用：复合外键会校验锚点确实属于该版本
        anchorMapper.insertAnchorRef(e.getId(), anchor.getAnchorId(), versionId, "PRIMARY");

        log.info("复审新建关联风险 {}（父风险 {}，锚点 {}）",
                e.getRiskNo(), origin.getRiskNo(), anchor.getAnchorId());
        return e.getId();
    }

    /** 生成风险编号。并发下由 uk_risk_no 兜底，此处只保证常规情况下不重号。 */
    private String nextRiskNo(Long caseId) {
        Long cnt = riskCaseMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<RiskCaseEntity>()
                        .eq("case_id", caseId));
        long seq = (cnt == null ? 0 : cnt) + 1;
        String no = "RK-" + caseId + "-" + String.format("%03d", seq);
        // 极端并发导致的冲突：退化为时间戳后缀，保证唯一
        Long dup = riskCaseMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<RiskCaseEntity>()
                        .eq("risk_no", no));
        return (dup != null && dup > 0) ? no + "-" + (System.currentTimeMillis() % 1000) : no;
    }

    // ════════════════════════════════════════════════════════════════════
    // 工具
    // ════════════════════════════════════════════════════════════════════

    /** 只保留有文本的锚点：无文本锚点无法做比对 */
    private static List<EvidenceAnchorEntity> textual(List<EvidenceAnchorEntity> list) {
        List<EvidenceAnchorEntity> out = new ArrayList<>();
        for (EvidenceAnchorEntity a : list) {
            if (a.getText() != null && !a.getText().isBlank()) {
                out.add(a);
            }
        }
        return out;
    }

    /** 等级解析：模型可能给出枚举外的值，落到 MEDIUM 而不是让整条链路失败 */
    private static RiskLevel safeLevel(String name) {
        try {
            return RiskLevel.valueOf(name);
        } catch (Exception e) {
            return RiskLevel.MEDIUM;
        }
    }

    private static Map<String, Object> anchorBrief(EvidenceAnchorEntity a, String why) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("anchorId", a.getAnchorId());
        m.put("anchorType", a.getAnchorType());
        m.put("text", a.getText());
        m.put("locator", a.getLocator());
        m.put("why", why);
        return m;
    }

    private String buildSummary(RiskCaseEntity risk, boolean originalResolved, boolean remainingRisk,
                                List<Map<String, Object>> stillPresent,
                                List<Map<String, Object>> remainingHits,
                                List<Map<String, Object>> newRisks, int reviewedAnchors) {
        StringBuilder sb = new StringBuilder();
        sb.append("复审（仅文本比对，非法律判断，最终以法务意见为准）：");

        // 问题 1
        if (originalResolved) {
            sb.append("\n1) 原风险已解决：「").append(brief(risk.getRiskText()))
              .append("」在新版本中未再出现。");
        } else {
            sb.append("\n1) 原风险未解决：「").append(brief(risk.getRiskText()))
              .append("」仍存在于新版本，命中锚点 ")
              .append(joinAnchorIds(stillPresent)).append("。");
        }

        // 问题 2
        if (remainingRisk) {
            sb.append("\n2) 仍有剩余风险：新版本中仍有 ").append(remainingHits.size())
              .append(" 处命中同类关键词，锚点 ")
              .append(joinAnchorIds(remainingHits)).append("。");
        } else {
            sb.append("\n2) 无剩余风险：新版本中未再命中同类关键词。");
        }

        // 问题 3
        if (newRisks.isEmpty()) {
            sb.append("\n3) 未发现新增风险。");
        } else {
            sb.append("\n3) 发现 ").append(newRisks.size())
              .append(" 处新增风险，已新建关联风险记录（未改写原风险含义）：");
            for (Map<String, Object> r : newRisks) {
                sb.append("\n   · ").append(brief(String.valueOf(r.get("text"))))
                  .append("（锚点 ").append(r.get("anchorId")).append("）");
            }
        }

        sb.append("\n本次比对新版本锚点 ").append(reviewedAnchors).append(" 个。");
        sb.append("\n建议：").append(originalResolved && !remainingRisk
                ? "可进入法务终审。" : "请修改后重新提交，或由法务判断是否可接受。");

        String s = sb.toString();
        return s.length() > 1800 ? s.substring(0, 1800) + "…" : s;
    }

    private static String brief(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replace("\n", " ").trim();
        return t.length() > 60 ? t.substring(0, 60) + "…" : t;
    }

    private static String joinAnchorIds(List<Map<String, Object>> list) {
        List<String> ids = new ArrayList<>();
        for (Map<String, Object> m : list) {
            ids.add(String.valueOf(m.get("anchorId")));
        }
        return ids.isEmpty() ? "（无）" : String.join("、", ids);
    }

    private static RiskType safeType(String name) {
        try {
            return RiskType.valueOf(name);
        } catch (Exception e) {
            return RiskType.OTHER;
        }
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
