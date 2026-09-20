package com.guangxuan.audit.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 宣传物料（对应 {@code material} 表）。
 *
 * <p>注意 {@code parseStatus} 的 FAILED 与 PARTIAL 语义差异（docs/01 §8.1）：
 * FAILED 严格阻断且不计入通过率分母；PARTIAL 允许进入初审但置信度封顶、不得算"初审通过"。
 */
@Data
@TableName("material")
public class MaterialEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long caseId;
    private String name;

    /** IMAGE/VIDEO/TEXT/PPT/PDF/WORD */
    private String materialType;

    /** PENDING/RUNNING/SUCCEEDED/FAILED/PARTIAL */
    private String parseStatus;

    private String parseErrorCode;

    /** 明确告诉用户需要重传或补充什么（AGENTS.md 第 4 条） */
    private String parseErrorMessage;

    private Long currentVersionId;

    /** 冗余缓存列；口径以 v_material_initial_review 视图为准 */
    private String initialReviewClass;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
