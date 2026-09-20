package com.guangxuan.audit.infra.legal;

import com.guangxuan.audit.infra.persistence.mapper.LegalBasisMapper;
import com.guangxuan.audit.infra.provider.RiskKeywordRules;

import java.util.List;

/**
 * 条款定位：从一部法规的全部条款切片里选出与本次风险真正相关的那一条。
 *
 * <p>为什么必须是一个共享实现：这件事有三个调用方——AI 初审生成引用、
 * 反馈区把 {@code rule_refs} 还原成可读依据、初审报告落库。三处各写一份的话，
 * 迟早出现"风险详情说第九条、报告里写第四条"这种自相矛盾，
 * 而法务一旦发现依据对不上，就不会再信任任何一条 AI 结论。
 *
 * <h3>为什么要按"线索词"选条款，而不是取第一条</h3>
 * 一部法规关联的切片有七八条，取第一条意味着所有绝对化宣传都引用同一条
 * （通常是第四条的总则），法务看不出"这条风险具体违反了哪一款"。
 * 线索词来自 {@link RiskKeywordRules#clauseHint(String)}，把风险类型
 * 映射到该类型最相关的表述，例如绝对化宣传 → "国家级"、引证内容 → "引证内容"。
 *
 * <h3>为什么还要截到句子</h3>
 * 条款常常包含多款。广告法第十一条前半句讲行政许可、后半句才是
 * "引证内容应当真实、准确，并表明出处"。整条贴出来，法务要自己找哪半句适用；
 * 截到含线索词的那一句，依据才是可以直接引用的。
 */
public final class LegalClausePicker {

    private LegalClausePicker() {
    }

    /** 选中与风险类型最相关的条款切片；无法判断时返回 null */
    public static LegalBasisMapper.LegalBasisRow pickRow(
            String riskType, List<LegalBasisMapper.LegalBasisRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        String hint = RiskKeywordRules.clauseHint(riskType);
        if (hint != null) {
            for (LegalBasisMapper.LegalBasisRow r : rows) {
                if (r.getChunkText() != null && r.getChunkText().contains(hint)) {
                    return r;
                }
            }
        }
        // 兜底：取第一条有文本的条款。宁可给一条相关性弱的依据，
        // 也不要给空——空会被界面渲染成"无依据"，而实际上依据是存在的。
        for (LegalBasisMapper.LegalBasisRow r : rows) {
            if (r.getChunkText() != null && !r.getChunkText().isBlank()) {
                return r;
            }
        }
        return null;
    }

    /**
     * 只取与风险相关的条款正文（不含法规名）。
     *
     * @return 可直接展示的一句话；无可用条款时返回说明性文字而不是 null，
     *         避免调用方各自决定"没有依据时显示什么"
     */
    public static String pick(String riskType, List<LegalBasisMapper.LegalBasisRow> rows) {
        LegalBasisMapper.LegalBasisRow best = pickRow(riskType, rows);
        if (best == null) {
            return "（该法规已关联，但未找到可展示的条款文本）";
        }

        String t = best.getChunkText();
        // 条款号：开头到第一个全角空格，例如「第十一条」
        int sp = t.indexOf('　');
        String clauseNo = sp > 0 ? t.substring(0, sp) : "";

        String hint = RiskKeywordRules.clauseHint(riskType);
        if (hint != null) {
            int hIdx = t.indexOf(hint);
            if (hIdx >= 0) {
                int start = Math.max(0, t.lastIndexOf('。', hIdx) + 1);
                int end = t.indexOf('。', hIdx);
                String sentence = end > 0 ? t.substring(start, end + 1) : t.substring(start);
                if (sentence.length() > 150) {
                    sentence = sentence.substring(0, 150) + "…";
                }
                // 句子本身可能就带「第X条」前缀，避免拼成「第九条　第九条　…」
                if (clauseNo.isEmpty() || sentence.startsWith(clauseNo)) {
                    return sentence;
                }
                return clauseNo + "　" + sentence;
            }
        }

        int dot = t.indexOf('。');
        return dot > 0 && dot < 130
                ? t.substring(0, dot + 1)
                : t.substring(0, Math.min(110, t.length()));
    }

    /**
     * 法规名 + 条款，用于"审核依据：中华人民共和国广告法 第九条　…"这种展示。
     *
     * @return 形如 {@code 中华人民共和国广告法：第九条　不得使用"国家级"…}
     */
    public static String pickWithLaw(String riskType, String lawTitle,
                                     List<LegalBasisMapper.LegalBasisRow> rows) {
        String clause = pick(riskType, rows);
        return lawTitle == null || lawTitle.isBlank() ? clause : lawTitle + "：" + clause;
    }
}
