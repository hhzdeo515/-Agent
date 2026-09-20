package com.guangxuan.audit.common.enums;

/**
 * 操作主体类型。
 *
 * <p>这是"AI 不得替代法务"这条产品边界的执行基础（AGENTS.md 第 1、8 条）：
 * 领域守卫通过校验 {@code actor.type()}，使 AI 编排链路无论从哪个入口进来都做不了
 * 确认风险、判误判、签名、关闭、批准这些动作。
 */
public enum ActorType {

    /** AI：可建议状态、可完成 AI 阶段流转，无权最终批准 / 签名 / 关闭 */
    AI,

    /** 人工：法务、品牌、设计、业务、管理员 */
    HUMAN,

    /** 系统：定时任务、回调消费、状态自动推进 */
    SYSTEM
}
