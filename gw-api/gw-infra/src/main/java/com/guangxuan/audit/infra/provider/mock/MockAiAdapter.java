package com.guangxuan.audit.infra.provider.mock;

import com.guangxuan.audit.common.enums.RiskLevel;
import com.guangxuan.audit.common.enums.RiskType;
import com.guangxuan.audit.domain.ai.RiskDraft;
import com.guangxuan.audit.domain.port.AiInferencePort;
import com.guangxuan.audit.infra.persistence.mapper.LegalBasisMapper;
import com.guangxuan.audit.infra.provider.RiskKeywordRules;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 推理端口的 Mock 实现：基于关键词规则的假识别。
 *
 * <p>用途：在没有 {@code DASHSCOPE_API_KEY} 时让主链路可端到端跑通，
 * 并让"单文件多风险"这类场景可以被稳定复现（AGENTS.md 第 15 条要求覆盖该场景）。
 *
 * <p><b>它刻意不是"聪明的"实现</b>：只做关键词匹配，不调用模型。
 * 这样验收时"AI 判得对不对"与"编排是否按规则走"两件事可以分开评估。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "gw.ai.provider", havingValue = "mock", matchIfMissing = true)
public class MockAiAdapter implements AiInferencePort {

    /**
     * 法定依据来源。
     *
     * <p>回答里引用的每一条法规都来自这里查出的真实条款，而不是写死在模板里——
     * 这样"依据"才能被追溯与更新：法规调整时只需更新知识库数据，
     * 不需要改这个类的代码。
     */
    private final LegalBasisMapper legalBasisMapper;

    /**
     * 关键词 → 风险类型 / 等级 的映射。
     *
     * <p>覆盖 AGENTS.md 第 6 条列举的主要风险类型，便于演示"一张海报多个独立风险"
     * 会生成多条可分别处理的 Risk Case。
     */
    /**
     * 关键词规则表已提取到 {@link com.guangxuan.audit.infra.provider.RiskKeywordRules}。
     *
     * <p>原因：AI 复审（{@code RiskRereviewService}）必须与初审用同一套关键词，
     * 否则会出现"初审命中、复审不命中"的分裂，在界面上表现为 AI 前后不一致。
     * 本类不再自己维护规则，一律引用共享表。
     */
    private static final Map<String, RiskKeywordRules.Rule> RULES = RiskKeywordRules.all();

    static {
        // 规则内容定义见 RiskKeywordRules；此处仅保留一个空块以便阅读时定位
    }

    @Override
    public List<RiskDraft> identifyCandidates(CandidateRequest request) {
        List<RiskDraft> drafts = new ArrayList<>();
        for (AnchorLine line : request.anchorLines()) {
            String text = line.text();
            if (text == null || text.isBlank()) {
                continue;
            }
            // 用共享规则表匹配：它内部会剔除「被更长命中词包含」的短词，
            // 否则「行业第一」会同时生成「行业第一」与「第一」两条重复风险
            for (RiskKeywordRules.Rule rule : RiskKeywordRules.match(text)) {
                drafts.add(new RiskDraft(
                        rule.type().name(),
                        rule.level().name(),
                        rule.keyword(),
                        new RiskDraft.Location(List.of(line.anchorId()), "NOT_APPLICABLE",
                                "锚点 " + line.anchorId()),
                        rule.reason(),
                        0.85,
                        // 依据只能来自知识库：这里是防虚构法条机制的输入端。
                        // 曾经被误改成 List.of() 过一次——那样每条风险都会变成"依据不足"，
                        // 表面上更"安全"，实际上是让整套引用体系空转，法务看不到任何法条。
                        legalRefsFor(rule.type().name()),
                        List.of(),
                        List.of(),
                        "建议删除或改为有依据的表述",
                        "建议替换为可验证的客观表述",
                        "如保留原表述，需提供第三方检测报告或统计数据来源",
                        "OPEN"));
            }
        }
        log.debug("[mock] identifyCandidates -> {} drafts", drafts.size());
        return drafts;
    }

