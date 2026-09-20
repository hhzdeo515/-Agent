package com.guangxuan.audit.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审核任务（对应 {@code audit_case} 表）。
 *
 * <p>字段与 docs/01-数据模型与DDL.md §4.1 对齐。
 */
@Data
@TableName("audit_case")
public class AuditCaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String caseNo;
    private String name;
    private Long projectId;
    private Long submitterId;
    private Long ownerId;
    private LocalDateTime deadline;

    /** 取值见 {@code CaseStatus}；与数据库 CHECK 约束逐字一致 */
    private String status;

    /** JSON：审核要求（结构化关注点清单） */
    private String reviewRequirement;

    private String requirementTemplateCode;

    /**
     * AGENTS.md 第 4 条：模板内容不得默认为本次任务的正式要求，用户确认后才生效。
     * 因此这两个字段是 Case 能否进入 AI 初审的硬前置。
     */
    private Long requirementConfirmedBy;
    private LocalDateTime requirementConfirmedAt;

    private Integer materialCount;

    /** 乐观锁 */
    @Version
    private Integer lockVersion;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
