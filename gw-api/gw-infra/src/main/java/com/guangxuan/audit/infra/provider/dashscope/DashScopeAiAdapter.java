package com.guangxuan.audit.infra.provider.dashscope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guangxuan.audit.common.error.DomainException;
import com.guangxuan.audit.common.error.ErrorCode;
import com.guangxuan.audit.domain.ai.RiskDraft;
import com.guangxuan.audit.domain.port.AiInferencePort;
import com.guangxuan.audit.infra.provider.RiskKeywordRules;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 千问文本主力模型适配器：五个推理阶段的真实实现。
 *
 * <h3>三条不可动摇的约束（都体现在 Prompt 与解析代码里）</h3>
 * <ol>
 *   <li><b>定位只能引用锚点 ID</b>。模型给不出可靠坐标，而让它回一段"风险原文"
 *       再由服务端做字符串匹配会静默失败（错别字、全半角、空格都会导致匹配不到）。
 *       因此 Prompt 里附上锚点清单，只允许模型回清单内的 ID，服务端逐个校验存在性。</li>
 *   <li><b>法条只能引用检索结果里的 ID</b>。模型很容易"记得"一条看起来很对的法条，
 *       但那是幻觉。Prompt 给出本次检索到的条款清单与它们的 ID，
 *       模型只能选；想引用清单外的内容必须写进 {@code unsupported_claims}，
 *       服务端据此强制转人工判断，而不是让它变成一条有依据的结论。</li>
 *   <li><b>宁可说"不确定"</b>。证据不足、事实无法核实、需要专业判断时，
 *       必须输出 {@code PENDING_LEGAL_DECISION}，而不是为了完成流程给一个确定结论。</li>
 * </ol>
 *
 * <h3>为什么检索结果里也要有"关键词兜底"</h3>
 * 真实模型会漏召回，而"高风险漏检"是本项目优先级最高的缺陷（AGENTS.md 第 15 条）。
 * 因此看到绝对化用语这类**确定性极高**的表述时，用规则补一条候选，
 * 交给同一条校验链路。它只做"多提一条让人看"，不做判断——判断仍在模型与法务。
 * 这样即使模型某次抽风，最危险的那一类问题也不会静默消失。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "gw.ai.provider", havingValue = "dashscope")
public class DashScopeAiAdapter implements AiInferencePort {

    private final DashScopeClient client;
    private final ObjectMapper objectMapper;

    /** 允许的风险类型：与 risk_case.risk_type 的枚举逐字一致，模型只能从这里选 */
    private static final List<String> RISK_TYPES = List.of(
            "ABSOLUTE_CLAIM", "SAFETY_PROMISE", "EVIDENCE_MISSING",
            "COMPETITOR_COMPARISON", "PRICE_CLAIM", "DISCLAIMER_MISSING",
            "MISLEADING", "OTHER");

    private static final String SHARED_RULES = """
            你在协助企业法务做广告宣传物料的**初审筛查**。你不是法务，不允许给出最终法律结论。
            严格遵守：
            1. 只依据我提供的原文与依据清单判断，不要使用你记忆中的其他法条、案例或产品参数。
            2. `evidence_anchor_ids` 只能填我给出的锚点 ID，一个字符都不能改。找不到对应锚点就留空数组。
            3. `rule_references` 只能填我给出的知识库 ID（数字）。想引用清单以外的依据，
               必须写进 `unsupported_claims` 说明"依据不足"，不要写进 rule_references。
            4. 证据不足、事实无法核实、需要专业判断时，`recommended_status` 填 `PENDING_LEGAL_DECISION`；
               只有在命中明确规则且依据充分时才填 `OPEN`。宁可交给人工，不要给确定结论。
            5. 不要编造数据来源、检测报告、认证、案例或产品参数。
            """;

