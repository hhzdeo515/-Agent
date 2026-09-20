package com.guangxuan.audit.domain.port;

import com.guangxuan.audit.domain.ai.RiskDraft;

import java.util.List;

/**
 * AI 推理端口：五阶段流水线中除"内容解析"外的四个阶段。
 *
 * <p>实现可以是百炼（qwen3-vl / qwen-vl-ocr / 文本主力模型），
 * 也可以是 Mock（无 API Key 时用于端到端联调）。
 *
 * <p><b>铁律</b>：本接口的任何实现都<b>不得</b>执行确认风险、判误判、签名、关闭、
 * 批准这些动作——那些是法务的判断。AI 只能产出草稿与建议
 * （AGENTS.md 第 1、8 条）。
 */
public interface AiInferencePort {

    /**
     * 阶段 2：候选风险识别（宽召回）。
     *
     * <p>目标是"宁可多出待人工判断，不可漏"——AGENTS.md 第 15 条明确
     * 涉及高风险漏检的测试优先级高于文案风格优化。因此本阶段允许含噪声。
     */
    List<RiskDraft> identifyCandidates(CandidateRequest request);

    /**
     * 阶段 4：风险判断（收紧误报）。
     *
     * <p>输入是候选风险 + 已检索到的依据；输出是带等级、置信度与推荐状态的草稿。
     * 与阶段 2 目标相反，因此刻意分两次调用而不是合成一次。
     */
    List<RiskDraft> judge(JudgeRequest request);

    /**
     * 阶段 5 之后：生成 Markdown 初审报告。
     *
     * <p><b>只能基于已校验的 Risk Case 数据组织语言</b>，不得新增、删除或改变
     * 任何风险的数量、等级与结论——否则报告会与数据库矛盾（AGENTS.md 第 11 条）。
     */
    String generateReport(ReportRequest request);

    /** 生成面向业务方的沟通话术（AGENTS.md 第 6 条） */
    String generateCommunicationScript(ScriptRequest request);

    // ── 请求 DTO ────────────────────────────────────────────────────────

    /** @param anchorLines 锚点清单（ID + 文本），模型只能引用清单内的 ID */
    record CandidateRequest(String materialType, String contentText,
                            List<AnchorLine> anchorLines, String reviewRequirement) {
    }

    record AnchorLine(String anchorId, String type, String text, Long beginMs, Long endMs) {
    }

    record JudgeRequest(List<RiskDraft> candidates, List<RetrievedRule> retrievedRules,
                        String productContext, String reviewRequirement) {
    }

    record RetrievedRule(long kbItemVersionId, String ruleCode, String title,
                         String content, String effectiveStatus, String sourceNote) {
    }

    record ReportRequest(Long caseId, String caseName,
                         int reviewedMaterialCount, int parseFailedCount,
                         int initialPassCount, int pendingHumanCount, int riskFailCount,
                         double initialPassRate,
                         List<RiskSummary> risks) {
    }

    record RiskSummary(String riskNo, String materialName, String riskText, String locationDesc,
                       String riskLevel, double confidence, String reason, String basis,
                       String evidence, String suggestion, String recommendedCopy,
                       String requiredEvidence) {
    }

    /** @param audience BRAND / DESIGN / BIZ / MARKET */
    record ScriptRequest(String audience, String riskText, String locationDesc,
                         String riskLevel, String reason, String suggestion,
                         String recommendedCopy, String requiredEvidence) {
    }
}
