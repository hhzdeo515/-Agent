package com.guangxuan.audit.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 版本差异（对应 {@code version_diff} 表）。
 *
 * <p>落库而非只在前端实时计算的原因（AGENTS.md 第 12 条）：事后必须能还原
 * "当时看到的差异是什么"。若差异只在浏览器里算，法务事后复盘时就无法证明
 * 某次复审究竟比对了哪些内容。
 *
 * <p>本表<b>不允许 UPDATE</b>：差异一旦生成即为当时事实。同一对版本重复生成时，
 * 由 {@code uk_diff(base_version_id, target_version_id)} 唯一键拦截，
 * 复用已有记录而不是覆盖。
 */
@Data
@TableName("version_diff")
public class VersionDiffEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long materialId;
    private Long baseVersionId;
    private Long targetVersionId;

    /** TEXT / IMAGE / VIDEO / DOC / MIXED —— 与 CHECK 约束 ck_diff_type 一致 */
    private String diffType;

    /** JSON：结构化差异（changes 数组 + 统计） */
    private String payload;

    private String summary;

    /** 版本无变化（哈希相同或锚点集合完全一致）时为 false */
    private Boolean hasChange;

    /** 生成差异的引擎标识，如 lcs-text-diff */
    private String engine;

    /** 流水线版本，用于解释"算法升级后同一对版本差异为何不同" */
    private String pipelineVersion;

    private LocalDateTime createdAt;
}