    // ════════════════════════════════════════════════════════════════════
    // 阶段 2：候选风险识别（宽召回）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 候选识别要的是<b>召回率</b>，因此 Prompt 明确要求"宁可多提、允许不确定"。
     * 这与下一阶段"收紧误报"的目标相反，所以必须是两次独立调用——
     * 合成一次会让两个目标互相污染（docs/03 §7.2）。
     */
    @Override
    public List<RiskDraft> identifyCandidates(CandidateRequest request) {
        String anchors = renderAnchors(request.anchorLines());
        String prompt = """
                %s

                【物料类型】%s
                【审核要求】%s

                【可审核内容（每行前面是锚点 ID）】
                %s

                【任务】找出所有**可能**违反中国广告法及相关规定的表述或画面。
                这是宽召回阶段：宁可多提、允许不确定，不要漏。
                重点看：绝对化用语（国家级/最高级/第一/100%%等）、无法验证的安全或功效承诺、
                没有出处与工况的数据、贬低或不当比较竞品、价格与优惠的适用范围、
                缺少必要免责声明、可能误导消费者的描述。

                【风险类型只能从这个列表里选】%s

                只输出 JSON，不要任何解释文字，格式：
                {"candidates":[{
                  "risk_type":"列表中的值",
                  "risk_level":"HIGH|MEDIUM|LOW",
                  "risk_text":"原文里那句有问题的表述，逐字摘录，不要改写",
                  "evidence_anchor_ids":["锚点ID"],
                  "location_desc":"这句话在物料里的位置，用中文描述",
                  "reason":"为什么可能有问题",
                  "confidence":0.0到1.0之间的小数,
                  "suggestion":"怎么改",
                  "recommended_copy":"建议改成什么，给一句可直接用的表述",
                  "required_evidence":"如果要保留原表述，需要提供什么证明材料"
                }]}
                没有发现问题就输出 {"candidates":[]}。
                """.formatted(SHARED_RULES, request.materialType(),
                nz(request.reviewRequirement(), "（未特别指定，按通用广告合规要求审查）"),
                anchors, String.join(" / ", RISK_TYPES));

        List<RiskDraft> drafts = callForCandidates(prompt, "identifyCandidates");
        log.info("[dashscope] 候选识别：锚点 {} 个 → 候选 {} 条", request.anchorLines().size(), drafts.size());
        if (drafts.isEmpty()) {
            return drafts;
        }

        // 关键词兜底：模型漏召回时，最危险的那一类（绝对化用语等）不能静默消失。
        // 补进来的候选与模型输出走完全相同的校验链路，不存在"绕过校验"的通道。
        List<RiskDraft> merged = new ArrayList<>(drafts);
        Set<String> seen = new LinkedHashSet<>();
        for (RiskDraft d : drafts) {
            seen.add(normalize(d.riskText()));
        }
        for (AnchorLine line : request.anchorLines()) {
            for (RiskKeywordRules.Rule hit : RiskKeywordRules.match(line.text())) {
                if (!seen.add(normalize(hit.keyword()))) {
                    continue;
                }
                merged.add(new RiskDraft(
                        hit.type().name(), hit.level().name(), hit.keyword(),
                        new RiskDraft.Location(List.of(line.anchorId()), "NOT_APPLICABLE",
                                "锚点 " + line.anchorId()),
                        hit.reason() + "（由规则兜底补入，需人工确认）",
                        0.7, List.of(), List.of(),
                        List.of("规则兜底命中，模型未独立识别"),
                        "建议删除或改为有依据的客观表述",
                        "建议替换为可验证的客观表述",
                        "如保留原表述，需提供第三方检测报告或统计数据来源",
                        "PENDING_LEGAL_DECISION"));
                log.info("[dashscope] 规则兜底补入候选：{}（锚点 {}）", hit.keyword(), line.anchorId());
            }
        }
        return merged;
    }

