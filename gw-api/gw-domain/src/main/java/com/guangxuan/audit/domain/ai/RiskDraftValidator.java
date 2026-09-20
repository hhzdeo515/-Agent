package com.guangxuan.audit.domain.ai;

import com.guangxuan.audit.common.enums.ParseStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 风险草稿的服务端校验器——<b>唯一可信关口</b>（docs/03 §7.3）。
 *
 * <p>为什么不能依赖供应商的"结构化输出"开关：
 * <ul>
 *   <li>{@code qwen-vl-ocr} 与 {@code qwen3.5-ocr} 官方明确<b>不支持</b>结构化输出；</li>
 *   <li>即使支持，{@code json_object} 也只保证"合法 JSON"，不保证"符合我们的 schema"。</li>
 * </ul>
 * 因此无论模型侧是否支持，服务端都必须独立校验。
 *
 * <p><b>校验失败绝不静默丢弃</b>：每条被修正或降级的草稿都必须留下原因，
 * 最终体现为「待人工判断」而不是「通过」。这是安全方向的强制选择——
 * 宁可多让法务看一眼，也不能让一条无处定位或无依据的风险悄悄消失。
 */
public final class RiskDraftValidator {

    /** 低于该置信度一律转人工；由 ruleset 配置，此处为默认值 */
    public static final double DEFAULT_CONFIDENCE_THRESHOLD = 0.6;

    private RiskDraftValidator() {
    }

    /**
     * @param validAnchorIds        该版本内真实存在的锚点 ID 集合
     * @param retrievedKbVersionIds 本次检索到的知识库版本 ID 集合
     * @param parseStatus           物料解析状态；PARTIAL 时置信度将被封顶
     * @param confidenceThreshold   置信度阈值
     */
    public record ValidationContext(
            Set<String> validAnchorIds,
            Set<Long> retrievedKbVersionIds,
            ParseStatus parseStatus,
            double confidenceThreshold) {

        public static ValidationContext of(Set<String> anchorIds, Set<Long> kbIds,
                                           ParseStatus parseStatus) {
            return new ValidationContext(anchorIds, kbIds, parseStatus,
                    DEFAULT_CONFIDENCE_THRESHOLD);
        }
    }

    /** 单条草稿的校验结果 */
    public record ValidatedDraft(RiskDraft draft, List<String> violations, boolean forcedToHuman) {
        public boolean isValid() {
            return violations.isEmpty();
        }
    }

    public record Result(List<ValidatedDraft> drafts, int totalViolations) {
        public boolean hasViolations() {
            return totalViolations > 0;
        }
    }

    public static Result validate(List<RiskDraft> drafts, ValidationContext ctx) {
        List<ValidatedDraft> out = new ArrayList<>();
        int violationCount = 0;

        for (RiskDraft draft : drafts) {
            List<String> violations = new ArrayList<>();
            RiskDraft current = draft;

            // ── 校验 1：锚点必须真实存在于该版本 ──────────────────────────
            // 失败 = 该风险定位无效，必须转人工并记录原因，不得猜测坐标。
            List<String> validAnchors = new ArrayList<>();
            for (String anchorId : current.location() == null
                    ? List.<String>of() : current.location().anchorIds()) {
                if (ctx.validAnchorIds().contains(anchorId)) {
                    validAnchors.add(anchorId);
                } else {
                    violations.add("锚点 ID 不存在于该版本：" + anchorId);
                }
            }

            // ── 校验 2：依据必须来自本次检索结果（防虚构法条）──────────────
            List<RiskDraft.RuleReference> validRules = new ArrayList<>();
            List<String> unsupported = new ArrayList<>(current.unsupportedClaims());
            for (RiskDraft.RuleReference ref : current.ruleReferences()) {
                if (ctx.retrievedKbVersionIds().contains(ref.kbItemVersionId())) {
                    validRules.add(ref);
                } else {
                    // 模型引用了未检索到的依据 —— 这不是"依据"，而是幻觉
                    unsupported.add("未检索到的依据：" + ref.quotedText());
                    violations.add("引用了未检索到的依据：" + ref.kbItemVersionId());
                }
            }

            current = rebuild(current, validAnchors, validRules, unsupported);

            // ── 校验 3：置信度封顶（部分解析） ────────────────────────────
            double capped = ctx.parseStatus().capConfidence(current.confidence());
            if (capped < current.confidence()) {
                violations.add(String.format(
                        "部分解析物料，置信度由 %.2f 封顶为 %.2f", current.confidence(), capped));
                current = withConfidence(current, capped);
            }

            // ── 校验 4：强制转人工判定 ────────────────────────────────────
            // AGENTS.md 第 11 条：模型无法获取关键事实、证明材料或适用规则时，
            // 应输出待人工判断，而不是为了完成流程编造确定答案。
            List<String> humanReasons = new ArrayList<>();
            if (current.confidence() < ctx.confidenceThreshold()) {
                humanReasons.add("置信度低于阈值 " + ctx.confidenceThreshold());
            }
            if (current.ruleReferences().isEmpty()) {
                humanReasons.add("无适用依据");
            }
            if (!current.unsupportedClaims().isEmpty()) {
                humanReasons.add("存在无法支撑的引用");
            }
            if (current.lacksLocation()) {
                humanReasons.add("无可用锚点定位");
            }
            // 低置信度 + 高影响：优先人工，而不是忽略
            if ("HIGH".equals(current.riskLevel())
                    && current.confidence() < Math.max(ctx.confidenceThreshold(), 0.75)) {
                humanReasons.add("高影响但置信度不足");
            }

            boolean forced = !humanReasons.isEmpty();
            if (forced) {
                current = withRecommendedStatus(current, "PENDING_LEGAL_DECISION");
                violations.addAll(humanReasons);
            }

            violationCount += violations.size();
            out.add(new ValidatedDraft(current, violations, forced));
        }

        return new Result(out, violationCount);
    }

    // ── 不可变重建（RiskDraft 是 record，只能重建不能改） ─────────────────

    private static RiskDraft rebuild(RiskDraft d, List<String> anchorIds,
                                     List<RiskDraft.RuleReference> rules,
                                     List<String> unsupported) {
        RiskDraft.Location loc = new RiskDraft.Location(
                anchorIds,
                d.location() == null ? "NOT_APPLICABLE" : d.location().regionHint(),
                d.location() == null ? "" : d.location().locationDesc());
        return new RiskDraft(d.riskType(), d.riskLevel(), d.riskText(), loc, d.reason(),
                d.confidence(), rules, d.evidenceReferences(), unsupported,
                d.suggestion(), d.recommendedCopy(), d.requiredEvidence(), d.recommendedStatus());
    }

    private static RiskDraft withConfidence(RiskDraft d, double confidence) {
        return new RiskDraft(d.riskType(), d.riskLevel(), d.riskText(), d.location(), d.reason(),
                confidence, d.ruleReferences(), d.evidenceReferences(), d.unsupportedClaims(),
                d.suggestion(), d.recommendedCopy(), d.requiredEvidence(), d.recommendedStatus());
    }

    private static RiskDraft withRecommendedStatus(RiskDraft d, String status) {
        return new RiskDraft(d.riskType(), d.riskLevel(), d.riskText(), d.location(), d.reason(),
                d.confidence(), d.ruleReferences(), d.evidenceReferences(), d.unsupportedClaims(),
                d.suggestion(), d.recommendedCopy(), d.requiredEvidence(), status);
    }
}
