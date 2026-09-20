-- ============================================================================
-- V7 Legal Knowledge Base
-- 对应设计文档：01-数据模型与DDL.md §5
-- 依据：AGENTS.md 第 10 条（统一知识库、区分现行/历史、治理与审批、不得直接写回高可信库）
-- ============================================================================

SET NAMES utf8mb4;

-- ── 知识条目 ────────────────────────────────────────────────────────────────
-- AGENTS.md 第 10 条要求每条知识保存来源、适用地区、适用业务或产品、发布日期、
-- 生效日期、失效日期、版本、维护人和可信级别。这些字段在这里全部落地。
CREATE TABLE kb_item (
  id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  item_type           VARCHAR(24)     NOT NULL
                      COMMENT 'LAW/REGULATION/INTERPRETATION/INTERNAL_RULE/PRODUCT_PARAM/EVIDENCE/CASE/APPROVED_CLAIM/REJECTED_COPY',
  title               VARCHAR(300)    NOT NULL,
  region              VARCHAR(64)     NULL COMMENT '适用地区',
  applicable_business VARCHAR(128)    NULL COMMENT '适用业务',
  applicable_product  VARCHAR(128)    NULL COMMENT '适用产品/车型',
  publish_date        DATE            NULL,
  effective_date      DATE            NULL,
  expire_date         DATE            NULL,
  trust_level         VARCHAR(16)     NOT NULL COMMENT 'HIGH/MEDIUM/LOW',
  -- 治理状态：法务对 AI 结果的确认与误判标记可以沉淀为优化数据，
  -- 但不得在未经治理的情况下直接写回高可信法律法规库（AGENTS.md 第 10 条）。
  -- 因此 review_record 与本表之间没有自动写路径，入库必须走 DRAFT → PENDING_APPROVAL → PUBLISHED。
  governance_status   VARCHAR(24)     NOT NULL DEFAULT 'DRAFT'
                      COMMENT 'DRAFT/PENDING_APPROVAL/PUBLISHED/RETIRED',
  maintainer_id       BIGINT UNSIGNED NULL,
  project_id          BIGINT UNSIGNED NULL COMMENT '企业内部规则可按项目隔离；法规为 NULL 表示全局',
  created_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_kb_type_status (item_type, governance_status),
  KEY idx_kb_effective (effective_date, expire_date),
  KEY idx_kb_scope (region, applicable_business, applicable_product),
  KEY idx_kb_project (project_id),
  CONSTRAINT fk_kb_maintainer FOREIGN KEY (maintainer_id) REFERENCES sys_user(id),
  CONSTRAINT fk_kb_project FOREIGN KEY (project_id) REFERENCES biz_project(id),
  CONSTRAINT ck_kb_type CHECK (item_type IN
    ('LAW','REGULATION','INTERPRETATION','INTERNAL_RULE','PRODUCT_PARAM','EVIDENCE',
     'CASE','APPROVED_CLAIM','REJECTED_COPY')),
  CONSTRAINT ck_kb_trust CHECK (trust_level IN ('HIGH','MEDIUM','LOW')),
  CONSTRAINT ck_kb_gov CHECK (governance_status IN
    ('DRAFT','PENDING_APPROVAL','PUBLISHED','RETIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识条目';

-- ── 知识条目版本 ────────────────────────────────────────────────────────────
-- AGENTS.md 第 10 条：法规更新、企业规则变更和产品参数更新应有版本管理和审批机制，
-- 避免旧知识继续影响新任务。
CREATE TABLE kb_item_version (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  kb_item_id     BIGINT UNSIGNED NOT NULL,
  version_no     INT UNSIGNED    NOT NULL,
  content        MEDIUMTEXT      NOT NULL COMMENT '条文/规则正文',
  source_url     VARCHAR(512)    NULL COMMENT '可追溯来源',
  source_note    VARCHAR(500)    NULL,
  content_hash   CHAR(64)        NOT NULL,
  effective_from DATE            NULL,
  effective_to   DATE            NULL,
  approved_by    BIGINT UNSIGNED NULL COMMENT '法规更新需审批',
  approved_at    DATETIME(3)     NULL,
  created_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_kbv (kb_item_id, version_no),
  KEY idx_kbv_effective (effective_from, effective_to),
  CONSTRAINT fk_kbv_item FOREIGN KEY (kb_item_id) REFERENCES kb_item(id),
  CONSTRAINT fk_kbv_approver FOREIGN KEY (approved_by) REFERENCES sys_user(id),
  CONSTRAINT ck_kbv_version CHECK (version_no >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识条目版本';

-- ── 知识切片与向量 ──────────────────────────────────────────────────────────
-- 混合检索（03 文档 §5.2）：
--   dense  做语义匹配（"绝对化用语的合规边界"）
--   sparse 做关键词精确匹配（"《广告法》第九条第几款"）
-- 法务检索同时存在这两种意图，纯稠密检索会漏掉条款号匹配。
--
-- 批次与长度约束（已核实）：text-embedding-v4 批次 ≤10 条、单条 ≤8192 token。
-- MySQL 8 无原生向量类型，dense 以 float32 BLOB 存储；数据量增大后按 00 文档 A9 评估专用向量库。
CREATE TABLE kb_chunk (
  id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  kb_item_version_id BIGINT UNSIGNED NOT NULL,
  chunk_no           INT UNSIGNED    NOT NULL,
  text               TEXT            NOT NULL,
  token_count        INT UNSIGNED    NOT NULL,
  embedding_model    VARCHAR(64)     NULL COMMENT '如 text-embedding-v4',
  embedding_dim      SMALLINT UNSIGNED NULL COMMENT '如 1024（CMTEB 70.14，性能与成本平衡点）',
  embedding_dense    BLOB            NULL COMMENT 'float32 数组',
  embedding_sparse   JSON            NULL COMMENT '稀疏向量 {token_id: weight}',
  created_at         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_chunk (kb_item_version_id, chunk_no),
  KEY idx_chunk_model (embedding_model, embedding_dim),
  CONSTRAINT fk_chunk_kbv FOREIGN KEY (kb_item_version_id) REFERENCES kb_item_version(id),
  -- 单条不得超过模型上限，超限在入库前就被拒绝
  CONSTRAINT ck_chunk_tokens CHECK (token_count <= 8192)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识切片与向量';

-- ── 风险规则 ────────────────────────────────────────────────────────────────
-- ruleset_version 会写入 risk_case，使"规则变了所以结论变了"可被解释。
CREATE TABLE risk_rule (
  id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  rule_code        VARCHAR(64)     NOT NULL COMMENT '如 ABSOLUTE_CLAIM_01',
  name             VARCHAR(200)    NOT NULL,
  rule_type        VARCHAR(48)     NOT NULL COMMENT '与 risk_case.risk_type 对齐',
  description      TEXT            NOT NULL,
  severity_default VARCHAR(8)      NOT NULL COMMENT '默认风险等级',
  applicable_scope JSON            NULL COMMENT '适用物料类型/渠道/业务',
  ruleset_version  VARCHAR(32)     NOT NULL,
  status           VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE',
  created_at       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_rule (rule_code, ruleset_version),
  KEY idx_rule_type_status (rule_type, status),
  CONSTRAINT ck_rule_sev CHECK (severity_default IN ('HIGH','MEDIUM','LOW')),
  CONSTRAINT ck_rule_status CHECK (status IN ('ACTIVE','RETIRED')),
  CONSTRAINT ck_rule_type CHECK (rule_type IN
    ('ABSOLUTE_CLAIM','EVIDENCE_MISSING','SAFETY_PROMISE','COMPETITOR_COMPARISON',
     'PRICE_CLAIM','DISCLAIMER_MISSING','MISLEADING','OTHER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风险规则';

CREATE TABLE risk_rule_kb_ref (
  rule_id            BIGINT UNSIGNED NOT NULL,
  kb_item_version_id BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY (rule_id, kb_item_version_id),
  KEY idx_rrkb_kbv (kb_item_version_id),
  CONSTRAINT fk_rrkb_rule FOREIGN KEY (rule_id) REFERENCES risk_rule(id),
  CONSTRAINT fk_rrkb_kbv FOREIGN KEY (kb_item_version_id) REFERENCES kb_item_version(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='规则-知识依据关联';