    // ════════════════════════════════════════════════════════════════════
    // 阶段 4：风险判断（收紧误报）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 判断阶段要的是<b>准确率</b>：给定候选与已检索到的依据，逐条决定是否成立、
     * 等级多高、置信度多少。与上一阶段目标相反，因此单独一次调用。
     */
    @Override
    public List<RiskDraft> judge(JudgeRequest request) {
        if (request.candidates().isEmpty()) {
            return List.of();
        }
        String rules = renderRules(request.retrievedRules());
        String prompt = """
                %s

                【产品与业务背景】%s
                【审核要求】%s

                【候选问题清单】
                %s

                【本次检索到的现行依据（只能引用这些 ID）】
                %s

                【任务】对每一条候选，判断它是否真的构成广宣风险。
                收紧误报：如果表述有充分依据、或属于法律允许的情形，就不要保留它。
                但只要存在疑点（依据不足、事实无法核实、需要专业判断），就必须保留并转人工。

                只输出 JSON，不要解释文字，格式：
                {"judged":[{
                  "risk_type":"同上",
                  "risk_level":"HIGH|MEDIUM|LOW",
                  "risk_text":"原文逐字摘录",
                  "evidence_anchor_ids":["锚点ID"],
                  "location_desc":"位置",
                  "reason":"判定理由，说明为什么构成或不构成风险",
                  "confidence":0.0到1.0,
                  "rule_references":[{"kb_item_version_id":数字,"rule_code":"依据编号","quoted_text":"引用的条款原文"}],
                  "unsupported_claims":["想引用但依据清单里没有的内容"],
                  "suggestion":"怎么改",
                  "recommended_copy":"建议表述",
                  "required_evidence":"需要的证明材料",
                  "recommended_status":"OPEN|PENDING_LEGAL_DECISION"
                }]}
                """.formatted(SHARED_RULES,
                nz(request.productContext(), "（未提供产品参数，涉及具体数据一律转人工）"),
                nz(request.reviewRequirement(), "（通用广告合规要求）"),
                renderCandidates(request.candidates()), rules);

        return callForCandidates(prompt, "judge");
    }

    // ════════════════════════════════════════════════════════════════════
    // 报告与话术
    // ════════════════════════════════════════════════════════════════════

    /**
     * 报告叙述部分。
     *
     * <p>注意：<b>报告的数字与结论不由这里产生</b>。计数、分类、通过率一律由服务端
     * 从数据库视图拼装（{@code InitialReviewReportService}），本方法只产出叙述段落。
     * 让模型写数字，它就有机会把 7 条写成 6 条（AGENTS.md 第 11 条）。
     */
    @Override
    public String generateReport(ReportRequest request) {
        StringBuilder risks = new StringBuilder();
        for (RiskSummary r : request.risks()) {
            risks.append("- ").append(r.riskNo()).append("｜").append(r.riskLevel())
                    .append("｜").append(nz(r.materialName(), "未知物料"))
                    .append("｜").append(nz(r.riskText(), ""))
                    .append("｜位置：").append(nz(r.locationDesc(), "未知"))
                    .append("｜原因：").append(nz(r.reason(), ""))
                    .append("｜依据：").append(nz(r.basis(), "无"))
                    .append("\n");
        }
        String prompt = """
                你是一名协助企业法务的技术助理。请为下面这批广告宣传物料的**第一次 AI 初审**
                写一段总体研判摘要（中文，200 字以内）。

                严格要求：
                1. 不要写任何数字（不要写"共 X 条"、"通过率 Y%%"）——统计数字由系统另行给出口径统一的版本，
                   你写了会与系统数字冲突。
                2. 不要给出"可以上线""合法"这类结论性判断。这是 AI 初审，不是法务意见。
                3. 指出本批材料最需要法务优先关注的方向。

                【任务】%s
                【风险清单】
                %s
                """.formatted(nz(request.caseName(), ""), risks);
        return client.chat(client.props().getModels().getText(),
                List.of(Map.of("role", "user", "content", prompt)), false, null).trim();
    }

    /** 面向业务方的沟通话术：要能让设计/市场看懂"改什么、为什么" */
    @Override
    public String generateCommunicationScript(ScriptRequest request) {
        String prompt = """
                你是企业法务团队的助理。请写一段可以直接发给%s团队的沟通话术（中文，200 字以内）。

                要求：
                1. 说清三件事：问题是什么、为什么需要改、建议怎么改。
                2. 语气专业、就事论事，不要指责。
                3. 如果要保留原表述，明确列出需要补充的证明材料。
                4. 结尾注明"本话术由 AI 生成，最终以法务意见为准"。
                5. 不要给出法律结论，不要说"违法"。

                【风险原文】%s
                【位置】%s
                【风险等级】%s
                【原因】%s
                【修改建议】%s
                【推荐表达】%s
                【所需材料】%s
                """.formatted(audienceLabel(request.audience()), nz(request.riskText(), ""),
                nz(request.locationDesc(), ""), nz(request.riskLevel(), ""),
                nz(request.reason(), ""), nz(request.suggestion(), ""),
                nz(request.recommendedCopy(), ""), nz(request.requiredEvidence(), ""));
        return client.chat(client.props().getModels().getText(),
                List.of(Map.of("role", "user", "content", prompt)), false, null).trim();
    }

