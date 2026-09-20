package com.guangxuan.audit.domain.ai;

import java.util.List;

/**
 * 风险草稿：AI 结构化输出的单条风险。
 *
 * <p>字段覆盖 {@code AGENTS.md} 第 11 条要求的全部内容。
 * <b>两处刻意的设计偏离</b>，需在代码评审时理解其意图：
 * <ol>
 *   <li>{@code materialId} / {@code versionId} <b>不由模型输出</b>，而由服务端注入。
 *       让模型输出这两个字段没有任何价值，只会带来填错的风险。</li>
 *   <li>{@code location} 里是 <b>anchorIds 而非坐标</b>。模型给不出可靠坐标，
 *       而让"风险原文"回来做字符串匹配又会静默失败（docs/00 §3.1）。
 *       改为引用锚点 ID 后，定位 = ID 查表，可 100% 校验。</li>
 * </ol>
 *
 * <p>{@code ruleReferences} 只允许引用知识库中<b>已检索到</b>的 ID——
 * 这是防虚构法条的机制：模型无法凭空造出合法的 {@code kbItemVersionId}。
 * 若模型想引用但检索不到依据，只能写入 {@code unsupportedClaims}，
 * 服务端据此强制转人工判断（docs/03 §5.3）。
 */
public record RiskDraft(
        String riskType,
        String riskLevel,
        String riskText,
        Location location,
        String reason,
        double confidence,
        List<RuleReference> ruleReferences,
        List<EvidenceReference> evidenceReferences,
        List<String> unsupportedClaims,
        String suggestion,
        String recommendedCopy,
        String requiredEvidence,
        String recommendedStatus) {

    /**
     * @param anchorIds   必须来自本次提供的锚点清单；服务端逐个校验存在性
     * @param regionHint  无文字锚点的画面类风险使用；界面须标注为"大致区域"
     * @param locationDesc 人类可读位置描述
     */
    public record Location(List<String> anchorIds, String regionHint, String locationDesc) {
        public Location {
            anchorIds = anchorIds == null ? List.of() : List.copyOf(anchorIds);
        }
    }

    /**
     * @param kbItemVersionId 知识库版本的 ID；服务端校验它必须来自本次检索结果集
     */
    public record RuleReference(long kbItemVersionId, String ruleCode, String quotedText) {
    }

    /** @param kind KB_ITEM / APPROVED_CLAIM / HISTORICAL_CASE / PRODUCT_PARAM / NONE */
    public record EvidenceReference(String kind, String refId, String note) {
    }

    public RiskDraft {
        ruleReferences = ruleReferences == null ? List.of() : List.copyOf(ruleReferences);
        evidenceReferences = evidenceReferences == null ? List.of() : List.copyOf(evidenceReferences);
        unsupportedClaims = unsupportedClaims == null ? List.of() : List.copyOf(unsupportedClaims);
    }

    /** 依据是否充分：无依据或存在无法支撑的引用时为 false */
    public boolean hasSufficientBasis() {
        return !ruleReferences.isEmpty() && unsupportedClaims.isEmpty();
    }

    /** 是否缺少可定位的锚点 */
    public boolean lacksLocation() {
        return location == null || location.anchorIds().isEmpty();
    }
}
