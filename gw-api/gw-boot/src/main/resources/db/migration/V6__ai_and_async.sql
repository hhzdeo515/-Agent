-- ============================================================================
-- V6 AI 调用记录、异步任务、操作审计、助手临时文件
-- 对应设计文档：01-数据模型与DDL.md §4.9 / §4.10 / §4.11 / §4.12
-- 依据：AGENTS.md 第 12 条（审计与合规）、第 14 条（异步、幂等、可重试）
-- ============================================================================

SET NAMES utf8mb4;

-- ── AI 调用记录与成本归集 ────────────────────────────────────────────────────
-- 记录 model_id / pipeline_version / prompt_version / ruleset_version 四件套，
-- 使"为什么同一份材料这次判得不一样"可以被解释（00 文档 ADR D-10）。
--
-- 成本监控口径提醒：qwen3-vl-8b-thinking、qwen-vl-ocr、qwen3.5-ocr 均不支持上下文缓存，
-- 因此核心指标是 batch_mode 占比与单 Case token 成本，而不是缓存命中率。
CREATE TABLE ai_invocation (
  id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  case_id          BIGINT UNSIGNED NULL,
  risk_case_id     BIGINT UNSIGNED NULL,
  stage            VARCHAR(32)     NOT NULL
                   COMMENT 'PARSE_OCR/PARSE_ASR/PARSE_DOC/VISUAL/CANDIDATE/RETRIEVAL/RERANK/JUDGE/STRUCT_OUT/REPORT/ASSISTANT/EMBED',
  provider         VARCHAR(32)     NOT NULL DEFAULT 'DASHSCOPE',
  model_id         VARCHAR(64)     NOT NULL,
  region           VARCHAR(24)     NULL COMMENT '如 cn-beijing',
  batch_mode       TINYINT(1)      NOT NULL DEFAULT 0 COMMENT '是否走 Batch（单价约减半）',
  prompt_version   VARCHAR(32)     NULL,
  pipeline_version VARCHAR(32)     NULL,
  ruleset_version  VARCHAR(32)     NULL,
  input_tokens     INT UNSIGNED    NOT NULL DEFAULT 0,
  output_tokens    INT UNSIGNED    NOT NULL DEFAULT 0,
  cache_hit_tokens INT UNSIGNED    NOT NULL DEFAULT 0,
  latency_ms       INT UNSIGNED    NOT NULL DEFAULT 0,
  success          TINYINT(1)      NOT NULL,
  error_code       VARCHAR(64)     NULL,
  retry_of         BIGINT UNSIGNED NULL,
  request_id       VARCHAR(96)     NULL COMMENT '供应商 request_id，便于对账',
  created_at       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_ai_case (case_id, created_at),
  KEY idx_ai_stage_model (stage, model_id, created_at),
  KEY idx_ai_success (success, created_at),
  KEY idx_ai_batch (batch_mode, created_at),
  CONSTRAINT fk_ai_case FOREIGN KEY (case_id) REFERENCES audit_case(id),
  CONSTRAINT fk_ai_risk FOREIGN KEY (risk_case_id) REFERENCES risk_case(id),
  CONSTRAINT fk_ai_retry FOREIGN KEY (retry_of) REFERENCES ai_invocation(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 调用记录与成本归集';

-- ── 异步任务 ────────────────────────────────────────────────────────────────
-- uk_async_external 是回调幂等的关键：ASR 的 EventBridge 回调可能重复投递（官方明确说明），
-- 靠这个唯一键保证同一外部 task_id 只落一次结果。
--
-- 安全提醒：HTTP/HTTPS 回调必须校验 X-Eventbridge-Signature*，
-- 否则任意外部 IP 都可伪造 AsyncTaskFinish 事件注入虚假识别结果（00 文档 §2.2）。
CREATE TABLE async_task (
  id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  task_type        VARCHAR(32)     NOT NULL
                   COMMENT 'PARSE/AI_REVIEW/AI_REREVIEW/BATCH_POLL/EXPORT/CLEANUP',
  biz_id           VARCHAR(64)     NOT NULL,
  idempotency_key  VARCHAR(160)    NOT NULL,
  external_task_id VARCHAR(128)    NULL COMMENT '供应商异步任务 ID',
  status           VARCHAR(16)     NOT NULL DEFAULT 'QUEUED'
                   COMMENT 'QUEUED/RUNNING/SUCCEEDED/FAILED/CANCELLED',
  attempt          TINYINT UNSIGNED NOT NULL DEFAULT 0,
  max_attempt      TINYINT UNSIGNED NOT NULL DEFAULT 5,
  next_retry_at    DATETIME(3)     NULL,
  payload          JSON            NULL,
  result           JSON            NULL,
  error_code       VARCHAR(64)     NULL,
  error_message    VARCHAR(500)    NULL,
  created_at       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_async_idem (idempotency_key),
  UNIQUE KEY uk_async_external (task_type, external_task_id),
  KEY idx_async_status (status, next_retry_at),
  KEY idx_async_biz (task_type, biz_id),
  CONSTRAINT ck_async_status CHECK (status IN
    ('QUEUED','RUNNING','SUCCEEDED','FAILED','CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='异步任务';

-- ── 操作审计 ────────────────────────────────────────────────────────────────
-- 严禁把物料原文写入 before_state / after_state：
-- AGENTS.md 第 12 条要求不得把敏感原文完整写入不受控日志。
-- 审计只记录字段级变更摘要与锚点 ID，原文通过锚点 ID 回溯。
CREATE TABLE audit_log (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  actor_id      BIGINT UNSIGNED NULL,
  actor_type    VARCHAR(8)      NOT NULL,
  action        VARCHAR(64)     NOT NULL,
  resource_type VARCHAR(32)     NOT NULL,
  resource_id   VARCHAR(64)     NOT NULL,
  before_state  JSON            NULL COMMENT '字段级摘要，禁止存原文',
  after_state   JSON            NULL COMMENT '字段级摘要，禁止存原文',
  ip            VARCHAR(45)     NULL,
  user_agent    VARCHAR(255)    NULL,
  trace_id      VARCHAR(64)     NULL,
  created_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_audit_resource (resource_type, resource_id, created_at),
  KEY idx_audit_actor (actor_id, created_at),
  KEY idx_audit_trace (trace_id),
  CONSTRAINT fk_audit_actor FOREIGN KEY (actor_id) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作审计';

-- ── AI 法务助手临时文件 ──────────────────────────────────────────────────────
-- AGENTS.md 第 3 条：助手是轻量即时的咨询入口，不能创建正式审核任务。
-- 因此它的临时文件与正式物料严格隔离，并带 TTL；
-- 只有用户走"转为正式审核任务"确认后，才置 PROMOTED 并关联到新建的 material。
CREATE TABLE assistant_session_file (
  id                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  session_id           VARCHAR(64)     NOT NULL,
  user_id              BIGINT UNSIGNED NOT NULL,
  file_object_key      VARCHAR(512)    NOT NULL,
  file_sha256          CHAR(64)        NOT NULL,
  file_size            BIGINT UNSIGNED NOT NULL,
  mime_type            VARCHAR(128)    NOT NULL,
  status               VARCHAR(16)     NOT NULL DEFAULT 'TEMP' COMMENT 'TEMP/PROMOTED/EXPIRED',
  promoted_material_id BIGINT UNSIGNED NULL,
  expires_at           DATETIME(3)     NOT NULL COMMENT '过期由清理任务删除对象，避免临时文件长期留存',
  created_at           DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_asf_session (session_id, created_at),
  KEY idx_asf_expire (status, expires_at),
  KEY idx_asf_user (user_id, created_at),
  CONSTRAINT fk_asf_user FOREIGN KEY (user_id) REFERENCES sys_user(id),
  CONSTRAINT fk_asf_material FOREIGN KEY (promoted_material_id) REFERENCES material(id),
  CONSTRAINT ck_asf_status CHECK (status IN ('TEMP','PROMOTED','EXPIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 法务助手临时文件（带 TTL）';
