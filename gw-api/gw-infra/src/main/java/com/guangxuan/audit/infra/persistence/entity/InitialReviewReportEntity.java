package com.guangxuan.audit.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * AI 初审报告（对应 {@code initial_review_report} 表，append-only）。
 *
 * <p>AGENTS.md 第 6 条：报告只反映<b>第一次 AI 初审</b>结果，且通过率口径必须明确。
 * 因此这里同时落库「结构化计数」与「Markdown 正文」，两者来自同一次视图快照——
 * 报告和数据库里的 Risk Case 数量因此不可能互相矛盾。
 *
 * <p>AGENTS.md 第 12 条：报告需记录生成时间、数据版本和生成者。数据版本由
 * {@code dataSnapshot} + {@code modelId}/{@code pipelineVersion}/{@code promptVersion} 承载。
 */
@Data
@TableName("initial_review_report")
public class InitialReviewReportEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long caseId;
    private Integer revisionNo;

    /** Markdown 正文；导出与复制都以此为准 */
    private String contentMd;
    /** 正文哈希，用于检测"报告被改过但没人知道" */
    private String contentHash;

    private Integer materialCount;
    private Integer reviewedMaterialCount;
    private Integer parseFailedCount;
    private Integer initialPassCount;
    private Integer pendingHumanCount;
    private Integer riskFailCount;

    private BigDecimal initialPassRate;
    /** JSON：风险类型分布 */
    private String riskTypeDistribution;
    /** JSON：生成时的数据版本快照 */
    private String dataSnapshot;

    private String modelId;
    private String promptVersion;
    private String pipelineVersion;
    /** AI 生成为空；人工触发重算时记录人 */
    private Long generatedBy;
    private LocalDateTime generatedAt;
}