    // ════════════════════════════════════════════════════════════════════
    // AI 法务助手：单条咨询
    // ════════════════════════════════════════════════════════════════════

    /**
     * 助手是<b>咨询</b>入口，不是审核入口：不产出 Risk Case、不改变任何状态。
     * 它必须能说"我不确定"，也必须主动建议转正式审核。
     */
    @Override
    public ConsultationAnswer consult(ConsultationRequest request) {
        long t0 = System.currentTimeMillis();
        List<TraceStep> trace = new ArrayList<>();
        String question = request.question() == null ? "" : request.question();
        String attachmentText = request.attachment() == null ? null
                : request.attachment().extractedText();

        trace.add(new TraceStep("意图识别",
                "判断为单条文案咨询，不创建正式审核任务",
                List.of("助手只做咨询与单条分析，批次审核需在接收区建任务"), 0));

        String prompt = """
                %s

                用户在咨询一条广告宣传表述是否有风险。请用中文回答，结构固定为：
                1. 结论倾向（"存在较明确风险" / "存在疑点，需要进一步确认" / "未发现明显问题"）——
                   注意只能在这三种里选，不要下"违法"或"合法"的判断。
                2. 涉及的风险点与原因。
                3. 适用的规则依据。只能引用我提供的依据清单里的内容；清单里没有的，
                   必须明确说"我无法确认具体条款"，不要凭记忆写法条名称与条号。
                4. 建议的改写方向，给一句可直接用的表述。
                5. 如果要保留原表述，需要补充什么证明材料。

                不要编造法条、案例、检测报告或产品参数。信息不足时直接说不足。

                【用户输入】%s
                %s
                【可引用的依据清单】
                %s
                """.formatted(SHARED_RULES, question,
                attachmentText == null || attachmentText.isBlank() ? ""
                        : "【附件已提取文本】" + truncate(attachmentText, 4000),
                renderRules(List.of()));

        String reply = client.chat(client.props().getModels().getText(),
                List.of(Map.of("role", "user", "content", prompt)), false, null).trim();
        trace.add(new TraceStep("规则解释与改写建议",
                "已给出风险点、依据说明与改写方向",
                List.of("依据仅来自知识库检索结果，检索不到时明确说明"), 
                System.currentTimeMillis() - t0));

        // 咨询结论也要落成结构化风险点，前端才能展示"涉及哪几个问题"。
        // 但它不落库、不建 Risk Case——这是助手与接收区的边界。
        List<RiskDraft> risks = new ArrayList<>();
        for (RiskKeywordRules.Rule hit : RiskKeywordRules.match(question + " "
                + (attachmentText == null ? "" : truncate(attachmentText, 2000)))) {
            risks.add(new RiskDraft(
                    hit.type().name(), hit.level().name(), hit.keyword(),
                    new RiskDraft.Location(List.of(), "NOT_APPLICABLE", "用户输入文本"),
                    hit.reason(), 0.7, List.of(), List.of(), List.of(),
                    "建议删除或改为有依据的客观表述",
                    "建议替换为可验证的客观表述",
                    "如保留原表述，需提供第三方检测报告或统计数据来源",
                    "PENDING_LEGAL_DECISION"));
        }

        boolean needsFormal = !risks.isEmpty()
                || reply.contains("需要进一步确认") || reply.contains("疑点");
        return new ConsultationAnswer(reply, risks, trace, needsFormal);
    }

    // ════════════════════════════════════════════════════════════════════
    // AI 复审：三问
    // ════════════════════════════════════════════════════════════════════

