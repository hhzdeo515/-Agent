package com.guangxuan.audit.domain.risk;

/**
 * AI 复审的检查范围（见 02 文档 §8）。
 *
 * <p>{@code AGENTS.md} 第 7 条要求"不得把终审区重新做成一次无差别的全量初审"。
 * 服务端在复审入口强制校验范围不为 {@link #FULL}，并把它持久化到 {@code review_record.payload}——
 * 这样"本次复审没有做全量扫描"这件事是<b>有据可查</b>的，而不是靠承诺。
 */
public enum ReviewScope {

    /** 仅变化区域 */
    CHANGED_REGION_ONLY,

    /** 变化区域 + 必要上下文（默认）：防止局部修改引入上下文风险 */
    CHANGED_REGION_WITH_CONTEXT,

    /** 全量初审 —— <b>终审区禁止使用</b> */
    FULL;

    public boolean isForbiddenForRereview() {
        return this == FULL;
    }
}
