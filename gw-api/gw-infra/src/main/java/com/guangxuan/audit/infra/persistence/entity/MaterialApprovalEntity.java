package com.guangxuan.audit.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 物料最终批准（对应 {@code material_approval} 表）。
 *
 * <p>刻意存 {@code versionId} 而非"当前版本"：AGENTS.md 第 9 条要求对外批准的文件
 * 必须能反查到批准时使用的<b>准确版本</b>；{@code fileSha256} 快照让事后校验成为可能。
 *
 * <p>只有一份物料关联的所有阻断性风险都关闭后，该物料版本才可以被标记为最终批准版本
 * （AGENTS.md 第 7 条）——该前置条件在应用层校验，数据库CHECK 约束保证撤销必须留痕。
 */
@Data
@TableName("material_approval")
public class MaterialApprovalEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long materialId;
    private Long versionId;
    private String fileSha256;

    /** APPROVED / REVOKED */
    private String status;

    private Long approvedBy;
    private LocalDateTime approvedAt;
    private Long signatureId;

    private Long revokedBy;
    private LocalDateTime revokedAt;
    private String revokeReason;

    private LocalDateTime createdAt;
}
