-- ============================================================================
-- V12 系统配置：让"设置"里的 AI 参数可以真正保存并生效
--
-- 为什么需要一张表而不是只靠环境变量：
--   环境变量在容器启动后无法修改，改一次要重建容器；而 AI 供应商、模型型号、
--   超时这些恰恰是最需要现场调整的参数（换模型、限流降配、排查超时）。
--   把可调参数放进配置表后，界面上改一次即刻生效，且留有"谁在什么时候改了什么"。
--
-- 为什么区分 is_secret：
--   API Key 与普通参数的安全级别不同。标记为 secret 的值在数据库中<b>加密存储</b>、
--   接口<b>永不回显</b>（只返回掩码），并且在日志里也不落明文（AGENTS.md 第 12 条）。
-- ============================================================================

SET NAMES utf8mb4;

CREATE TABLE sys_config (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  config_key   VARCHAR(128)    NOT NULL COMMENT '配置键，点分命名，如 ai.provider',
  config_value TEXT            NULL     COMMENT '配置值；is_secret=1 时为 AES-GCM 密文（Base64）',
  is_secret    TINYINT(1)      NOT NULL DEFAULT 0 COMMENT '1=敏感值，加密存储且接口不回显',
  description  VARCHAR(255)    NULL     COMMENT '这个键是干什么的，便于运维直接看库',
  updated_by   BIGINT UNSIGNED NULL     COMMENT '最后修改人；系统初始化为空',
  created_at   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_config_key (config_key),
  CONSTRAINT fk_config_updater FOREIGN KEY (updated_by) REFERENCES sys_user(id),
  CONSTRAINT ck_config_secret CHECK (is_secret IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统配置（可覆盖环境变量默认值）';

-- 说明几条与 AI 相关的键，避免后来者只能靠猜：
INSERT INTO sys_config (config_key, config_value, is_secret, description) VALUES
  ('ai.provider',        NULL, 0, 'AI 供应商：mock（规则实现）| dashscope（阿里云百炼千问）'),
  ('ai.dashscope.api-key', NULL, 1, '百炼 API Key（加密存储，接口只返回掩码）'),
  ('ai.models.text',     NULL, 0, '文本主力模型，如 qwen-plus'),
  ('ai.models.vision',   NULL, 0, '画面语义理解模型，如 qwen3-vl-8b-thinking'),
  ('ai.models.ocr-primary', NULL, 0, '文字识别主模型，如 qwen-vl-ocr'),
  ('ai.models.ocr-fallback', NULL, 0, '文字识别兜底模型，如 qwen3.5-ocr'),
  ('ai.models.asr',      NULL, 0, '语音转写模型，如 qwen3-asr-flash-filetrans'),
  ('ai.models.embedding', NULL, 0, '向量化模型，如 text-embedding-v4'),
  ('ai.models.rerank',   NULL, 0, '重排序模型，如 qwen3-rerank'),
  ('ai.http.read-timeout-ms', NULL, 0, '模型读取超时（毫秒）；视觉与 OCR 是长请求'),
  ('ai.http.max-attempts',    NULL, 0, '失败重试次数（含首次）')
ON DUPLICATE KEY UPDATE description = VALUES(description);
