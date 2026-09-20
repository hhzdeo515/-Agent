package com.guangxuan.audit.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 风险记录（对应 {@code risk_case} 表）。
 *
 * <p>三个维度刻意分成三列，<b>不得混用</b>（AGENTS.md 第 11 条）：
 * <ul>
 *   <li>{@code riskLevel} —— 潜在影响有多大</li>
 *   <li>{@code confidence} —— AI 对识别结果的把握</li>
 *   <li>{@code status} —— 流程处于哪个阶段</li>
 * </ul>
 *
 * <p>{@code ruleRefs} 只允许存知识库 ID：这是防虚构法条的机制——模型无法凭空造出
 * 合法的 {@code kb_item_version_id}。模型想引用但检索不到依据的内容只能进
 * {@code unsupportedClaims}，据此强制转人工。
 */
@Data
@TableName("risk_case")
public class RiskCaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String riskNo;
    private Long caseId;
    private Long materialId;

    /** 首次发现该风险的版本，永不改变（AGENTS.md 第 9 条） */
    private Long firstVersionId;
    /** 当前正在处理的版本 */
    private Long currentVersionId;

    private String riskType;
    private String riskLevel;
    private BigDecimal confidence;
    private String status;

    private String riskText;
    private String locationDesc;
    private String regionHint;
    private String reason;

    /** JSON：知识库引用 ID 数组 */
    private String ruleRefs;
    /** JSON：证据引用 */
    private String evidenceRefs;
    /** JSON：想引用但检索不到依据的内容；非空则强制转人工 */
    private String unsupportedClaims;

    private String suggestion;
    private String recommendedCopy;
    private String requiredEvidence;

    private Boolean blocked;

    /** 责任方（转入整改时必填） */
    private Long assigneeId;
    private LocalDateTime remediationDueAt;

    /** 整改后发现的新增风险关联原风险（AGENTS.md 第 7 条） */
    private Long parentRiskId;

    private String dedupKey;

    /** 审计四件套：缺了它们就无法解释"为什么同一份材料这次判得不一样"（ADR D-10） */
    private String modelId;
    private String pipelineVersion;
    private String promptVersion;
    private String rulesetVersion;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime closedAt;

    @Version
    private Integer lockVersion;
}
