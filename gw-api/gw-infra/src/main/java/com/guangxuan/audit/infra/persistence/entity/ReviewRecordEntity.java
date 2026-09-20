package com.guangxuan.audit.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审核记录（对应 {@code review_record} 表）。
 *
 * <p><b>append-only</b>：由数据库触发器拒绝 UPDATE / DELETE。
 * AGENTS.md 第 8 条要求状态变更必须由明确事件触发并写入本表；
 * 第 9 条要求每次 AI 判断、人工意见、状态变化、签名和关闭都形成记录。
 *
 * <p>{@code actorType} 与 {@code actorId} / {@code aiModelId} 的互斥关系由数据库
 * CHECK 约束 ck_rr_actor_fields 强制，不依赖代码自觉。
 */
@Data
@TableName("review_record")
public class ReviewRecordEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long caseId;
    private Long riskCaseId;
    private Long materialVersionId;

    /** AI / HUMAN / SYSTEM */
    private String actorType;
    private Long actorId;
    private String aiModelId;

    private String action;
    private String result;

    /** 人工意见 / 理由。误判时必填，由 CHECK 约束 ck_rr_false_positive_opinion 强制 */
    private String opinion;

    private String fromStatus;
    private String toStatus;

    /** JSON：附加结构化信息（如复审三问结论、reviewScope） */
    private String payload;

    private String traceId;

    private LocalDateTime createdAt;
}
