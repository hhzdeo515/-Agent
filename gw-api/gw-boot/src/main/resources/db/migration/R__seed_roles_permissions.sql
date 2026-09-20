-- ============================================================================
-- R 种子数据：角色与权限点（可重复执行）
-- 对应设计文档：02-状态机与权限矩阵.md §4
--
-- ⚠️ 权限点 code 必须与 gw-common 的 PermCode 常量、前端 TS 联合类型三处同源。
--
-- 设计要点：
--   * LEGAL 是唯一持有签名、关闭风险、批准版本权限的角色。
--   * BRAND/DESIGN/BIZ 可以提交物料与上传修改版本，但不能代替法务关闭风险或批准
--     （AGENTS.md 第 2 条）。
--   * ADMIN 刻意不具备业务审批权——这是职责分离要求。若企业规模小、同一人兼任，
--     必须使用两个独立账号，而不是给 ADMIN 叠加法务权限。
--   * AI_SERVICE 是 AI 编排链路使用的服务账号角色，刻意不持有任何 NEVER_FOR_AI 权限：
--     这不是"代码里拦一下"，而是从凭据层面就做不到（AGENTS.md 第 1、8 条）。
-- ============================================================================

SET NAMES utf8mb4;

-- ── 角色 ────────────────────────────────────────────────────────────────────
INSERT INTO sys_role (code, name, description) VALUES
  ('LEGAL',      '法务',   '设置审核关注点、确认风险、处理争议、终审、签名、批准'),
  ('BRAND',      '品牌',   '提交物料、查看整改意见、上传修改版本；不能关闭风险或批准'),
  ('DESIGN',     '设计',   '提交物料、查看整改意见、上传修改版本；不能关闭风险或批准'),
  ('BIZ',        '业务',   '提交物料、查看整改意见、上传修改版本；不能关闭风险或批准'),
  ('ADMIN',      '管理员', '维护权限、知识库来源、企业规则、产品参数、审计配置；无业务审批权'),
  ('AI_SERVICE', 'AI 服务', 'AI 编排链路专用服务账号；无任何签名/关闭/批准权限')
ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description);

-- ── 权限点 ──────────────────────────────────────────────────────────────────
INSERT INTO sys_permission (code, name, category) VALUES
  -- 审核任务 Case
  ('case.view',                  '查看审核任务详情', 'API'),
  ('case.list',                  '查看任务列表',     'API'),
  ('case.create',                '创建审核任务',     'API'),
  ('case.edit',                  '编辑任务信息',     'API'),
  ('case.upload',                '上传物料',         'API'),
  ('case.parse_retry',           '重试解析',         'API'),
  ('case.requirement_confirm',   '确认审核要求',     'API'),
  ('case.start_initial_review',  '启动 AI 初审',     'API'),
  ('case.reject',                '驳回审核任务',     'API'),
  ('case.resume_remediation',    '恢复整改',         'API'),
  ('case.back_to_feedback',      '退回反馈区',       'API'),
  ('case.archive',               '归档任务',         'API'),
  -- 风险
  ('risk.view',                  '查看风险',         'API'),
  ('risk.confirm',               '确认风险',         'API'),
  ('risk.false_positive',        '标记误判',         'API'),
  ('risk.request_evidence',      '要求补充材料',     'API'),
  ('risk.to_remediation',        '转入整改',         'API'),
  ('risk.rereview_trigger',      '触发 AI 复审',     'API'),
  ('risk.legal_final_review',    '法务终审',         'API'),
  ('risk.sign',                  '法务签名',         'API'),
  ('risk.close',                 '关闭风险',         'API'),
  -- 物料版本
  ('version.view',               '查看物料版本',     'API'),
  ('version.upload',             '上传整改版本',     'API'),
  ('version.diff_view',          '查看版本差异',     'API'),
  -- 最终批准
  ('material.approve',           '标记最终批准版本', 'API'),
  ('material.revoke_approval',   '撤销批准',         'API'),
  -- 报告
  ('report.view',                '查看初审报告',     'API'),
  ('report.export',              '导出初审报告',     'API'),
  -- AI 法务助手
  ('assistant.use',              '使用 AI 法务助手', 'API'),
  ('assistant.file_upload',      '助手内临时上传',   'API'),
  ('assistant.to_formal_case',   '转为正式审核任务', 'API'),
  -- 知识库
  ('kb.view',                    '查看知识库',       'API'),
  ('kb.edit',                    '编辑知识条目',     'API'),
  ('kb.publish',                 '发布知识',         'API'),
  -- 管理
  ('admin.user_manage',          '用户与角色管理',   'API'),
  ('admin.rule_manage',          '企业规则与产品参数维护', 'API'),
  ('admin.audit_view',           '查看审计日志',     'API'),
  ('admin.config',               '系统参数配置',     'API')
