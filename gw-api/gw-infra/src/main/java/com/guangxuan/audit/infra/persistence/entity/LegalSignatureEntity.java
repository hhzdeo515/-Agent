package com.guangxuan.audit.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 法务签名（对应 {@code legal_signature} 表）。
 *
 * <p><b>append-only</b>，且 INSERT 时由触发器 {@code trg_signature_requires_privilege}
 * 校验签名人必须持有 LEGAL 角色——这是"AI 不得冒充法务"的最后一道兜底。
 *
 * <p>{@code fileSha256} 是批准时物料的哈希快照：AGENTS.md 第 9 条要求对外批准的文件
 * 必须能反向追溯到批准时使用的准确版本，不能只关联到一个可能被覆盖的文件地址。
 */
@Data
@TableName("legal_signature")
public class LegalSignatureEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long riskCaseId;
    private Long materialId;
    private Long versionId;

    private Long signerId;
    private String signerRole;

    /** RISK_CLOSE / MATERIAL_APPROVE / REVOKE */
    private String signType;

    private String fileSha256;

    /** 对签名上下文计算的哈希，用于防篡改与事后核验 */
    private String signatureHash;

    private String comment;
    private LocalDateTime signedAt;
}
