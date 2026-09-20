-- ============================================================================
-- V5 法务签名与物料最终批准
-- 对应设计文档：01-数据模型与DDL.md §4.8
-- 依据：AGENTS.md 第 1 条（不得冒充法务给出最终结论）、第 7 条（只有 AI 复审通过 +
--       法务确认 + 签名后 Risk Case 才能 Closed）、第 9 条（批准必须绑定精确版本）
-- ============================================================================

SET NAMES utf8mb4;

-- ── 法务签名（只允许 INSERT） ────────────────────────────────────────────────
CREATE TABLE legal_signature (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  risk_case_id   BIGINT UNSIGNED NULL COMMENT '风险关闭签名',
  material_id    BIGINT UNSIGNED NULL COMMENT '物料批准签名',
  version_id     BIGINT UNSIGNED NULL COMMENT '批准针对的确切版本',
  signer_id      BIGINT UNSIGNED NOT NULL COMMENT '必须是持 LEGAL 角色的人类账号',
  signer_role    VARCHAR(32)     NOT NULL,
  sign_type      VARCHAR(24)     NOT NULL COMMENT 'RISK_CLOSE/MATERIAL_APPROVE/REVOKE',
  -- AGENTS.md 第 9 条：对外批准的文件必须能反向追溯到批准时使用的准确版本，
  -- 不能只关联到一个可能继续被覆盖的文件地址。哈希快照让事后校验成为可能。
  file_sha256    CHAR(64)        NULL,
  signature_hash CHAR(64)        NOT NULL COMMENT '对签名上下文计算的哈希，防篡改',
  comment        VARCHAR(1000)   NULL,
  signed_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_sig_risk (risk_case_id),
  KEY idx_sig_material (material_id, version_id),
  KEY idx_sig_signer (signer_id, signed_at),
  CONSTRAINT fk_sig_risk FOREIGN KEY (risk_case_id) REFERENCES risk_case(id),
  CONSTRAINT fk_sig_material FOREIGN KEY (material_id) REFERENCES material(id),
  CONSTRAINT fk_sig_version FOREIGN KEY (version_id) REFERENCES material_version(id),
  CONSTRAINT fk_sig_signer FOREIGN KEY (signer_id) REFERENCES sys_user(id),
  CONSTRAINT ck_sig_type CHECK (sign_type IN ('RISK_CLOSE','MATERIAL_APPROVE','REVOKE')),
  CONSTRAINT ck_sig_target CHECK (
       (sign_type = 'RISK_CLOSE' AND risk_case_id IS NOT NULL)
    OR (sign_type IN ('MATERIAL_APPROVE','REVOKE') AND material_id IS NOT NULL AND version_id IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='法务签名（append-only）';

-- ── 物料最终批准 ────────────────────────────────────────────────────────────
-- 存 version_id 而非"当前版本"，是为了让"批准的是哪份文件"永久可证。
CREATE TABLE material_approval (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  material_id   BIGINT UNSIGNED NOT NULL,
  version_id    BIGINT UNSIGNED NOT NULL COMMENT '最终批准版本（精确绑定）',
  file_sha256   CHAR(64)        NOT NULL COMMENT '批准时哈希快照',
  status        VARCHAR(16)     NOT NULL COMMENT 'APPROVED/REVOKED',
  approved_by   BIGINT UNSIGNED NULL,
  approved_at   DATETIME(3)     NULL,
  signature_id  BIGINT UNSIGNED NULL,
  revoked_by    BIGINT UNSIGNED NULL,
  revoked_at    DATETIME(3)     NULL,
  revoke_reason VARCHAR(1000)   NULL,
  created_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_approval_material (material_id, status),
  KEY idx_approval_version (version_id),
  CONSTRAINT fk_approval_material FOREIGN KEY (material_id) REFERENCES material(id),
  CONSTRAINT fk_approval_version FOREIGN KEY (version_id) REFERENCES material_version(id),
  CONSTRAINT fk_approval_signature FOREIGN KEY (signature_id) REFERENCES legal_signature(id),
  CONSTRAINT fk_approval_approver FOREIGN KEY (approved_by) REFERENCES sys_user(id),
  CONSTRAINT fk_approval_revoker FOREIGN KEY (revoked_by) REFERENCES sys_user(id),
  CONSTRAINT ck_approval_status CHECK (status IN ('APPROVED','REVOKED')),
  -- 撤销必须有撤销人与原因（AGENTS.md 第 13 条：高风险动作需留痕）
  CONSTRAINT ck_approval_revoke CHECK (
    status <> 'REVOKED' OR (revoked_by IS NOT NULL AND revoke_reason IS NOT NULL)
  ),
  -- 批准状态必须有批准人与批准时间
  CONSTRAINT ck_approval_approved CHECK (
    status <> 'APPROVED' OR (approved_by IS NOT NULL AND approved_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='物料最终批准';