ON DUPLICATE KEY UPDATE name = VALUES(name), category = VALUES(category);

-- ── 角色 → 权限 ─────────────────────────────────────────────────────────────

-- LEGAL：全部业务权限（唯一可签名、关闭、批准的角色）
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r CROSS JOIN sys_permission p
 WHERE r.code = 'LEGAL'
   AND p.code IN (
     'case.view','case.list','case.create','case.edit','case.upload','case.parse_retry',
     'case.requirement_confirm','case.start_initial_review','case.reject',
     'case.resume_remediation','case.back_to_feedback','case.archive',
     'risk.view','risk.confirm','risk.false_positive','risk.request_evidence',
     'risk.to_remediation','risk.rereview_trigger','risk.legal_final_review',
     'risk.sign','risk.close',
     'version.view','version.upload','version.diff_view',
     'material.approve','material.revoke_approval',
     'report.view','report.export',
     'assistant.use','assistant.file_upload','assistant.to_formal_case',
     'kb.view','kb.edit','kb.publish','admin.rule_manage','admin.audit_view')
ON DUPLICATE KEY UPDATE role_id = role_id;

-- BRAND：提交物料、上传修改版本、查看与导出；不能确认风险、不能关闭、不能批准
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r CROSS JOIN sys_permission p
 WHERE r.code = 'BRAND'
   AND p.code IN (
     'case.view','case.list','case.create','case.upload','case.parse_retry',
     'risk.view','version.view','version.upload','version.diff_view',
     'report.view','report.export',
     'assistant.use','assistant.file_upload','assistant.to_formal_case','kb.view')
ON DUPLICATE KEY UPDATE role_id = role_id;

-- DESIGN：同 BRAND
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r CROSS JOIN sys_permission p
 WHERE r.code = 'DESIGN'
   AND p.code IN (
     'case.view','case.list','case.upload','case.parse_retry',
     'risk.view','version.view','version.upload','version.diff_view',
     'report.view','assistant.use','assistant.file_upload','kb.view')
ON DUPLICATE KEY UPDATE role_id = role_id;

-- BIZ：同 BRAND
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r CROSS JOIN sys_permission p
 WHERE r.code = 'BIZ'
   AND p.code IN (
     'case.view','case.list','case.create','case.upload','case.parse_retry',
     'risk.view','version.view','version.upload','version.diff_view',
     'report.view','report.export',
     'assistant.use','assistant.file_upload','assistant.to_formal_case','kb.view')
ON DUPLICATE KEY UPDATE role_id = role_id;

-- ADMIN：维护类权限，刻意不含任何审批权（职责分离）
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r CROSS JOIN sys_permission p
 WHERE r.code = 'ADMIN'
   AND p.code IN (
     'case.view','case.list','case.archive',
     'risk.view','version.view','report.view',
     'kb.view','kb.edit',
     'admin.user_manage','admin.rule_manage','admin.audit_view','admin.config')
ON DUPLICATE KEY UPDATE role_id = role_id;

-- AI_SERVICE：只读 + 可触发 AI 复审所需的最小权限。
-- 刻意不含 risk.confirm / risk.false_positive / risk.sign / risk.close /
-- material.approve / case.reject / kb.publish —— 即 PermCode.NEVER_FOR_AI 全集。
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r CROSS JOIN sys_permission p
 WHERE r.code = 'AI_SERVICE'
   AND p.code IN ('case.view','case.list','risk.view','version.view','kb.view')
ON DUPLICATE KEY UPDATE role_id = role_id;
