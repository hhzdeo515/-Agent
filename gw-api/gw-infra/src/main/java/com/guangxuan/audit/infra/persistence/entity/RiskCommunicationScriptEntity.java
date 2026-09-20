package com.guangxuan.audit.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 风险沟通话术（对应 {@code risk_communication_script} 表，append-only）。
 *
 * <p>AGENTS.md 第 6 条：每个风险下应自动生成一段可以发给设计、品牌、市场或业务团队的
 * 沟通话术。独立于 {@code suggestion}（怎么改）与 {@code recommended_copy}（改成什么）——
 * 话术回答的是"怎么跟业务方说"。
 *
 * <p>{@code modelId} / {@code promptVersion} 必填：话术是 AI 生成物，
 * 事后必须能知道是哪一版模型、哪一版提示词产出的（AGENTS.md 第 11 条）。
 */
@Data
@TableName("risk_communication_script")
public class RiskCommunicationScriptEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long riskCaseId;
    private Integer revisionNo;
    /** BRAND / DESIGN / BIZ / MARKET */
    private String audience;
    private String scriptText;
    private String modelId;
    private String promptVersion;
    private LocalDateTime generatedAt;
}
