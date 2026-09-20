package com.guangxuan.audit.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 物料版本（对应 {@code material_version} 表）。
 *
 * <p><b>本表由数据库触发器强制不可变</b>（拒绝 UPDATE / DELETE），
 * 因此任何"修改版本"的需求都必须实现为插入新版本（AGENTS.md 第 7 条）。
 * 文件名带 sha256，对象键本身即不可变，不存在被覆盖的可能。
 */
@Data
@TableName("material_version")
public class MaterialVersionEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long materialId;
    private Integer versionNo;
    private String versionLabel;

    private String fileObjectKey;
    private String fileSha256;
    private Long fileSize;
    private String mimeType;

    private Long uploaderId;

    /** V2 及以后必填，由数据库 CHECK 约束 ck_version_reason 强制 */
    private String uploadReason;

    private Long parentVersionId;

    /** JSON：duration_ms / width / height / page_count / frame_count 等 */
    private String mediaMeta;

    private LocalDateTime createdAt;
}