    @Override
    public List<RiskDraft> judge(JudgeRequest request) {
        // Mock 不做额外的判断收紧，直接回传候选（真实实现应结合检索到的依据收紧误报）
        return request.candidates();
    }

    @Override
    public String generateReport(ReportRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("# AI 初审报告（Mock 生成）\n\n");
        sb.append("> 本报告由 Mock 适配器生成，仅用于联调验证，不构成任何法律意见。\n\n");
        sb.append("## 一、初审概览\n\n");
        sb.append("| 指标 | 数量 |\n| --- | --- |\n");
        sb.append("| 参与初审物料数 | ").append(request.reviewedMaterialCount()).append(" |\n");
        sb.append("| 解析失败物料数（不计入分母） | ").append(request.parseFailedCount()).append(" |\n");
        sb.append("| 初审通过 | ").append(request.initialPassCount()).append(" |\n");
        sb.append("| 待人工判断 | ").append(request.pendingHumanCount()).append(" |\n");
        sb.append("| 风险未通过 | ").append(request.riskFailCount()).append(" |\n");
        sb.append("| 物料层初审通过率 | ")
                .append(String.format("%.2f%%", request.initialPassRate() * 100)).append(" |\n\n");
        sb.append("> 口径：初审通过率 = 初审通过物料数 ÷ 参与初审物料数；")
                .append("解析失败物料不计入分母，单独列出。\n")
                .append("> \"初审通过\"仅表示 AI 未发现明显风险，**不等于法务最终批准**。\n\n");
        sb.append("## 二、风险明细\n\n");
        for (RiskSummary r : request.risks()) {
            sb.append("### ").append(r.riskNo()).append(" · ").append(r.riskLevel()).append("\n\n");
            sb.append("- 物料：").append(r.materialName()).append("\n");
            sb.append("- 风险原文：").append(r.riskText()).append("\n");
            sb.append("- 位置：").append(r.locationDesc()).append("\n");
            sb.append("- 原因：").append(r.reason()).append("\n");
            sb.append("- 修改建议：").append(r.suggestion()).append("\n");
            sb.append("- 推荐表达：").append(r.recommendedCopy()).append("\n\n");
        }
        return sb.toString();
    }

    @Override
    public String generateCommunicationScript(ScriptRequest request) {
        return String.format("""
                【需要修改】%s

                问题：%s
                位置：%s

                建议改为：%s

                如需保留原表达，请提供：%s

                —— 本话术由 AI 生成，供沟通参考，最终以法务意见为准。
                """,
                request.riskText(),
                request.reason(),
                request.locationDesc(),
                request.recommendedCopy() == null ? request.suggestion() : request.recommendedCopy(),
                request.requiredEvidence() == null ? "相应证明材料" : request.requiredEvidence());
    }

