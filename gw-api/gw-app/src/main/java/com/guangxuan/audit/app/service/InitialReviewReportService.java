package com.guangxuan.audit.app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guangxuan.audit.common.enums.RiskLevel;
import com.guangxuan.audit.common.enums.RiskStatus;
import com.guangxuan.audit.common.enums.RiskType;
import com.guangxuan.audit.common.error.DomainException;
import com.guangxuan.audit.common.error.ErrorCode;
import com.guangxuan.audit.common.security.PermCode;
import com.guangxuan.audit.domain.port.AiInferencePort;
import com.guangxuan.audit.domain.security.Actor;
import com.guangxuan.audit.infra.persistence.entity.AuditCaseEntity;
import com.guangxuan.audit.infra.persistence.entity.InitialReviewReportEntity;
import com.guangxuan.audit.infra.persistence.entity.RiskCaseEntity;
import com.guangxuan.audit.infra.persistence.entity.RiskCommunicationScriptEntity;
import com.guangxuan.audit.infra.persistence.mapper.AuditCaseMapper;
import com.guangxuan.audit.infra.persistence.mapper.InitialReviewReportMapper;
import com.guangxuan.audit.infra.persistence.mapper.LegalBasisMapper;
import com.guangxuan.audit.infra.persistence.mapper.MaterialMapper;
import com.guangxuan.audit.infra.persistence.mapper.RiskCommunicationScriptMapper;
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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * AI 初审报告服务（AGENTS.md 第 6 条）。
 *
 * <p><b>为什么报告骨架由服务端拼装，而不是让模型直接产整篇报告</b>：
 * AGENTS.md 第 11 条要求"不能让报告和数据库中的 Risk Case 数量或状态互相矛盾"。
 * 一旦把计数交给模型，它就有机会把 7 条写成 6 条、把"待人工判断"写成"已通过"。
 * 因此这里的规则是：
 * <ul>
 *   <li><b>数字与结论</b>——全部来自数据库视图与 risk_case 行，模型不参与；</li>
 *   <li><b>语言</b>——每一条风险下的沟通话术由 {@link AiInferencePort} 生成并单独落库，</li>
 * </ul>
 * 这样报告既可读，又不可能与库里的数据不一致。
 *
 * <p>报告 append-only：每次生成新增一个 {@code revision_no}，不覆盖旧版。
 * 复核时若发现"报告与数据不符"，必须能取到当时那一版。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InitialReviewReportService {

    private final MaterialMapper materialMapper;
    private final AuditCaseMapper caseMapper;
    private final InitialReviewReportMapper reportMapper;
    private final RiskCommunicationScriptMapper scriptMapper;
    private final LegalBasisMapper legalBasisMapper;
    private final RiskAppService riskAppService;
    private final AiInferencePort aiInferencePort;
    private final ReviewRecordService reviewRecordService;
    private final ObjectMapper objectMapper;

    @Value("${gw.ai.models.vision:unknown}")
    private String modelId;
    @Value("${gw.ai.pipeline-version:0.1.0}")
    private String pipelineVersion;
    @Value("${gw.ai.prompt-version:0.1.0}")
    private String promptVersion;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String AUDIENCE = "BRAND";

    /** 通过率口径的唯一说法：界面、报告、导出三处必须逐字一致 */
    public static final String RATE_DEFINITION =
            "初审通过率 = 初审通过物料数 ÷ 参与初审物料数；解析失败物料不计入分母，单独列出。"
                    + "「AI 初审通过」仅表示 AI 未发现明显风险，不等于法务最终批准。";

    // ── 查询 ────────────────────────────────────────────────────────────

    /** 最新一版报告；没有则返回 null（由调用方决定是否生成） */
    @Transactional(readOnly = true)
    public InitialReviewReportEntity findLatest(Long caseId) {
        return reportMapper.findLatest(caseId);
    }

    // ── 生成 ────────────────────────────────────────────────────────────

    /**
     * 生成（或重新生成）本任务的 AI 初审报告。
     *
     * <p>报告只反映<b>第一次 AI 初审</b>结果，因此数据源固定为初审视图与 risk_case，
     * 不含任何整改版本信息——版本与 Diff 属于终审区（AGENTS.md 第 5、7 条）。
     */
    @Transactional
    public InitialReviewReportEntity generate(Actor actor, Long caseId) {
        actor.requirePermission(PermCode.REPORT_VIEW);

        AuditCaseEntity c = caseMapper.selectById(caseId);
        if (c == null) {
            throw new DomainException(ErrorCode.RESOURCE_NOT_VISIBLE);
        }

        Map<String, Object> summary = materialMapper.selectInitialReviewSummary(caseId);
        List<Map<String, Object>> materials = materialMapper.selectInitialReviewClasses(caseId);
        List<RiskCaseEntity> risks = riskAppService.listByCase(caseId);

        int reviewed = intOf(summary, "reviewedMaterialCount");
        int parseFailed = intOf(summary, "parseFailedCount");
        int passCount = intOf(summary, "initialPassCount");
        int pendingCount = intOf(summary, "pendingHumanCount");
        int failCount = intOf(summary, "riskFailCount");
        BigDecimal passRate = decimalOf(summary, "initialPassRateByMaterial");

        // 物料名映射：报告里必须写"哪份材料的哪个位置"，否则法务要自己去翻
        Map<Long, String> materialNames = new LinkedHashMap<>();
        for (Map<String, Object> m : materials) {
            Object id = m.get("materialId");
            Object name = m.get("materialName");
            if (id instanceof Number n && name != null) {
                materialNames.put(n.longValue(), String.valueOf(name));
            }
        }

        // 审核依据：rule_refs 里存的是知识库版本 ID，批量还原成人能读的条款原文
        Map<Long, String> basisText = loadBasisText(risks);

        // 沟通话术：语言部分交给 AI，数字部分不交给它
        Map<Long, String> scripts = generateScripts(actor, risks, basisText);

        Map<String, Object> distribution = riskTypeDistribution(risks);

        String md = composeMarkdown(c, summary, materials, risks, materialNames, basisText,
                scripts, distribution, reviewed, parseFailed, passCount, pendingCount,
                failCount, passRate);

        InitialReviewReportEntity e = new InitialReviewReportEntity();
        e.setCaseId(caseId);
        e.setRevisionNo(reportMapper.nextRevisionNo(caseId));
        e.setContentMd(md);
        e.setContentHash(sha256(md));
        e.setMaterialCount(materials.size());
        e.setReviewedMaterialCount(reviewed);
        e.setParseFailedCount(parseFailed);
        e.setInitialPassCount(passCount);
        e.setPendingHumanCount(pendingCount);
        e.setRiskFailCount(failCount);
        e.setInitialPassRate(passRate);
        e.setRiskTypeDistribution(toJson(distribution));
        e.setDataSnapshot(toJson(dataSnapshot(c, risks, reviewed, parseFailed)));
        e.setModelId(modelId);
        e.setPipelineVersion(pipelineVersion);
        e.setPromptVersion(promptVersion);
        e.setGeneratedBy(actor.userId());
        e.setGeneratedAt(LocalDateTime.now());
        reportMapper.insert(e);

        reviewRecordService.append(caseId, null, null, actor, "REPORT_GENERATED",
                "生成第 " + e.getRevisionNo() + " 版 AI 初审报告", null, null,
                "{\"revisionNo\":" + e.getRevisionNo() + ",\"contentHash\":\""
                        + e.getContentHash() + "\",\"riskCount\":" + risks.size() + "}");

        log.info("AI 初审报告已生成: case={} rev={} 风险 {} 条", caseId, e.getRevisionNo(), risks.size());
        return e;
    }

    /**
     * 生成一个 Case 下全部风险的沟通话术。
     *
     * <p>已存在 BRAND 受众话术的风险直接复用，不重复调用模型——
     * 重复生成会让同一风险在不同时间点的话术对不上，也会无谓增加模型成本。
     */
    private Map<Long, String> generateScripts(Actor actor, List<RiskCaseEntity> risks,
                                              Map<Long, String> basisText) {
        Map<Long, String> existing = scriptMapper.listByCase(
                        risks.isEmpty() ? -1L : risks.get(0).getCaseId()).stream()
                .filter(s -> AUDIENCE.equals(s.getAudience()))
                .collect(Collectors.toMap(RiskCommunicationScriptEntity::getRiskCaseId,
                        RiskCommunicationScriptEntity::getScriptText, (a, b) -> b));

        Map<Long, String> out = new LinkedHashMap<>();
        for (RiskCaseEntity r : risks) {
            String cached = existing.get(r.getId());
            if (cached != null && !cached.isBlank()) {
                out.put(r.getId(), cached);
                continue;
            }
            String text;
            try {
                text = aiInferencePort.generateCommunicationScript(new AiInferencePort.ScriptRequest(
                        AUDIENCE, r.getRiskText(), r.getLocationDesc(), label(r.getRiskLevel()),
                        r.getReason(), r.getSuggestion(), r.getRecommendedCopy(),
                        r.getRequiredEvidence()));
            } catch (Exception ex) {
                // 话术生成失败不应让整篇报告失败：报告的数字部分仍然有效
                log.warn("沟通话术生成失败 riskId={}: {}", r.getId(), ex.getMessage());
                text = "（话术生成失败，请人工撰写）";
            }
            out.put(r.getId(), text);
            try {
                RiskCommunicationScriptEntity s = new RiskCommunicationScriptEntity();
                s.setRiskCaseId(r.getId());
                s.setAudience(AUDIENCE);
                s.setRevisionNo(scriptMapper.nextRevisionNo(r.getId(), AUDIENCE));
                s.setScriptText(text);
                s.setModelId(modelId);
                s.setPromptVersion(promptVersion);
                s.setGeneratedAt(LocalDateTime.now());
                scriptMapper.insert(s);
            } catch (Exception ex) {
                log.warn("沟通话术落库失败 riskId={}: {}", r.getId(), ex.getMessage());
            }
        }
        return out;
    }

    /**
     * 批量把 rule_refs 还原为可读条款；查询失败不影响报告主体。
     *
     * <p>还原的是<b>具体条款</b>而不是整部法规——一个版本 ID 下挂着七八条切片，
     * 全贴出来法务看到的是一整部法律，"这条风险违反了哪一款"反而被淹没。
     * 选条款与其他调用方共用 {@code LegalClausePicker}，否则会出现
     * "报告引第四条、风险详情引第九条"的矛盾。
     */
    private Map<Long, String> loadBasisText(List<RiskCaseEntity> risks) {
        Set<Long> ids = new LinkedHashSet<>();
        for (RiskCaseEntity r : risks) {
            ids.addAll(parseIdArray(r.getRuleRefs()));
        }
        if (ids.isEmpty()) {
            return Map.of();
        }

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
            for (Long id : parseIdArray(r.getRuleRefs())) {
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

    private List<Long> parseIdArray(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        try {
            for (Object o : objectMapper.readValue(json, List.class)) {
                if (o instanceof Number n) {
                    ids.add(n.longValue());
                }
            }
        } catch (Exception e) {
            log.debug("rule_refs 解析失败（忽略）: {}", json);
        }
        return ids;
    }

    // ── Markdown 组装 ───────────────────────────────────────────────────

    private String composeMarkdown(AuditCaseEntity c, Map<String, Object> summary,
                                   List<Map<String, Object>> materials, List<RiskCaseEntity> risks,
                                   Map<Long, String> materialNames, Map<Long, String> basisText,
                                   Map<Long, String> scripts, Map<String, Object> distribution,
                                   int reviewed, int parseFailed, int passCount, int pendingCount,
                                   int failCount, BigDecimal passRate) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(c.getName()).append(" · AI 初审报告\n\n");
        sb.append("> 任务编号：").append(c.getCaseNo())
                .append("　任务状态：").append(c.getStatus())
                .append("　生成时间：").append(LocalDateTime.now().format(TS)).append("\n");
        sb.append("> 本报告仅反映**第一次 AI 初审**结果，属于 AI 初审意见，")
                .append("经法务确认或批准之前不对任何物料生效。\n\n");

        sb.append("## 一、初审概览\n\n");
        sb.append("| 指标 | 数值 |\n| --- | --- |\n");
        sb.append("| 物料总数 | ").append(materials.size()).append(" |\n");
        sb.append("| 参与初审（通过率分母） | ").append(reviewed).append(" |\n");
        sb.append("| 解析失败（不计入分母） | ").append(parseFailed).append(" |\n");
        sb.append("| 初审通过 | ").append(passCount).append(" |\n");
        sb.append("| 待人工判断 | ").append(pendingCount).append(" |\n");
        sb.append("| 风险未通过 | ").append(failCount).append(" |\n");
        sb.append("| 物料层初审通过率 | ")
                .append(passRate == null ? "—" : String.format("%.2f%%", passRate.doubleValue() * 100))
                .append(" |\n");
        sb.append("| 风险记录总数 | ").append(risks.size()).append(" |\n");
        sb.append("| 高风险 / 中风险 / 低风险 | ")
                .append(intOf(summary, "highRiskItems")).append(" / ")
                .append(intOf(summary, "mediumRiskItems")).append(" / ")
                .append(intOf(summary, "lowRiskItems")).append(" |\n");
        sb.append("| 未关闭的阻断性风险 | ")
                .append(intOf(summary, "openBlockingRiskItems")).append(" |\n\n");
        sb.append("> **口径**：").append(RATE_DEFINITION).append("\n\n");

        sb.append("## 二、风险类型分布\n\n");
        if (distribution.isEmpty()) {
            sb.append("本批材料未识别出风险。\n\n");
        } else {
            sb.append("| 风险类型 | 数量 |\n| --- | --- |\n");
            for (Map.Entry<String, Object> e : distribution.entrySet()) {
                sb.append("| ").append(e.getKey()).append(" | ").append(e.getValue()).append(" |\n");
            }
            sb.append("\n");
        }

        sb.append("## 三、物料初审结果\n\n");
        sb.append("| 物料 | 类型 | 解析 | 初审分类 | 风险数 |\n| --- | --- | --- | --- | --- |\n");
        for (Map<String, Object> m : materials) {
            sb.append("| ").append(m.get("materialName"))
                    .append(" | ").append(materialTypeLabel(str(m.get("materialType"))))
                    .append(" | ").append(parseLabel(str(m.get("parseStatus"))))
                    .append(" | ").append(classLabel(str(m.get("initialReviewClass"))))
                    .append(" | ").append(m.get("riskCount")).append(" |\n");
        }
        sb.append("\n");

        sb.append("## 四、风险明细\n\n");
        if (risks.isEmpty()) {
            sb.append("本批材料未建立风险记录。\n\n");
        }
        for (RiskCaseEntity r : risks) {
            sb.append("### ").append(r.getRiskNo()).append("　")
                    .append(label(r.getRiskLevel())).append("风险　")
                    .append(typeLabel(r.getRiskType())).append("\n\n");
            sb.append("- **物料**：").append(materialNames.getOrDefault(r.getMaterialId(), "—"))
                    .append("（").append(r.getCurrentVersionId() == null ? "—" : "版本 #" + r.getCurrentVersionId())
                    .append("）\n");
            sb.append("- **风险原文**：").append(nz(r.getRiskText())).append("\n");
            sb.append("- **风险位置**：").append(nz(r.getLocationDesc())).append("\n");
            sb.append("- **风险原因**：").append(nz(r.getReason())).append("\n");
            sb.append("- **AI 置信度**：").append(confidenceText(r.getConfidence())).append("\n");
            sb.append("- **当前状态**：").append(statusLabel(r.getStatus())).append("\n");
            sb.append("- **审核依据**：").append(basisOrUnsupported(r, basisText)).append("\n");
            sb.append("- **AI 修改建议**：").append(nz(r.getSuggestion())).append("\n");
            sb.append("- **推荐表达**：").append(nz(r.getRecommendedCopy())).append("\n");
            sb.append("- **所需证明材料**：").append(nz(r.getRequiredEvidence())).append("\n\n");
            String script = scripts.get(r.getId());
            if (script != null && !script.isBlank()) {
                sb.append("**可直接发送给业务方的沟通话术**（受众：品牌）\n\n");
                sb.append("```text\n").append(script.trim()).append("\n```\n\n");
            }
        }

        sb.append("## 五、生成信息\n\n");
        sb.append("- 模型：").append(modelId).append("\n");
        sb.append("- 流水线版本：").append(pipelineVersion).append("\n");
        sb.append("- 提示词版本：").append(promptVersion).append("\n");
        sb.append("- 生成者：").append(LocalDateTime.now().format(TS))
                .append("（AI 生成，未含法务意见）\n");
        sb.append("- 数据口径：物料三分类与通过率取自数据库视图 `v_material_initial_review` / ")
                .append("`v_case_initial_review_summary`，报告数字与库中 Risk Case 数量同源。\n");
        return sb.toString();
    }

    private Map<String, Object> riskTypeDistribution(List<RiskCaseEntity> risks) {
        Map<String, Long> counts = risks.stream()
                .collect(Collectors.groupingBy(RiskCaseEntity::getRiskType,
                        Collectors.counting()));
        Map<String, Object> out = new LinkedHashMap<>();
        counts.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<String, Long> e) -> -e.getValue()))
                .forEach(e -> out.put(typeLabel(e.getKey()), e.getValue()));
        return out;
    }

    private Map<String, Object> dataSnapshot(AuditCaseEntity c, List<RiskCaseEntity> risks,
                                            int reviewed, int parseFailed) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("caseStatus", c.getStatus());
        snap.put("reviewedMaterialCount", reviewed);
        snap.put("parseFailedCount", parseFailed);
        snap.put("riskCaseIds", risks.stream().map(RiskCaseEntity::getId).toList());
        snap.put("riskStatuses", risks.stream().collect(Collectors.toMap(
                r -> String.valueOf(r.getId()), RiskCaseEntity::getStatus, (a, b) -> b, LinkedHashMap::new)));
        snap.put("generatedAt", LocalDateTime.now().format(TS));
        return snap;
    }

    // ── 小工具 ──────────────────────────────────────────────────────────

    private String basisOrUnsupported(RiskCaseEntity r, Map<Long, String> basisText) {
        List<Long> ids = parseIdArray(r.getRuleRefs());
        List<String> texts = ids.stream().map(basisText::get).filter(t -> t != null && !t.isBlank()).toList();
        List<String> unsupported = parseStringArray(r.getUnsupportedClaims());
        if (texts.isEmpty() && unsupported.isEmpty()) {
            return "未检索到可引用的现行规则，本条为**待人工判断**，请勿据此直接下结论。";
        }
        StringBuilder sb = new StringBuilder();
        if (!texts.isEmpty()) {
            sb.append(String.join("；", texts));
        }
        if (!unsupported.isEmpty()) {
            if (sb.length() > 0) {
                sb.append("　");
            }
            // AGENTS.md 第 11 条：检索不到依据的引用不得作为依据，必须显式标出
            sb.append("⚠️ AI 曾试图引用但知识库中查无此依据的内容：")
                    .append(String.join("；", unsupported))
                    .append("（不作为审核依据，已强制转人工判断）");
        }
        return sb.toString();
    }

    private List<String> parseStringArray(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<?> list = objectMapper.readValue(json, List.class);
            return list.stream().map(String::valueOf).filter(s -> !s.isBlank()).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String nz(String s) {
        return s == null || s.isBlank() ? "—" : s.trim();
    }

    private static String confidenceText(BigDecimal c) {
        if (c == null) {
            return "—";
        }
        return String.format("%.0f%%", c.doubleValue() * 100);
    }

    private static String label(String riskLevel) {
        try {
            return RiskLevel.valueOf(riskLevel).label();
        } catch (Exception e) {
            return riskLevel == null ? "—" : riskLevel;
        }
    }

    private static String typeLabel(String riskType) {
        try {
            return RiskType.valueOf(riskType).label();
        } catch (Exception e) {
            return riskType == null ? "其他" : riskType;
        }
    }

    private static String statusLabel(String status) {
        try {
            return RiskStatus.valueOf(status).label();
        } catch (Exception e) {
            return status == null ? "—" : status;
        }
    }

    private static String classLabel(String cls) {
        return switch (cls == null ? "" : cls) {
            case "INITIAL_PASS" -> "AI 初审通过（≠ 法务最终批准）";
            case "PENDING_HUMAN" -> "待人工判断";
            case "RISK_FAIL" -> "风险未通过";
            default -> "未参与初审";
        };
    }

    private static String parseLabel(String s) {
        return switch (s == null ? "" : s) {
            case "SUCCEEDED" -> "解析完成";
            case "PARTIAL" -> "部分解析";
            case "FAILED" -> "解析失败";
            case "RUNNING" -> "解析中";
            default -> "待解析";
        };
    }

    private static String materialTypeLabel(String t) {
        return switch (t == null ? "" : t) {
            case "IMAGE" -> "图片";
            case "VIDEO" -> "视频";
            case "TEXT" -> "文本";
            case "PPT" -> "PPT";
            case "PDF" -> "PDF";
            case "WORD" -> "Word";
            default -> t == null ? "—" : t;
        };
    }

    private static int intOf(Map<String, Object> m, String key) {
        if (m == null) {
            return 0;
        }
        Object v = m.get(key);
        return v instanceof Number n ? n.intValue() : 0;
    }

    private static BigDecimal decimalOf(Map<String, Object> m, String key) {
        if (m == null) {
            return null;
        }
        Object v = m.get(key);
        if (v instanceof BigDecimal b) {
            return b.setScale(4, RoundingMode.HALF_UP);
        }
        return v instanceof Number n
                ? BigDecimal.valueOf(n.doubleValue()).setScale(4, RoundingMode.HALF_UP) : null;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
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

    /** 供上层做分组统计用；避免在 Controller 里反复查库 */
    @Transactional(readOnly = true)
    public Map<Long, List<RiskCommunicationScriptEntity>> scriptsByRisk(Long caseId) {
        return scriptMapper.listByCase(caseId).stream()
                .collect(Collectors.groupingBy(RiskCommunicationScriptEntity::getRiskCaseId,
                        LinkedHashMap::new, Collectors.toList()));
    }

    /** 便于单测/排查：报告正文里出现的风险编号顺序 */
    @Transactional(readOnly = true)
    public List<String> riskNosOf(Long caseId) {
        return riskAppService.listByCase(caseId).stream().map(RiskCaseEntity::getRiskNo).toList();
    }
}
