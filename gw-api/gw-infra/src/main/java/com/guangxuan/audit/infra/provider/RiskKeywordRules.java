package com.guangxuan.audit.infra.provider;

import com.guangxuan.audit.common.enums.RiskLevel;
import com.guangxuan.audit.common.enums.RiskType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 风险关键词规则表 —— 全项目共用的<b>单一事实来源</b>。
 *
 * <p>为什么提取出来：AI 初审（候选识别）与 AI 复审（判断原风险是否已解决、
 * 是否仍有剩余风险、是否新增风险）必须依据<b>同一套</b>关键词才有意义。
 * 若复审自己维护一份规则，就会出现"初审认为命中、复审认为不命中"的分裂，
 * 而这种分裂在界面上表现为"AI 前后不一致"，比没有复审更糟。
 *
 * <p>接入真实模型后，本类会退化为"无 API Key 时的兜底规则"，
 * 但只要还有一条链路用它，就必须保持唯一来源。
 */
public final class RiskKeywordRules {

    /**
     * 关键词 → 规则。
     *
     * <p>用 LinkedHashMap 保持声明顺序，使同一段文本的命中顺序稳定——
     * 顺序不稳定会让 Risk Case 的编号每次运行都不一样，不利于对账。
     */
    private static final Map<String, Rule> RULES = new LinkedHashMap<>();

    /** 规则 → 条款定位线索，用于把风险类型对应到具体法条（见 LegalBasisMapper）。 */
    private static final Map<String, String> CLAUSE_HINT = Map.of(
            "ABSOLUTE_CLAIM", "国家级",
            "EVIDENCE_MISSING", "引证内容",
            "COMPETITOR_COMPARISON", "贬低",
            "MISLEADING", "虚假广告",
            "PRICE_CLAIM", "价格",
            "SAFETY_PROMISE", "引人误解",
            "DISCLAIMER_MISSING", "显著、清晰表示");

    /**
     * @param keyword 命中的关键词
     * @param type    风险类型
     * @param level   建议风险等级
     * @param reason  风险原因
     */
    public record Rule(String keyword, RiskType type, RiskLevel level, String reason) {
    }

    static {
        RULES.put("行业第一", new Rule("行业第一", RiskType.ABSOLUTE_CLAIM, RiskLevel.HIGH,
                "涉嫌使用绝对化用语，违反广告法对绝对化宣传的限制"));
        RULES.put("第一", new Rule("第一", RiskType.ABSOLUTE_CLAIM, RiskLevel.HIGH,
                "涉嫌使用绝对化用语"));
        RULES.put("最优", new Rule("最优", RiskType.COMPETITOR_COMPARISON, RiskLevel.MEDIUM,
                "比较性表述缺乏依据与统计口径，存在贬低竞品或不正当竞争风险"));
        RULES.put("最好", new Rule("最好", RiskType.ABSOLUTE_CLAIM, RiskLevel.HIGH,
                "涉嫌使用绝对化用语"));
        RULES.put("领先", new Rule("领先", RiskType.COMPETITOR_COMPARISON, RiskLevel.MEDIUM,
                "竞品比较表述缺乏依据与统计口径"));
        RULES.put("100%", new Rule("100%", RiskType.SAFETY_PROMISE, RiskLevel.HIGH,
                "作出绝对化承诺，通常无法验证，易被认定为虚假或引人误解"));
        RULES.put("保障安全", new Rule("保障安全", RiskType.SAFETY_PROMISE, RiskLevel.HIGH,
                "作出安全承诺，需提供权威检测或认证依据"));
        RULES.put("零风险", new Rule("零风险", RiskType.SAFETY_PROMISE, RiskLevel.HIGH,
                "绝对化安全承诺，通常无法验证"));
        // 续航类：中文单位与英文单位都要覆盖，否则「1000 公里」会被漏检
        RULES.put("续航", new Rule("续航", RiskType.EVIDENCE_MISSING, RiskLevel.MEDIUM,
                "续航数据未标注测试工况与数据来源，缺乏证明材料"));
        RULES.put("1000km", new Rule("1000km", RiskType.EVIDENCE_MISSING, RiskLevel.MEDIUM,
                "续航数据未标注数据来源与测试工况，缺乏证明材料"));
        RULES.put("最低价", new Rule("最低价", RiskType.PRICE_CLAIM, RiskLevel.MEDIUM,
                "价格宣传需标明适用范围、期限与依据"));
        RULES.put("立减", new Rule("立减", RiskType.PRICE_CLAIM, RiskLevel.MEDIUM,
                "价格优惠表述需标明适用范围与期限"));
    }

    private RiskKeywordRules() {
    }

    public static Map<String, Rule> all() {
        return RULES;
    }

    public static Rule of(String keyword) {
        return RULES.get(keyword);
    }

    public static String clauseHint(String riskType) {
        return CLAUSE_HINT.get(riskType);
    }

    /**
     * 在文本中匹配规则。
     *
     * <p><b>包含关系去重是必须的</b>：{@code 行业第一} 会同时命中「行业第一」与「第一」
     * 两条规则，不去重就会生成两条内容重复的 Risk Case，直接污染风险数量与通过率。
     * 规则：若某个命中词被另一个更长的命中词包含，则丢弃短的那个。
     */
    public static List<Rule> match(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<String> matched = new ArrayList<>();
        for (String kw : RULES.keySet()) {
            if (text.contains(kw)) {
                matched.add(kw);
            }
        }
        List<Rule> out = new ArrayList<>();
        for (String kw : matched) {
            boolean coveredByLonger = false;
            for (String other : matched) {
                if (!other.equals(kw) && other.length() > kw.length() && other.contains(kw)) {
                    coveredByLonger = true;
                    break;
                }
            }
            if (!coveredByLonger) {
                out.add(RULES.get(kw));
            }
        }
        return out;
    }

    /** 该风险类型下有哪些关键词。复审判断"是否仍有同类型风险"时用它。 */
    public static List<String> keywordsOf(RiskType type) {
        List<String> out = new ArrayList<>();
        for (Rule r : RULES.values()) {
            if (r.type() == type) {
                out.add(r.keyword());
            }
        }
        return out;
    }

    /** 该风险类型下有哪些关键词（按枚举名传入，便于从数据库字符串直接用）。 */
    public static List<String> keywordsOf(String riskTypeName) {
        try {
            return keywordsOf(RiskType.valueOf(riskTypeName));
        } catch (IllegalArgumentException e) {
            return List.of();
        }
    }

    /**
     * 文本中是否命中指定风险类型的关键词，返回命中的关键词列表。
     *
     * <p>与 {@link #match} 的区别：这里只看某一类，且不做跨类型去重——
     * 复审要回答的是"这一类问题还在不在"，而不是重新做一遍全量识别。
     */
    public static List<String> hitsOfType(String text, String riskTypeName) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String kw : keywordsOf(riskTypeName)) {
            if (text.contains(kw)) {
                out.add(kw);
            }
        }
        return out;
    }
}
