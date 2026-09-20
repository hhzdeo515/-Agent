package com.guangxuan.audit.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 证据锚点（对应 {@code evidence_anchor} 表）。
 *
 * <p>这是整套定位能力的地基（docs/00 §3）：模型输出风险时只引用 {@code anchorId}，
 * 不输出坐标；定位 = ID 查表，可 100% 校验。
 *
 * <p>由数据库触发器强制不可变（拒绝 UPDATE / DELETE）——因为历史 Risk Case 的位置引用
 * 依赖它，一旦被改写，历史风险就会指向错误内容。
 */
@Data
@TableName("evidence_anchor")
public class EvidenceAnchorEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务锚点 ID，如 A-0007；模型引用此值 */
    private String anchorId;

    private Long materialVersionId;

    /** TEXT_LINE / SPEECH_SENTENCE / SUBTITLE_LINE / KEY_FRAME / DOC_PARAGRAPH / DOC_SENTENCE */
    private String anchorType;

    /** JSON，结构随 anchorType 变化，见 docs/01 §6 */
    private String locator;

    private String text;

    @com.baomidou.mybatisplus.annotation.TableField("confidence")
    private BigDecimal confidence;

    private String sourceEngine;
    private String sourceEngineVersion;
    private Integer ordinal;

    private LocalDateTime createdAt;
}