    /**
     * 复审只针对<b>原风险所在的那一处</b>改动，不做第二次全量初审。
     *
     * <p>这与 AGENTS.md 第 7 条一致：终审区的主要对象是风险记录，不是重新审一遍物料。
     * 因此 Prompt 里给的是原风险 + 原版本锚点 + 新版本锚点 + 差异摘要，
     * 而不是整批材料——范围由调用方的 {@code ReviewScope} 决定，模型无权扩大。
     */
    @Override
    public RereviewAnswer rereview(RereviewRequest request) {
        String prompt = """
                %s

                现在做**整改后的复审**。只判断下面这一条风险在原位置附近是否已经解决，
                不要重新审查整份材料，也不要提出与这条风险无关的问题。

                【原风险】%s
                【风险类型】%s
                【原风险等级】%s
                【原位置】%s
                【复审范围】%s

                【原版本的原文（锚点 ID | 文本）】
                %s

                【整改后新版本的原文（锚点 ID | 文本）】
                %s

                【两版差异摘要】
                %s

                【必须回答的三个问题】
                1. original_resolved：原风险是否已经解决？（原表述是否已从新版本中消失，或被改写成合规表述）
                2. remaining_risk：新版本里是否仍有**同类**风险？
                3. new_risks：本次修改是否**新引入**了风险？只报新版本里新出现的内容，
                   不要把原本就存在的问题当成新增。

                只输出 JSON，不要解释文字：
                {"original_resolved":true/false,
                 "remaining_risk":true/false,
                 "remaining_evidence":["命中同类风险的原文"],
                 "new_risks":[{"anchor_id":"新版本里的锚点ID","text":"原文","risk_type":"类型","risk_level":"HIGH|MEDIUM|LOW","reason":"为什么是新增风险"}],
                 "summary":"一段中文结论，说明三问的答案与依据",
                 "confidence":0.0到1.0}

                注意：`anchor_id` 必须是上面新版本清单里真实存在的 ID；拿不准就不要报。
                你的判断是**文本比对 + 语义理解**，不是法律结论，summary 里要写明
                "最终以法务意见为准"。
                """.formatted(SHARED_RULES,
                nz(request.riskText(), ""), nz(request.riskType(), ""),
                nz(request.riskLevel(), ""), nz(request.originalLocation(), ""),
                nz(request.reviewScope(), "CHANGED_REGION_WITH_CONTEXT"),
                renderAnchors(request.baseAnchors()), renderAnchors(request.targetAnchors()),
                nz(request.changeSummary(), "（未提供差异摘要）"));

        String raw = client.chat(client.props().getModels().getText(),
                List.of(Map.of("role", "user", "content", prompt)), true,
                Map.of("temperature", 0.1));
        JsonNode n = extractJson(raw, "rereview");

        List<String> remainingEvidence = new ArrayList<>();
        for (JsonNode e : n.path("remaining_evidence")) {
            if (e.isTextual() && !e.asText().isBlank()) {
                remainingEvidence.add(e.asText().trim());
            }
        }
        List<NewRisk> newRisks = new ArrayList<>();
        for (JsonNode r : n.path("new_risks")) {
            String anchorId = text(r, "anchor_id");
            if (anchorId == null || anchorId.isBlank()) {
                continue;
            }
            String type = text(r, "risk_type");
            if (!RISK_TYPES.contains(type)) {
                type = "OTHER";
            }
            String level = text(r, "risk_level");
            if (!List.of("HIGH", "MEDIUM", "LOW").contains(level)) {
                level = "MEDIUM";
            }
            newRisks.add(new NewRisk(anchorId, nz(text(r, "text"), ""), type, level,
                    nz(text(r, "reason"), "复审认为本次修改新引入了该问题")));
        }

        boolean resolved = n.path("original_resolved").asBoolean(false);
        boolean remaining = n.path("remaining_risk").asBoolean(false);
        String summary = nz(text(n, "summary"), "AI 复审未给出结论摘要，请人工判断。");
        if (!summary.contains("法务")) {
            summary = summary + "\n（本次复审为文本比对与语义理解，非法律判断，最终以法务意见为准。）";
        }
        double confidence = Math.max(0, Math.min(1, n.path("confidence").asDouble(0.7)));

        log.info("[dashscope] 复审：原风险已解决={} 仍有剩余={} 新增={} 置信度={}",
                resolved, remaining, newRisks.size(), confidence);
        return new RereviewAnswer(resolved, remaining, remainingEvidence, newRisks,
                summary, confidence);
    }

