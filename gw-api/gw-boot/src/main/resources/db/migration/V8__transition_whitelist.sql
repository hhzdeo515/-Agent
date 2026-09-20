-- ============================================================================
-- V8 状态跃迁白名单
-- 对应设计文档：01-数据模型与DDL.md §7.3；02-状态机与权限矩阵.md §3.3
--
-- 用一张表而不是把几十个 IF 写死在触发器里，好处是：
--   1) V9 的触发器只需一次 EXISTS 查询即可拦截非法跃迁；
--   2) 可以写集成测试断言"本文档集合 == 本表集合 == RiskTransitionRules 类集合"，
--      让"三处一致"从口头承诺变成可执行断言（02 文档 §5.3）。
--
-- ⚠️ 本表内容必须与 gw-common 的 RiskTransitionRules 逐条一致。
--    修改任何一处都必须同步另一处，否则集成测试会失败——这正是它存在的意义。
-- ============================================================================

SET NAMES utf8mb4;

CREATE TABLE risk_status_transition (
  from_status  VARCHAR(32) NOT NULL,
  to_status    VARCHAR(32) NOT NULL,
  allowed_role VARCHAR(32) NULL COMMENT 'NULL 表示 AI/SYSTEM 也可触发；LEGAL 表示必须法务',
  PRIMARY KEY (from_status, to_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风险状态跃迁白名单';

-- 与 RiskTransitionRules 的 16 条跃迁一一对应
INSERT INTO risk_status_transition (from_status, to_status, allowed_role) VALUES
  -- OPEN：AI 可转人工；法务可确认 / 判误判 / 要求补料
  ('OPEN',                     'PENDING_LEGAL_DECISION',   NULL),
  ('OPEN',                     'CONFIRMED',                'LEGAL'),
  ('OPEN',                     'REJECTED_FALSE_POSITIVE',  'LEGAL'),
  ('OPEN',                     'AWAITING_EVIDENCE',        'LEGAL'),

  -- 待人工判断：只有法务处理后才能继续流转（AGENTS.md 第 5 条）
  ('PENDING_LEGAL_DECISION',   'CONFIRMED',                'LEGAL'),
  ('PENDING_LEGAL_DECISION',   'REJECTED_FALSE_POSITIVE',  'LEGAL'),
  ('PENDING_LEGAL_DECISION',   'AWAITING_EVIDENCE',        'LEGAL'),

  -- 已确认 → 整改
  ('CONFIRMED',                'AWAITING_REVISION',        'LEGAL'),

  -- 补料后两种走向
  ('AWAITING_EVIDENCE',        'CONFIRMED',                'LEGAL'),
  ('AWAITING_EVIDENCE',        'AWAITING_REVISION',        'LEGAL'),

  -- 整改闭环
  ('AWAITING_REVISION',        'RESUBMITTED',              NULL),
  ('RESUBMITTED',              'AI_REREVIEW',              NULL),
  ('AI_REREVIEW',              'LEGAL_FINAL_REVIEW',       NULL),
  ('AI_REREVIEW',              'AWAITING_REVISION',        NULL),

  -- 法务终审 → 关闭（必须先有签名，由 V9 触发器再校验一次）
  ('LEGAL_FINAL_REVIEW',       'CLOSED',                   'LEGAL'),
  ('LEGAL_FINAL_REVIEW',       'AWAITING_REVISION',        'LEGAL');

-- CLOSED 与 REJECTED_FALSE_POSITIVE 是终态，刻意不插入任何出边：
-- 误判是留痕终态，只记录不改写（AGENTS.md 第 5 条）。
