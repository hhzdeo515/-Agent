-- ============================================================================
-- V4 风险记录、风险-锚点引用、风险变更历史、审核记录、补充业务表
-- 对应设计文档：01-数据模型与DDL.md §4.6 / §4.7 / §4.12
-- 依据：AGENTS.md 第 5 条（每个独立问题建 Risk Case）、第 7 条（不得改写原风险含义）、
--       第 9 条（Review Record）、第 11 条（三个维度不得混用）
-- ============================================================================

SET NAMES utf8mb4;

-- ── 风险记录 ────────────────────────────────────────────────────────────────
-- 关键设计：
--   * risk_level / confidence / status 是三个独立维度，刻意分成三列，
--     界面也必须用三套视觉编码，不得混用（AGENTS.md 第 11 条）。
--   * first_version_id 永不改变，current_version_id 随整改推进。
--   * rule_refs 只允许存知识库 ID：这是防虚构法条的机制（03 文档 §5.3），
--     模型无法凭空造出合法的 kb_item_version_id。
CREATE TABLE risk_case (
  id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  risk_no            VARCHAR(40)     NOT NULL COMMENT '业务编号，如 RK-2026-0001-03',
  case_id            BIGINT UNSIGNED NOT NULL,
  material_id        BIGINT UNSIGNED NOT NULL,
  first_version_id   BIGINT UNSIGNED NOT NULL COMMENT '首次发现该风险的版本（永不改变）',
  current_version_id BIGINT UNSIGNED NOT NULL COMMENT '当前正在处理的版本',
  risk_type          VARCHAR(48)     NOT NULL,
  risk_level         VARCHAR(8)      NOT NULL COMMENT '潜在影响：HIGH/MEDIUM/LOW',
  confidence         DECIMAL(5,4)    NOT NULL COMMENT 'AI 对识别结果的把握（独立维度）',
  status             VARCHAR(32)     NOT NULL COMMENT '流程阶段（独立维度）',
  risk_text          TEXT            NOT NULL COMMENT '风险原文或画面内容',
  location_desc      VARCHAR(500)    NULL COMMENT '人类可读位置描述',
  region_hint        VARCHAR(24)     NULL COMMENT '画面类风险的大致区域；界面须标注"大致区域"',
  reason             TEXT            NOT NULL COMMENT '风险原因',
  rule_refs          JSON            NOT NULL COMMENT '知识库引用 ID 数组（防虚构）',
  evidence_refs      JSON            NOT NULL COMMENT '证据引用',
  unsupported_claims JSON            NOT NULL COMMENT '想引用但检索不到依据的内容；非空则强制转人工',
  suggestion         TEXT            NULL COMMENT 'AI 修改建议（怎么改）',
  recommended_copy   TEXT            NULL COMMENT '推荐表达（改成什么）',
  required_evidence  TEXT            NULL COMMENT '所需证明材料',
  blocked            TINYINT(1)      NOT NULL DEFAULT 1 COMMENT '是否阻断性风险',
  assignee_id        BIGINT UNSIGNED NULL COMMENT '责任方（RISK_TO_REMEDIATION 时必填）',
  remediation_due_at DATETIME(3)     NULL COMMENT '整改期望完成时间',
  parent_risk_id     BIGINT UNSIGNED NULL COMMENT '整改后发现的新增风险关联原风险',
  dedup_key          CHAR(64)        NOT NULL COMMENT 'version+type+主锚点+归一化原文哈希',
  model_id           VARCHAR(64)     NOT NULL,
  pipeline_version   VARCHAR(32)     NOT NULL,
  prompt_version     VARCHAR(32)     NOT NULL,
  ruleset_version    VARCHAR(32)     NOT NULL,
  created_at         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  closed_at          DATETIME(3)     NULL,
  lock_version       INT UNSIGNED    NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_risk_no (risk_no),
  -- 防重复建单（AGENTS.md 第 14 条）
  UNIQUE KEY uk_risk_dedup (dedup_key),
  KEY idx_risk_case_status (case_id, status),
  KEY idx_risk_material (material_id, status),
  KEY idx_risk_first_version (first_version_id),
  KEY idx_risk_parent (parent_risk_id),
  KEY idx_risk_assignee (assignee_id, status),
  KEY idx_risk_level_status (risk_level, status),
  KEY idx_risk_type (case_id, risk_type),
  CONSTRAINT fk_risk_case FOREIGN KEY (case_id) REFERENCES audit_case(id),
  CONSTRAINT fk_risk_material FOREIGN KEY (material_id) REFERENCES material(id),
  CONSTRAINT fk_risk_first_version FOREIGN KEY (first_version_id) REFERENCES material_version(id),
  CONSTRAINT fk_risk_current_version FOREIGN KEY (current_version_id) REFERENCES material_version(id),
  CONSTRAINT fk_risk_parent FOREIGN KEY (parent_risk_id) REFERENCES risk_case(id),
  CONSTRAINT fk_risk_assignee FOREIGN KEY (assignee_id) REFERENCES sys_user(id),
  CONSTRAINT ck_risk_level CHECK (risk_level IN ('HIGH','MEDIUM','LOW')),
  CONSTRAINT ck_risk_confidence CHECK (confidence >= 0 AND confidence <= 1),
  CONSTRAINT ck_risk_status CHECK (status IN
    ('OPEN','PENDING_LEGAL_DECISION','CONFIRMED','AWAITING_EVIDENCE','AWAITING_REVISION',
     'RESUBMITTED','AI_REREVIEW','LEGAL_FINAL_REVIEW','CLOSED','REJECTED_FALSE_POSITIVE')),
  CONSTRAINT ck_risk_region_hint CHECK (region_hint IS NULL OR region_hint IN
    ('TOP_LEFT','TOP_CENTER','TOP_RIGHT','MIDDLE_LEFT','MIDDLE_CENTER','MIDDLE_RIGHT',
     'BOTTOM_LEFT','BOTTOM_CENTER','BOTTOM_RIGHT','FULL_FRAME','NOT_APPLICABLE')),
  CONSTRAINT ck_risk_type CHECK (risk_type IN
    ('ABSOLUTE_CLAIM','EVIDENCE_MISSING','SAFETY_PROMISE','COMPETITOR_COMPARISON',
     'PRICE_CLAIM','DISCLAIMER_MISSING','MISLEADING','OTHER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风险记录';

-- ── 风险与锚点的引用关系 ─────────────────────────────────────────────────────
-- 复合外键 (material_version_id, anchor_id) → evidence_anchor(material_version_id, anchor_id)
-- 使"锚点必须真实存在于该版本"由数据库保证，而不是只靠应用层校验。
CREATE TABLE risk_anchor_ref (
  risk_case_id        BIGINT UNSIGNED NOT NULL,
  anchor_id           VARCHAR(32)     NOT NULL,
  material_version_id BIGINT UNSIGNED NOT NULL,
  ref_role            VARCHAR(16)     NOT NULL DEFAULT 'PRIMARY' COMMENT 'PRIMARY/SUPPORTING',
  PRIMARY KEY (risk_case_id, anchor_id),
  KEY idx_rar_anchor (material_version_id, anchor_id),
  CONSTRAINT fk_rar_risk FOREIGN KEY (risk_case_id) REFERENCES risk_case(id),
  CONSTRAINT fk_rar_anchor FOREIGN KEY (material_version_id, anchor_id)
    REFERENCES evidence_anchor(material_version_id, anchor_id),
  CONSTRAINT ck_rar_role CHECK (ref_role IN ('PRIMARY','SUPPORTING'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风险-锚点引用';

-- ── 风险内容变更历史 ────────────────────────────────────────────────────────
-- AGENTS.md 第 7 条"不得悄悄改变原风险的含义"必须有数据支撑：
-- 若 risk_text 是单值且随版本更新，整改推进后就无法还原旧版本的风险原文。
-- 本表由 V8 的触发器自动写入旧值快照，属 append-only。
CREATE TABLE risk_case_revision (
  id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  risk_case_id        BIGINT UNSIGNED NOT NULL,
  revision_no         INT UNSIGNED    NOT NULL COMMENT '第几次内容变更，从 1 开始',
  material_version_id BIGINT UNSIGNED NOT NULL COMMENT '该次变更前所处的版本',
  risk_text           TEXT            NOT NULL COMMENT '变更前的风险原文快照',
  risk_level          VARCHAR(8)      NOT NULL,
  confidence          DECIMAL(5,4)    NOT NULL,
  suggestion          TEXT            NULL,
  recommended_copy    TEXT            NULL,
  required_evidence   TEXT            NULL,
  reason              TEXT            NOT NULL COMMENT '变更原因',
  changed_by          BIGINT UNSIGNED NULL,
  ai_model_id         VARCHAR(64)     NULL,
  created_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_risk_rev (risk_case_id, revision_no),
  KEY idx_risk_rev_version (material_version_id),
  CONSTRAINT fk_risk_rev_risk FOREIGN KEY (risk_case_id) REFERENCES risk_case(id),
  CONSTRAINT fk_risk_rev_version FOREIGN KEY (material_version_id) REFERENCES material_version(id),
  CONSTRAINT fk_risk_rev_changed_by FOREIGN KEY (changed_by) REFERENCES sys_user(id),
  CONSTRAINT ck_risk_rev_level CHECK (risk_level IN ('HIGH','MEDIUM','LOW')),
  CONSTRAINT ck_risk_rev_conf CHECK (confidence >= 0 AND confidence <= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风险内容变更历史（旧值快照，append-only）';

-- ── 审核记录（不可变审计链） ─────────────────────────────────────────────────
-- AGENTS.md 第 8 条：状态变更必须由明确事件触发并写入 Review Record；
-- 第 9 条：每一次 AI 判断、人工意见、状态变化、签名和关闭操作都应形成 Review Record。
CREATE TABLE review_record (
  id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  case_id             BIGINT UNSIGNED NOT NULL,
  risk_case_id        BIGINT UNSIGNED NULL COMMENT 'Case 级记录可为空',
  material_version_id BIGINT UNSIGNED NULL,
  actor_type          VARCHAR(8)      NOT NULL COMMENT 'AI/HUMAN/SYSTEM',
  actor_id            BIGINT UNSIGNED NULL,
  ai_model_id         VARCHAR(64)     NULL,
  action              VARCHAR(48)     NOT NULL,
  result              VARCHAR(32)     NULL,
  opinion             TEXT            NULL,
  from_status         VARCHAR(32)     NULL,
  to_status           VARCHAR(32)     NULL,
  payload             JSON            NULL,
  trace_id            VARCHAR(64)     NULL,
  created_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_rr_case (case_id, created_at),
  KEY idx_rr_risk (risk_case_id, created_at),
  KEY idx_rr_actor (actor_type, actor_id, created_at),
  KEY idx_rr_action (action, created_at),
  CONSTRAINT fk_rr_case FOREIGN KEY (case_id) REFERENCES audit_case(id),
  CONSTRAINT fk_rr_risk FOREIGN KEY (risk_case_id) REFERENCES risk_case(id),
  CONSTRAINT fk_rr_version FOREIGN KEY (material_version_id) REFERENCES material_version(id),
  CONSTRAINT fk_rr_actor FOREIGN KEY (actor_id) REFERENCES sys_user(id),
  CONSTRAINT ck_rr_actor CHECK (actor_type IN ('AI','HUMAN','SYSTEM')),
  -- AI 与 HUMAN 的必填字段互斥：用约束表达，而不是靠代码自觉
  CONSTRAINT ck_rr_actor_fields CHECK (
       (actor_type = 'HUMAN'  AND actor_id IS NOT NULL AND ai_model_id IS NULL)
    OR (actor_type = 'AI'     AND actor_id IS NULL     AND ai_model_id IS NOT NULL)
    OR (actor_type = 'SYSTEM' AND actor_id IS NULL)
  ),
  -- AGENTS.md 第 5 条：误判必须保存法务理由（记录不能删除）
  CONSTRAINT ck_rr_false_positive_opinion CHECK (
    action <> 'LEGAL_FALSE_POSITIVE' OR (opinion IS NOT NULL AND CHAR_LENGTH(opinion) > 0)
  ),
  CONSTRAINT ck_rr_action CHECK (action IN
    ('AI_INITIAL_REVIEW','LEGAL_CONFIRM','LEGAL_FALSE_POSITIVE','REQUEST_EVIDENCE','TO_REMEDIATION',
     'UPLOAD_VERSION','AI_REREVIEW','LEGAL_FINAL_REVIEW','SIGN','CLOSE','APPROVE_MATERIAL',
     'REVOKE_APPROVAL','STATUS_CHANGE','CASE_STATUS_CHANGE','REPORT_GENERATED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审核记录（append-only 审计链）';

-- ── 风险沟通话术 ────────────────────────────────────────────────────────────
-- AGENTS.md 第 6 条：每个风险下应自动生成一段可发给业务团队的沟通话术。
-- 独立成表的原因：suggestion 是"怎么改"，recommended_copy 是"改成什么"，
-- 而话术是"怎么跟业务方说"——受众与语义都不同，挤进同一列会导致无法按受众出多版本。
CREATE TABLE risk_communication_script (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  risk_case_id   BIGINT UNSIGNED NOT NULL,
  revision_no    INT UNSIGNED    NOT NULL DEFAULT 1,
  audience       VARCHAR(24)     NOT NULL COMMENT 'BRAND/DESIGN/BIZ/MARKET',
  script_text    TEXT            NOT NULL,
  model_id       VARCHAR(64)     NOT NULL,
  prompt_version VARCHAR(32)     NOT NULL,
  generated_at   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_script (risk_case_id, audience, revision_no),
  KEY idx_script_risk (risk_case_id),
  CONSTRAINT fk_script_risk FOREIGN KEY (risk_case_id) REFERENCES risk_case(id),
  CONSTRAINT ck_script_audience CHECK (audience IN ('BRAND','DESIGN','BIZ','MARKET'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风险沟通话术（append-only）';

-- ── AI 初审报告 ─────────────────────────────────────────────────────────────
-- AGENTS.md 第 6 条：报告只反映第一次 AI 初审结果；通过率口径必须明确。
-- AGENTS.md 第 12 条：导出报告应记录生成时间、数据版本和生成者。
-- content_hash 与 data_snapshot 使"报告与数据库矛盾"可被自动检测。
CREATE TABLE initial_review_report (
  id                      BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  case_id                 BIGINT UNSIGNED NOT NULL,
  revision_no             INT UNSIGNED    NOT NULL DEFAULT 1,
  content_md              MEDIUMTEXT      NOT NULL,
  content_hash            CHAR(64)        NOT NULL,
  material_count          INT UNSIGNED    NOT NULL,
  reviewed_material_count INT UNSIGNED    NOT NULL COMMENT '参与初审的物料数（通过率分母）',
  parse_failed_count      INT UNSIGNED    NOT NULL COMMENT '解析失败数（不计入分母）',
  initial_pass_count      INT UNSIGNED    NOT NULL,
  pending_human_count     INT UNSIGNED    NOT NULL,
  risk_fail_count         INT UNSIGNED    NOT NULL,
  initial_pass_rate       DECIMAL(6,4)    NULL COMMENT '物料层通过率，口径见 V9 视图',
  risk_type_distribution  JSON            NOT NULL,
  data_snapshot           JSON            NOT NULL COMMENT '生成时的数据版本快照',
  model_id                VARCHAR(64)     NULL,
  prompt_version          VARCHAR(32)     NULL,
  pipeline_version        VARCHAR(32)     NULL,
  generated_by            BIGINT UNSIGNED NULL COMMENT 'AI 生成为空',
  generated_at            DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_report (case_id, revision_no),
  CONSTRAINT fk_report_case FOREIGN KEY (case_id) REFERENCES audit_case(id),
  CONSTRAINT fk_report_generator FOREIGN KEY (generated_by) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 初审报告（append-only）';

-- ── 版本差异 ────────────────────────────────────────────────────────────────
-- AGENTS.md 第 12 条要求能还原当时的真实审核过程：若 Diff 只在前端实时计算，
-- 事后就无法复现"当时看到的差异是什么"。
CREATE TABLE version_diff (
  id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  material_id       BIGINT UNSIGNED NOT NULL,
  base_version_id   BIGINT UNSIGNED NOT NULL,
  target_version_id BIGINT UNSIGNED NOT NULL,
  diff_type         VARCHAR(24)     NOT NULL COMMENT 'TEXT/IMAGE/VIDEO/DOC/MIXED',
  payload           JSON            NOT NULL COMMENT '结构化差异',
  summary           VARCHAR(1000)   NULL,
  has_change        TINYINT(1)      NOT NULL DEFAULT 1 COMMENT '版本无变化（哈希相同）时为 0',
  engine            VARCHAR(64)     NOT NULL,
  pipeline_version  VARCHAR(32)     NOT NULL,
  created_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_diff (base_version_id, target_version_id),
  KEY idx_diff_material (material_id, created_at),
  CONSTRAINT fk_diff_material FOREIGN KEY (material_id) REFERENCES material(id),
  CONSTRAINT fk_diff_base FOREIGN KEY (base_version_id) REFERENCES material_version(id),
  CONSTRAINT fk_diff_target FOREIGN KEY (target_version_id) REFERENCES material_version(id),
  CONSTRAINT ck_diff_type CHECK (diff_type IN ('TEXT','IMAGE','VIDEO','DOC','MIXED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='版本差异（append-only）';