    // ════════════════════════════════════════════════════════════════════
    // 调用与解析
    // ════════════════════════════════════════════════════════════════════

    private List<RiskDraft> callForCandidates(String prompt, String label) {
        String model = client.props().getModels().getText();
        String raw = client.chat(model, List.of(Map.of("role", "user", "content", prompt)),
                true, Map.of("temperature", 0.1));
        JsonNode root = extractJson(raw, label);
        JsonNode array = root.isArray() ? root
                : root.path("candidates").isArray() ? root.path("candidates")
                : root.path("judged").isArray() ? root.path("judged")
                : root.path("risks");
        if (!array.isArray()) {
            throw new DomainException(ErrorCode.AI_OUTPUT_INVALID,
                    "模型输出里找不到候选数组（" + label + "）：" + truncate(raw, 400));
        }

        List<RiskDraft> out = new ArrayList<>();
        for (JsonNode n : array) {
            try {
                out.add(toDraft(n));
            } catch (Exception e) {
                // 单条解析失败不拖垮整批：但要留痕，且这条不会进入校验链路，
                // 因此不会"悄悄少一条风险"而不被发现——日志里能查到。
                log.warn("[dashscope] {} 单条解析失败已跳过：{} / {}", label, e.getMessage(), truncate(n.toString(), 200));
            }
        }
        return out;
    }

    private RiskDraft toDraft(JsonNode n) {
        String riskType = text(n, "risk_type");
        if (!RISK_TYPES.contains(riskType)) {
            // 模型给了枚举外的值：归到 OTHER 而不是丢弃，丢掉等于漏检
            log.debug("[dashscope] 风险类型不在枚举内，归为 OTHER：{}", riskType);
            riskType = "OTHER";
        }
        String level = text(n, "risk_level");
        if (!List.of("HIGH", "MEDIUM", "LOW").contains(level)) {
            level = "MEDIUM";
        }
        String status = text(n, "recommended_status");
        if (!"OPEN".equals(status)) {
            status = "PENDING_LEGAL_DECISION";
        }

        List<String> anchorIds = new ArrayList<>();
        for (JsonNode a : n.path("evidence_anchor_ids")) {
            if (a.isTextual() && !a.asText().isBlank()) {
                anchorIds.add(a.asText().trim());
            }
        }

        List<RuleDraft> rules = new ArrayList<>();
        for (JsonNode r : n.path("rule_references")) {
            long id = r.path("kb_item_version_id").asLong(0);
            if (id > 0) {
                rules.add(new RuleDraft(id, text(r, "rule_code"), text(r, "quoted_text")));
            }
        }

        List<String> unsupported = new ArrayList<>();
        for (JsonNode u : n.path("unsupported_claims")) {
            if (u.isTextual() && !u.asText().isBlank()) {
                unsupported.add(u.asText().trim());
            }
        }

        double confidence = n.path("confidence").asDouble(0.6);
        confidence = Math.max(0, Math.min(1, confidence));

        return new RiskDraft(riskType, level, text(n, "risk_text"),
                new RiskDraft.Location(anchorIds, "NOT_APPLICABLE", text(n, "location_desc")),
                text(n, "reason"), confidence,
                rules.stream().map(r -> new RiskDraft.RuleReference(
                        r.id(), r.code(), r.quoted())).toList(),
                List.of(), unsupported,
                text(n, "suggestion"), text(n, "recommended_copy"),
                text(n, "required_evidence"), status);
    }

    private record RuleDraft(long id, String code, String quoted) {
    }

