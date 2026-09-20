package com.guangxuan.audit.common.enums;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 状态跃迁白名单（见 02 文档 §3.3）。
 *
 * <p><b>为什么规则写在这里而不是散落在 Service 里</b>：状态机是这个系统里最容易被绕过的部分。
 * 把跃迁规则集中成一个不可变的表，配合 {@code RiskStateMachine} 强制所有变更走同一条路，
 * 再用集成测试断言"本文档集合 == {@code risk_status_transition} 表 == 本类集合"，
 * 才能让"三处一致"从口头承诺变成可执行断言（02 文档 §5.3）。
 *
 * <p>注意 {@code CLOSED} 与 {@code REJECTED_FALSE_POSITIVE} 是终态，不接受任何跃迁。
 */
public final class RiskTransitionRules {

    private static final Map<RiskStatus, Set<RiskStatus>> ALLOWED;

    static {
        Map<RiskStatus, Set<RiskStatus>> m = new EnumMap<>(RiskStatus.class);

        // AI 可自行把高不确定项转人工；法务可确认 / 判误判 / 要求补料
        m.put(RiskStatus.OPEN, EnumSet.of(
                RiskStatus.PENDING_LEGAL_DECISION,
                RiskStatus.CONFIRMED,
                RiskStatus.REJECTED_FALSE_POSITIVE,
                RiskStatus.AWAITING_EVIDENCE));

        // AGENTS.md 第 5 条：待人工判断的风险只有在法务作出处理后才能继续流转
        m.put(RiskStatus.PENDING_LEGAL_DECISION, EnumSet.of(
                RiskStatus.CONFIRMED,
                RiskStatus.REJECTED_FALSE_POSITIVE,
                RiskStatus.AWAITING_EVIDENCE));

        m.put(RiskStatus.CONFIRMED, EnumSet.of(
                RiskStatus.AWAITING_REVISION));

        m.put(RiskStatus.AWAITING_EVIDENCE, EnumSet.of(
                RiskStatus.CONFIRMED,
                RiskStatus.AWAITING_REVISION));

        // 新版本上传由系统在解析成功后推进
        m.put(RiskStatus.AWAITING_REVISION, EnumSet.of(
                RiskStatus.RESUBMITTED));

        m.put(RiskStatus.RESUBMITTED, EnumSet.of(
                RiskStatus.AI_REREVIEW));

        m.put(RiskStatus.AI_REREVIEW, EnumSet.of(
                RiskStatus.LEGAL_FINAL_REVIEW,
                RiskStatus.AWAITING_REVISION));

        // 关闭必须经法务终审 + 签名
        m.put(RiskStatus.LEGAL_FINAL_REVIEW, EnumSet.of(
                RiskStatus.CLOSED,
                RiskStatus.AWAITING_REVISION));

        // 终态
        m.put(RiskStatus.CLOSED, EnumSet.noneOf(RiskStatus.class));
        m.put(RiskStatus.REJECTED_FALSE_POSITIVE, EnumSet.noneOf(RiskStatus.class));

        ALLOWED = Collections.unmodifiableMap(m);
    }

    private RiskTransitionRules() {
    }

    /** 全部合法跃迁（不可变） */
    public static Map<RiskStatus, Set<RiskStatus>> all() {
        return ALLOWED;
    }

    /** 指定状态可跃迁到的目标集合 */
    public static Set<RiskStatus> allowedFrom(RiskStatus from) {
        return ALLOWED.getOrDefault(from, Collections.emptySet());
    }

    public static boolean isAllowed(RiskStatus from, RiskStatus to) {
        return allowedFrom(from).contains(to);
    }

    /**
     * 该跃迁是否必须由法务执行。
     *
     * <p>AI 只能完成 AI 阶段的状态流转（如 OPEN → PENDING_LEGAL_DECISION、
     * RESUBMITTED → AI_REREVIEW → LEGAL_FINAL_REVIEW），无权确认风险、判误判、关闭。
     */
    public static boolean requiresLegal(RiskStatus from, RiskStatus to) {
        return switch (to) {
            case CONFIRMED, REJECTED_FALSE_POSITIVE, AWAITING_EVIDENCE,
                 AWAITING_REVISION, CLOSED -> true;
            // AI 阶段流转
            case PENDING_LEGAL_DECISION, RESUBMITTED, AI_REREVIEW -> false;
            // AI 复审完成后进入法务终审，由系统推进，人工决策在终审环节发生
            case LEGAL_FINAL_REVIEW -> false;
            default -> true;
        };
    }

    /** 收集本类与数据库白名单的差异，供一致性测试使用 */
    public static java.util.List<String> diffAgainst(Set<String> dbPairs) {
        java.util.List<String> missingInDb = new java.util.ArrayList<>();
        java.util.List<String> extraInDb = new java.util.ArrayList<>();

        java.util.Set<String> javaPairs = new java.util.HashSet<>();
        ALLOWED.forEach((from, tos) -> tos.forEach(to -> javaPairs.add(from.name() + "->" + to.name())));

        javaPairs.stream().filter(p -> !dbPairs.contains(p)).forEach(missingInDb::add);
        dbPairs.stream().filter(p -> !javaPairs.contains(p)).forEach(extraInDb::add);

        java.util.List<String> result = new java.util.ArrayList<>();
        missingInDb.forEach(p -> result.add("数据库缺少: " + p));
        extraInDb.forEach(p -> result.add("数据库多余: " + p));
        return result;
    }
}