    /**
     * AI 法务助手：单条咨询。
     *
     * <p>Mock 实现基于关键词规则，目的是让「助手能对话」这条链路真正跑通：
     * 用户输入文案 → 助手识别风险点 → 给出依据、改写建议与所需材料 → 可转正式审核。
     *
     * <p>⚠️ 它<b>不是</b>真实的法律智能：回答内容来自固定规则模板，
     * 只用于验证交互与数据流。接入真实模型后（GW_AI_PROVIDER=dashscope）
     * 由大模型生成回答，本实现将被替换。
     */
    @Override
    public ConsultationAnswer consult(ConsultationRequest request) {
        long t0 = System.currentTimeMillis();
        String question = request.question() == null ? "" : request.question();

        List<TraceStep> trace = new ArrayList<>();
        trace.add(new TraceStep("意图识别",
                "判断为单条文案风险咨询，非正式审核任务",
                List.of("输入长度 " + question.length() + " 字"
                        + (request.attachment() != null ? "，含 1 个临时附件" : "")),
                System.currentTimeMillis() - t0));

        // 1. 在输入中命中规则
        long t1 = System.currentTimeMillis();
        List<RiskDraft> risks = new ArrayList<>();
        List<String> hitKeywords = new ArrayList<>();

        // 用共享规则表匹配，其内部已处理「被更长命中词包含则剔除」的去重：
        // 否则「行业第一」会同时命中「行业第一」与「第一」，生成两条重复风险
        for (RiskKeywordRules.Rule rule : RiskKeywordRules.match(question)) {
            hitKeywords.add(rule.keyword());
            risks.add(new RiskDraft(
                    rule.type().name(), rule.level().name(), rule.keyword(),
                    new RiskDraft.Location(List.of(), "NOT_APPLICABLE", "用户输入文本"),
                    rule.reason(), 0.85,
                    legalRefsFor(rule.type().name()),
                    List.of(), List.of(),
                    "建议删除或改为有依据的客观表述",
                    recommendFor(rule.keyword()),
                    "如保留原表述，需提供第三方检测报告或统计数据来源",
                    "OPEN"));
        }
        trace.add(new TraceStep("规则检索",
                risks.isEmpty() ? "未命中明确风险规则" : "命中 " + risks.size() + " 处风险表述",
                risks.isEmpty() ? List.of("输入中未发现绝对化用语、无依据数据等典型表述")
                        : List.of("命中：" + String.join("、", hitKeywords)),
                System.currentTimeMillis() - t1));

        // 2. 组装回答
        long t2 = System.currentTimeMillis();
        String reply = buildConsultationReply(question, risks);
        trace.add(new TraceStep("结论生成",
                risks.isEmpty() ? "给出无明确风险的说明" : "给出风险说明与改写建议",
                List.of("结论仅为咨询参考，不构成法务最终意见"),
                System.currentTimeMillis() - t2));

        // 3. 是否需要转正式审核：命中 HIGH 风险或输入较长时建议转
        boolean needsFormal = risks.stream().anyMatch(r -> "HIGH".equals(r.riskLevel()))
                || question.length() > 200;

        return new ConsultationAnswer(reply, risks, trace, needsFormal);
    }

    /** 针对具体命中词给出更贴切的推荐表达，而不是千篇一律的模板 */
    private String recommendFor(String keyword) {
        return switch (keyword) {
            case "行业第一", "第一", "最好" ->
                    "改为可验证的客观表述，例如「XX 项指标达到行业领先水平」并注明数据来源与统计口径";
            case "100%", "保障安全" ->
                    "改为「通过 XX 项安全测试」并附检测机构与报告编号";
            case "1000km" ->
                    "补上测试工况，例如「CLTC 工况续航最高 1000 公里」";
            case "最低价" ->
                    "标明适用范围与期限，例如「XX 期间指定渠道享最低价」";
            case "领先" ->
                    "补充对比依据与统计口径，或改为不带比较含义的表述";
            default -> "改为有依据、可验证的表述";
        };
    }