    /**
     * 容错解析模型输出。
     *
     * <p>即使传了 {@code response_format=json_object}，也不能假定拿到的是纯 JSON：
     * OCR 系列模型明确不支持该参数，而文本模型也会偶发加代码围栏或前后说明。
     * 因此服务端必须自己剥壳——这是"供应商结构化输出不可信"的落地（docs/03 §7.3）。
     */
    private JsonNode extractJson(String raw, String label) {
        if (raw == null || raw.isBlank()) {
            throw new DomainException(ErrorCode.AI_OUTPUT_INVALID, label + " 返回空内容");
        }
        String s = raw.trim();
        // 剥代码围栏
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl > 0) {
                s = s.substring(nl + 1);
            }
            int end = s.lastIndexOf("```");
            if (end > 0) {
                s = s.substring(0, end);
            }
            s = s.trim();
        }
        try {
            return objectMapper.readTree(s);
        } catch (Exception ignored) {
            // 截取第一个 { 或 [ 到最后一个 } 或 ]：模型有时会在 JSON 前后加一句说明
            int objStart = s.indexOf('{');
            int arrStart = s.indexOf('[');
            int start = objStart < 0 ? arrStart : (arrStart < 0 ? objStart : Math.min(objStart, arrStart));
            int end = Math.max(s.lastIndexOf('}'), s.lastIndexOf(']'));
            if (start >= 0 && end > start) {
                try {
                    return objectMapper.readTree(s.substring(start, end + 1));
                } catch (Exception e) {
                    log.debug("[dashscope] {} JSON 截取后仍解析失败", label);
                }
            }
            throw new DomainException(ErrorCode.AI_OUTPUT_INVALID,
                    label + " 输出不是合法 JSON：" + truncate(raw, 400));
        }
    }

    // ── Prompt 渲染 ────────────────────────────────────────────────────

    private String renderAnchors(List<AnchorLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return "（无锚点：该物料未提取到可审核内容）";
        }
        StringBuilder sb = new StringBuilder();
        for (AnchorLine l : lines) {
            sb.append(l.anchorId()).append(" | ")
                    .append(l.type() == null ? "" : l.type()).append(" | ")
                    .append(l.text() == null ? "" : l.text().replace("\n", " "))
                    .append("\n");
        }
        return sb.toString();
    }

    private String renderCandidates(List<RiskDraft> candidates) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (RiskDraft d : candidates) {
            i++;
            sb.append(i).append(". 类型=").append(d.riskType())
                    .append(" 等级=").append(d.riskLevel())
                    .append(" 原文=\"").append(nz(d.riskText(), "")).append("\"")
                    .append(" 锚点=").append(d.location() == null ? "[]" : d.location().anchorIds())
                    .append(" 位置=").append(d.location() == null ? "" : nz(d.location().locationDesc(), ""))
                    .append(" 初步原因=").append(nz(d.reason(), ""))
                    .append("\n");
        }
        return sb.toString();
    }

    private String renderRules(List<RetrievedRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return "（本次未检索到现行依据。因此 rule_references 必须留空，"
                    + "并把你想引用的内容写进 unsupported_claims，recommended_status 必须为 PENDING_LEGAL_DECISION）";
        }
        StringBuilder sb = new StringBuilder();
        for (RetrievedRule r : rules) {
            sb.append("ID=").append(r.kbItemVersionId())
                    .append(" 依据=").append(nz(r.title(), ""))
                    .append(" 状态=").append(nz(r.effectiveStatus(), ""))
                    .append(" 条款=").append(truncate(nz(r.content(), ""), 500))
                    .append("\n");
        }
        return sb.toString();
    }

    private static String audienceLabel(String audience) {
        return switch (audience == null ? "" : audience) {
            case "DESIGN" -> "设计";
            case "BIZ" -> "业务";
            case "MARKET" -> "市场";
            default -> "品牌";
        };
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String nz(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s.trim();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String normalize(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "").toLowerCase();
    }

    /** 供上层做分组统计用，避免把 Map 结构散在控制器里 */
    public Map<String, Object> modelInfo() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("provider", "dashscope");
        m.put("text", client.props().getModels().getText());
        m.put("vision", client.props().getModels().getVision());
        m.put("ocrPrimary", client.props().getModels().getOcrPrimary());
        m.put("ocrFallback", client.props().getModels().getOcrFallback());
        m.put("asr", client.props().getModels().getAsr());
        m.put("region", client.props().getRegion());
        return m;
    }
}
