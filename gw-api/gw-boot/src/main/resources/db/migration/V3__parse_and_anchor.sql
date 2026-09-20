-- ============================================================================
-- V3 解析任务、解析产物、证据锚点
-- 对应设计文档：01-数据模型与DDL.md §4.4 / §4.5；抽象来源 00-技术方案与架构设计.md §3
-- 依据：AGENTS.md 第 4 条（解析并保留位置映射）、第 11 条（结构化输出）
-- ============================================================================

SET NAMES utf8mb4;

-- ── 解析任务 ────────────────────────────────────────────────────────────────
CREATE TABLE parse_job (
  id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  material_version_id BIGINT UNSIGNED NOT NULL,
  job_type            VARCHAR(32)     NOT NULL COMMENT 'FULL/OCR/ASR/DOC/FRAME/RETRY',
  status              VARCHAR(16)     NOT NULL DEFAULT 'QUEUED'
                      COMMENT 'QUEUED/RUNNING/SUCCEEDED/FAILED',
  -- AGENTS.md 第 14 条要求展示真实进度。进度必须有可计量分母：
  -- 视频=已处理毫秒/总毫秒，PDF/PPT=已处理页/总页，图片=单图，批量=已完成物料数。
  progress            TINYINT UNSIGNED NOT NULL DEFAULT 0,
  progress_total      BIGINT UNSIGNED NULL COMMENT '进度分母（如总毫秒、总页数）',
  progress_current    BIGINT UNSIGNED NULL COMMENT '进度分子',
  attempt             TINYINT UNSIGNED NOT NULL DEFAULT 0,
  idempotency_key     VARCHAR(160)    NOT NULL,
  pipeline_version    VARCHAR(32)     NOT NULL,
  external_task_id    VARCHAR(128)    NULL COMMENT '供应商异步任务 ID（如 ASR task_id）',
  error_code          VARCHAR(64)     NULL,
  error_message       VARCHAR(500)    NULL,
  started_at          DATETIME(3)     NULL,
  finished_at         DATETIME(3)     NULL,
  created_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  -- 同一版本 + 同一流水线版本只跑一次（AGENTS.md 第 14 条：避免重复提交产生重复风险）
  UNIQUE KEY uk_parse_idem (idempotency_key),
  KEY idx_parse_version (material_version_id),
  KEY idx_parse_status (status, created_at),
  KEY idx_parse_external (external_task_id),
  CONSTRAINT fk_parse_version FOREIGN KEY (material_version_id) REFERENCES material_version(id),
  CONSTRAINT ck_parse_status CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED')),
  CONSTRAINT ck_parse_progress CHECK (progress <= 100),
  CONSTRAINT ck_parse_job_type CHECK (job_type IN ('FULL','OCR','ASR','DOC','FRAME','RETRY'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='解析任务';

-- ── 解析产物 ────────────────────────────────────────────────────────────────
-- 注意 ASR 的 transcription_url 仅 24 小时有效，必须及时落盘，
-- 因此记录 raw_object_key 与 source_expires_at 以便提前告警（00 文档 §2.2 陷阱 3）。
CREATE TABLE parse_artifact (
  id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  parse_job_id        BIGINT UNSIGNED NOT NULL,
  material_version_id BIGINT UNSIGNED NOT NULL,
  artifact_type       VARCHAR(24)     NOT NULL
                      COMMENT 'OCR/ASR/ASR_RAW/KEYFRAME/SUBTITLE/DOC_TEXT/VISUAL/SCENE',
  payload             JSON            NULL COMMENT '结构化产物',
  raw_object_key      VARCHAR(512)    NULL COMMENT '原始结果落盘键',
  source_expires_at   DATETIME(3)     NULL COMMENT '源 URL 过期时间（ASR 为 24h），用于未落盘告警',
  engine              VARCHAR(64)     NOT NULL COMMENT '如 qwen-vl-ocr',
  engine_version      VARCHAR(64)     NULL COMMENT '快照版本，如 2025-11-20',
  created_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_artifact_job (parse_job_id),
  KEY idx_artifact_version_type (material_version_id, artifact_type),
  KEY idx_artifact_expire (source_expires_at),
  CONSTRAINT fk_artifact_job FOREIGN KEY (parse_job_id) REFERENCES parse_job(id),
  CONSTRAINT fk_artifact_version FOREIGN KEY (material_version_id) REFERENCES material_version(id),
  CONSTRAINT ck_artifact_type CHECK (artifact_type IN
    ('OCR','ASR','ASR_RAW','KEYFRAME','SUBTITLE','DOC_TEXT','VISUAL','SCENE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='解析产物';

-- ── 证据锚点 ────────────────────────────────────────────────────────────────
-- 这是整套定位能力的地基（00 文档 §3）：
--   解析阶段产出带稳定 ID 的锚点 → 模型输出风险时只引用 anchor_id → 定位 = ID 查表，可 100% 校验。
-- 这样既避免了 VLM 坐标不可信，也避免了用风险原文做字符串模糊匹配的静默失败。
--
-- locator 结构按 anchor_type 区分（01 文档 §6）：
--   TEXT_LINE       { bbox, polygon?, image_width, image_height, scale_ratio, page_no?, ocr_line_no? }
--   SPEECH_SENTENCE { begin_ms, end_ms, sentence_id, speaker_id?, language?, emotion? }
--   SUBTITLE_LINE   { begin_ms, end_ms, frame_ts_ms, bbox, frame_width, frame_height, scale_ratio }
--   KEY_FRAME       { ts_ms, scene_id, object_key, frame_width, frame_height, scale_ratio, visual_description? }
--   DOC_*           { page_no?, para_index, sentence_index?, char_range, rendered_page_key? }
--
-- 单位约定（01 文档 §6.1）：bbox 左上原点、像素；时间戳毫秒整数；
-- char_range 用 UTF-16 code unit（对齐 JS String.length）；scale_ratio 保留 6 位小数。
CREATE TABLE evidence_anchor (
  id                    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  anchor_id             VARCHAR(32)     NOT NULL COMMENT '业务锚点 ID，如 A-0007；模型引用此值',
  material_version_id   BIGINT UNSIGNED NOT NULL,
  anchor_type           VARCHAR(24)     NOT NULL,
  locator               JSON            NOT NULL,
  text                  TEXT            NULL COMMENT '锚点文本（画面类可为空）',
  confidence            DECIMAL(5,4)    NULL COMMENT 'OCR/ASR 置信度 0-1',
  source_engine         VARCHAR(64)     NOT NULL,
  source_engine_version VARCHAR(64)     NULL,
  ordinal               INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '同类型内顺序，用于稳定排序',
  created_at            DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  -- 复合唯一键是 risk_anchor_ref 复合外键的引用目标，使"锚点必须存在"可由数据库保证
  UNIQUE KEY uk_anchor_vid_aid (material_version_id, anchor_id),
  KEY idx_anchor_version_type (material_version_id, anchor_type, ordinal),
  KEY idx_anchor_confidence (material_version_id, confidence),
  KEY idx_anchor_engine (source_engine, source_engine_version),
  CONSTRAINT fk_anchor_version FOREIGN KEY (material_version_id) REFERENCES material_version(id),
  CONSTRAINT ck_anchor_type CHECK (anchor_type IN
    ('TEXT_LINE','SPEECH_SENTENCE','SUBTITLE_LINE','KEY_FRAME','DOC_PARAGRAPH','DOC_SENTENCE')),
  CONSTRAINT ck_anchor_confidence CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='证据锚点（版本内不可变）';