    private String buildConsultationReply(String question, List<RiskDraft> risks) {
        if (risks.isEmpty()) {
            return """
                    这段内容没有命中我掌握的明确风险规则。

                    需要说明的是：这不等于「一定合规」。如果有具体的产品参数、\
                    已获批的宣传口径或第三方检测报告，可以一并提供，我再做针对性核查。

                    如果这是要对外发布的一批材料，建议走正式审核流程，由法务出具意见。
                    """;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("这句话里有 ").append(risks.size()).append(" 处需要处理的地方，性质不同，建议分开看：\n\n");
        int i = 1;
        for (RiskDraft r : risks) {
            sb.append(i++).append(". 「").append(r.riskText()).append("」——")
              .append(levelLabel(r.riskLevel())).append("\n")
              .append("   依据：").append(describeBasis(r.riskType())).append("\n")
              .append("   ").append(r.reason()).append("\n")
              .append("   建议改为：").append(r.recommendedCopy()).append("\n\n");
        }
        sb.append("如果确实需要保留原表述，需要补充：")
          .append(risks.get(0).requiredEvidence()).append("\n\n");
        sb.append("以上是单条咨询意见，供参考。如果这是要对外发布的一批材料，")
          .append("建议转为正式审核任务，由法务逐条确认并留痕。");
        return sb.toString();
    }

    /**
     * 依据描述：从知识库查出该风险类型对应的法规与条款。
     *
     * <p>找不到时明确说明"未检索到明确条款"，而不是硬凑一条依据——
     * 这对应 AGENTS.md 第 11 条：模型无法获取适用规则时应说明不确定性，
     * 不得虚构法条。
     */
    private String describeBasis(String riskType) {
        try {
            List<LegalBasisMapper.LegalBasisRow> rows = legalBasisMapper.findByRiskType(riskType);
            if (rows == null || rows.isEmpty()) {
                return "未检索到明确对应的条款，建议由法务人工确认";
            }
            LegalBasisMapper.LegalBasisRow head = rows.get(0);
            String clause = pickRelevantClause(riskType, rows);
            return "《" + head.getLawTitle() + "》" + clause;
        } catch (Exception e) {
            log.warn("法定依据查询失败: riskType={} err={}", riskType, e.getMessage());
            return "依据检索暂时不可用，建议由法务人工确认";
        }
    }

    /**
     * 规则 → 条款的关键词线索已提取到
     * {@link com.guangxuan.audit.infra.provider.RiskKeywordRules#clauseHint(String)}。
     */

    /**
     * 从该规则关联的条款切片中选出最相关的一条。
     *
     * <p>实现已提取到 {@link com.guangxuan.audit.infra.legal.LegalClausePicker}，
     * 因为"选哪一条条款"有三处调用方（初审引用、反馈区依据还原、报告生成），
     * 各写一份迟早出现"详情说第九条、报告写第四条"。
     */
    private String pickRelevantClause(String riskType, List<LegalBasisMapper.LegalBasisRow> rows) {
        return com.guangxuan.audit.infra.legal.LegalClausePicker.pick(riskType, rows);
    }

    private String levelLabel(String level) {
        return switch (level) {
            case "HIGH" -> "属于较明确的违规风险，通常不能使用";
            case "MEDIUM" -> "存在被认定为误导的可能，需补充依据";
            default -> "建议优化表述";
        };
    }

    /**
     * 该风险类型对应的知识库版本 ID，作为 {@code rule_references} 写入 Risk Case。
     *
     * <p>只引用知识库里真实存在的 {@code kbItemVersionId}——这是防虚构法条的机制：
     * 模型无法凭空造出合法 ID。查不到就返回空数组，由上层据此转人工判断。
     */
    private List<RiskDraft.RuleReference> legalRefsFor(String riskType) {
        try {
            List<LegalBasisMapper.LegalBasisRow> rows = legalBasisMapper.findByRiskType(riskType);
            if (rows == null || rows.isEmpty()) {
                return List.of();
            }
            List<RiskDraft.RuleReference> refs = new ArrayList<>();
            java.util.Set<Long> seen = new java.util.HashSet<>();
            for (LegalBasisMapper.LegalBasisRow r : rows) {
                if (r.getKbVersionId() == null || !seen.add(r.getKbVersionId())) {
                    continue;
                }
                // 引用文本也用条款匹配的结果，保证与回复正文里的依据一致；
                // 若直接用该法规的第一条，会出现"正文说第九条、字段里写第四条"的矛盾
                List<LegalBasisMapper.LegalBasisRow> sameVersion = rows.stream()
                        .filter(x -> r.getKbVersionId().equals(x.getKbVersionId()))
                        .toList();
                String quote = pickRelevantClause(riskType, sameVersion);
                if (quote.length() > 120) {
                    quote = quote.substring(0, 120);
                }
                refs.add(new RiskDraft.RuleReference(r.getKbVersionId(),
                        r.getRuleCode() == null ? riskType : r.getRuleCode(), quote));
            }
            return refs;
        } catch (Exception e) {
            log.warn("法定依据引用查询失败: riskType={} err={}", riskType, e.getMessage());
            return List.of();
        }
    }
}
