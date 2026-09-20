package com.guangxuan.audit.infra.provider.mock;

import com.guangxuan.audit.common.enums.RiskLevel;
import com.guangxuan.audit.common.enums.RiskType;
import com.guangxuan.audit.domain.ai.RiskDraft;
import com.guangxuan.audit.domain.port.AiInferencePort;
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
@ConditionalOnProperty(name = "gw.ai.provider", havingValue = "mock", matchIfMissing = true)
public class MockAiAdapter implements AiInferencePort {

    /**
     * 关键词 → 风险类型 / 等级 的映射。
     *
     * <p>覆盖 AGENTS.md 第 6 条列举的主要风险类型，便于演示"一张海报多个独立风险"
     * 会生成多条可分别处理的 Risk Case。
     */
    private static final Map<String, Rule> RULES = new LinkedHashMap<>();

    private record Rule(RiskType type, RiskLevel level, String reason) {
    }

    static {
        RULES.put("行业第一", new Rule(RiskType.ABSOLUTE_CLAIM, RiskLevel.HIGH,
                "涉嫌使用绝对化用语，违反广告法对绝对化宣传的限制"));
        RULES.put("第一", new Rule(RiskType.ABSOLUTE_CLAIM, RiskLevel.HIGH,
                "涉嫌使用绝对化用语"));
        RULES.put("100%", new Rule(RiskType.SAFETY_PROMISE, RiskLevel.HIGH,
                "作出绝对化安全承诺，需有充分依据并可能违反安全承诺相关规定"));
        RULES.put("保障安全", new Rule(RiskType.SAFETY_PROMISE, RiskLevel.HIGH,
                "作出安全承诺，需提供权威检测或认证依据"));
        RULES.put("1000km", new Rule(RiskType.EVIDENCE_MISSING, RiskLevel.MEDIUM,
                "续航数据未标注数据来源与测试工况，缺乏证明材料"));
        RULES.put("最低价", new Rule(RiskType.PRICE_CLAIM, RiskLevel.MEDIUM,
                "价格宣传需标明适用范围、期限与依据"));
        RULES.put("最好", new Rule(RiskType.ABSOLUTE_CLAIM, RiskLevel.HIGH,
                "涉嫌使用绝对化用语"));
        RULES.put("领先", new Rule(RiskType.COMPETITOR_COMPARISON, RiskLevel.MEDIUM,
                "竞品比较表述缺乏依据与统计口径"));
    }

    @Override
    public List<RiskDraft> identifyCandidates(CandidateRequest request) {
        List<RiskDraft> drafts = new ArrayList<>();
        for (AnchorLine line : request.anchorLines()) {
            String text = line.text();
            if (text == null || text.isBlank()) {
                continue;
            }
            for (Map.Entry<String, Rule> e : RULES.entrySet()) {
                if (!text.contains(e.getKey())) {
                    continue;
                }
                Rule rule = e.getValue();
                drafts.add(new RiskDraft(
                        rule.type().name(),
                        rule.level().name(),
                        e.getKey(),
                        new RiskDraft.Location(List.of(line.anchorId()), "NOT_APPLICABLE",
                                "锚点 " + line.anchorId()),
                        rule.reason(),
                        0.85,
                        List.of(),
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
}
