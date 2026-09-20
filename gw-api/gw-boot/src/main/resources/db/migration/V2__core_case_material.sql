-- ============================================================================
-- V2 审核任务、物料、物料版本、审核要求模板
-- 对应设计文档：01-数据模型与DDL.md §4.1 / §4.2 / §4.3 / §4.12
-- 依据：AGENTS.md 第 4 条（接收区）、第 7 条（版本不可覆盖）、第 9 条（数据模型）
-- ============================================================================

SET NAMES utf8mb4;

-- ── 审核任务 Case ───────────────────────────────────────────────────────────
CREATE TABLE audit_case (
  id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  case_no           VARCHAR(32)     NOT NULL COMMENT '业务编号，如 GX-2026-0001',
  name              VARCHAR(200)    NOT NULL,
  project_id        BIGINT UNSIGNED NOT NULL,
  submitter_id      BIGINT UNSIGNED NOT NULL COMMENT '提交人',
  owner_id          BIGINT UNSIGNED NOT NULL COMMENT '负责人（法务）',
  deadline          DATETIME(3)     NULL COMMENT '审核截止时间',
  status            VARCHAR(32)     NOT NULL DEFAULT 'DRAFT',
  review_requirement JSON           NULL COMMENT '审核要求（结构化关注点清单）',
  requirement_template_code VARCHAR(64) NULL COMMENT '所用模板；为空表示未用模板',
  -- AGENTS.md 第 4 条：不得把模板内容默认为本次任务的正式要求，用户选择或确认后才生效。
  -- 因此「模板被选用」与「要求已生效」是两件事，后者必须有确认人与确认时间。
  requirement_confirmed_by  BIGINT UNSIGNED NULL,
  requirement_confirmed_at  DATETIME(3)     NULL,
  material_count    INT UNSIGNED    NOT NULL DEFAULT 0,
  lock_version      INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '乐观锁',
  created_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_case_no (case_no),
  KEY idx_case_project_status (project_id, status),
  KEY idx_case_owner (owner_id, status),
  KEY idx_case_deadline (deadline),
  CONSTRAINT fk_case_project FOREIGN KEY (project_id) REFERENCES biz_project(id),
  CONSTRAINT fk_case_submitter FOREIGN KEY (submitter_id) REFERENCES sys_user(id),
  CONSTRAINT fk_case_owner FOREIGN KEY (owner_id) REFERENCES sys_user(id),
  CONSTRAINT fk_case_req_confirmer FOREIGN KEY (requirement_confirmed_by) REFERENCES sys_user(id),
  CONSTRAINT ck_case_status CHECK (status IN
    ('DRAFT','PARSING','READY_FOR_REVIEW','AI_REVIEWING','FEEDBACK_PENDING',
     'REMEDIATION','FINAL_REVIEW','APPROVED','REJECTED','ARCHIVED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审核任务';

-- ── 宣传物料 ────────────────────────────────────────────────────────────────
CREATE TABLE material (
  id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  case_id             BIGINT UNSIGNED NOT NULL,
  name                VARCHAR(255)    NOT NULL,
  material_type       VARCHAR(16)     NOT NULL COMMENT 'IMAGE/VIDEO/TEXT/PPT/PDF/WORD',
  parse_status        VARCHAR(24)     NOT NULL DEFAULT 'PENDING'
                      COMMENT 'PENDING/RUNNING/SUCCEEDED/FAILED/PARTIAL',
  parse_error_code    VARCHAR(64)     NULL,
  parse_error_message VARCHAR(500)    NULL COMMENT '明确告诉用户需重传或补充什么',
  current_version_id  BIGINT UNSIGNED NULL COMMENT '当前版本；外键在 material_version 建表后追加',
  initial_review_class VARCHAR(24)    NULL COMMENT '冗余缓存列，以 §8 视图口径为准',
  created_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_material_case (case_id),
  KEY idx_material_parse (case_id, parse_status),
  CONSTRAINT fk_material_case FOREIGN KEY (case_id) REFERENCES audit_case(id),
  CONSTRAINT ck_material_type CHECK (material_type IN ('IMAGE','VIDEO','TEXT','PPT','PDF','WORD')),
  CONSTRAINT ck_material_parse CHECK (parse_status IN
    ('PENDING','RUNNING','SUCCEEDED','FAILED','PARTIAL')),
  CONSTRAINT ck_material_class CHECK (initial_review_class IS NULL OR initial_review_class IN
    ('INITIAL_PASS','PENDING_HUMAN','RISK_FAIL','NOT_REVIEWED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='宣传物料';

-- ── 物料版本（不可覆盖） ─────────────────────────────────────────────────────
-- AGENTS.md 第 7 条：不得使用新文件覆盖旧版本，不得因重新上传而删除原 Risk Case。
-- 本表的不可变性由 V8 的触发器强制（拒绝 UPDATE / DELETE）。
CREATE TABLE material_version (
  id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  material_id       BIGINT UNSIGNED NOT NULL,
  version_no        INT UNSIGNED    NOT NULL COMMENT 'V1=1, V2=2 ...',
  version_label     VARCHAR(16)     NOT NULL COMMENT 'V1/V2/V3',
  file_object_key   VARCHAR(512)    NOT NULL COMMENT 'MinIO 对象键（含 sha256，天然不可变）',
  file_sha256       CHAR(64)        NOT NULL,
  file_size         BIGINT UNSIGNED NOT NULL,
  mime_type         VARCHAR(128)    NOT NULL,
  uploader_id       BIGINT UNSIGNED NOT NULL,
  upload_reason     VARCHAR(1000)   NULL COMMENT '修改说明（V2+ 必填，由 CHECK 强制）',
  parent_version_id BIGINT UNSIGNED NULL COMMENT '上一版本',
  media_meta        JSON            NULL COMMENT '媒体元数据：duration_ms/width/height/page_count/frame_count',
  created_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_version_material_no (material_id, version_no),
  -- 同物料下同哈希只允许一个版本：重复上传天然幂等（AGENTS.md 第 14 条）
  UNIQUE KEY uk_version_sha (material_id, file_sha256),
  KEY idx_version_material (material_id, version_no),
  CONSTRAINT fk_version_material FOREIGN KEY (material_id) REFERENCES material(id),
  CONSTRAINT fk_version_parent FOREIGN KEY (parent_version_id) REFERENCES material_version(id),
  CONSTRAINT fk_version_uploader FOREIGN KEY (uploader_id) REFERENCES sys_user(id),
  CONSTRAINT ck_version_no CHECK (version_no >= 1),
  -- V2 及以后必须有修改说明
  CONSTRAINT ck_version_reason CHECK (
    version_no = 1 OR (upload_reason IS NOT NULL AND CHAR_LENGTH(upload_reason) > 0)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='物料版本（不可覆盖）';

-- 循环外键：material.current_version_id → material_version.id
ALTER TABLE material
  ADD CONSTRAINT fk_material_current_version
  FOREIGN KEY (current_version_id) REFERENCES material_version(id);

-- ── 审核要求模板 ────────────────────────────────────────────────────────────
-- 模板只是"候选关注点"，选用后必须经人工确认才生效（生效标记在 audit_case 上）
CREATE TABLE review_requirement_template (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  code       VARCHAR(64)     NOT NULL,
  name       VARCHAR(128)    NOT NULL,
  content    JSON            NOT NULL COMMENT '关注点清单（结构化）',
  scope      VARCHAR(24)     NOT NULL DEFAULT 'GLOBAL' COMMENT 'GLOBAL/PROJECT',
  project_id BIGINT UNSIGNED NULL COMMENT 'scope=PROJECT 时必填',
  status     VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE',
  created_by BIGINT UNSIGNED NOT NULL,
  created_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_tpl_code (code),
  KEY idx_tpl_scope (scope, project_id, status),
  CONSTRAINT fk_tpl_project FOREIGN KEY (project_id) REFERENCES biz_project(id),
  CONSTRAINT fk_tpl_creator FOREIGN KEY (created_by) REFERENCES sys_user(id),
  CONSTRAINT ck_tpl_status CHECK (status IN ('ACTIVE','RETIRED')),
  CONSTRAINT ck_tpl_scope CHECK (scope IN ('GLOBAL','PROJECT')),
  CONSTRAINT ck_tpl_project CHECK (scope = 'GLOBAL' OR project_id IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审核要求模板';
