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

    /**
     * AI 法务助手：单条咨询（AGENTS.md 第 3 条）。
     *
     * <p>与上面的正式审核链路的区别：这里回答的是「这一句话有没有问题」这类零散问题，
     * 产出的是<b>咨询结论</b>而非 Risk Case。
     *
     * <p>实现必须遵守两条边界：
     * <ul>
     *   <li>没有充分依据时明确说明不确定性，不得虚构法条、案例、产品参数或证明材料；</li>
     *   <li>涉及需要专业判断的情形，应建议转正式审核，而不是给出确定结论。</li>
     * </ul>
     *
     * @return 自然语言回答 + 其中的风险点 + 推理过程（供前端展示"AI 是怎么想的"）
     */
    ConsultationAnswer consult(ConsultationRequest request);

    /**
     * @param question   用户输入（一句宣传语、一段文案，或一个法律问题）
     * @param attachment 可选的临时附件（图片/文档的已提取文本）；助手不接收正式物料
     */
    record ConsultationRequest(String question, Attachment attachment) {
    }

    /** @param extractedText 已解析出的文本内容；空表示仅凭文件名无法分析 */
    record Attachment(String name, String mimeType, String extractedText) {
    }

    /**
     * @param reply 自然语言回答
     * @param risks 回答中涉及的风险点（可为空，例如用户只是问一个法律概念）
     * @param trace 推理过程，前端折叠展示
     * @param needsFormalReview 是否建议转正式审核
     */
    record ConsultationAnswer(String reply, List<RiskDraft> risks, List<TraceStep> trace,
                              boolean needsFormalReview) {
        public ConsultationAnswer {
            risks = risks == null ? List.of() : List.copyOf(risks);
            trace = trace == null ? List.of() : List.copyOf(trace);
        }
    }

    /**
     * @param stage   阶段名，如「规则检索」
     * @param summary 一句话结果
     * @param details 关键细节
     * @param costMs  耗时
     */
    record TraceStep(String stage, String summary, List<String> details, long costMs) {
        public TraceStep {
            details = details == null ? List.of() : List.copyOf(details);
        }
    }

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
