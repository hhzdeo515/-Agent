-- ============================================================================
-- V10 口径视图：物料三分类与初审通过率
-- 对应设计文档：01-数据模型与DDL.md §8
--
-- 为什么把口径固化成视图：
--   AGENTS.md 第 6 条要求"通过率的计算口径必须在界面中明确，不能把 Risk Case 数量和
--   物料数量混为一谈"。把口径写成视图后，界面与报告直接取视图值，
--   各处自行计算导致"界面说通过、库里说风险"的可能性就被消除了。
-- ============================================================================

SET NAMES utf8mb4;

CREATE OR REPLACE VIEW v_material_initial_review AS
SELECT
  m.id                  AS material_id,
  m.case_id             AS case_id,
  m.name                AS material_name,
  m.material_type       AS material_type,
  m.parse_status        AS parse_status,
  m.current_version_id  AS current_version_id,
  CASE
    -- 第三类：风险未通过 —— 存在阻断性且未被判误判/未关闭的风险
    WHEN EXISTS (
      SELECT 1 FROM risk_case r
       WHERE r.material_id = m.id
         AND r.blocked = 1
         AND r.status NOT IN ('REJECTED_FALSE_POSITIVE','CLOSED')
         AND r.risk_level IN ('HIGH','MEDIUM')
    ) THEN 'RISK_FAIL'

    -- 第二类：待人工判断 —— 存在待处理风险
    WHEN EXISTS (
      SELECT 1 FROM risk_case r
       WHERE r.material_id = m.id
         AND r.status IN ('OPEN','PENDING_LEGAL_DECISION','AWAITING_EVIDENCE')
    ) THEN 'PENDING_HUMAN'

    -- 部分解析：即使无风险项也不得算"初审通过"。
    -- 理由：解析不完整意味着"没发现风险"可能只是"没看到"（01 文档 §8.1）。
    WHEN m.parse_status = 'PARTIAL' THEN 'PENDING_HUMAN'

    -- 第一类：AI 初审通过。注意这不等于法务最终批准。
    WHEN m.parse_status = 'SUCCEEDED' THEN 'INITIAL_PASS'

    ELSE 'NOT_REVIEWED'
  END AS initial_review_class,
  (SELECT COUNT(*) FROM risk_case r WHERE r.material_id = m.id) AS risk_count,
  (SELECT COUNT(*) FROM risk_case r
    WHERE r.material_id = m.id
      AND r.risk_level = 'HIGH'
      AND r.status NOT IN ('REJECTED_FALSE_POSITIVE')) AS high_risk_count,
  (SELECT COUNT(*) FROM risk_case r
    WHERE r.material_id = m.id AND r.blocked = 1
      AND r.status NOT IN ('REJECTED_FALSE_POSITIVE','CLOSED')) AS open_blocking_risk_count
FROM material m
WHERE m.parse_status IN ('SUCCEEDED','PARTIAL','FAILED');


CREATE OR REPLACE VIEW v_case_initial_review_summary AS
SELECT
  c.id            AS case_id,
  c.case_no       AS case_no,
  c.name          AS case_name,
  c.status        AS case_status,
  -- 分母：仅"参与初审"的物料（解析成功或部分成功）。
  -- 解析失败物料不计入分母，单独列出——否则解析失败会拉低通过率，掩盖真实审核情况。
  SUM(CASE WHEN m.parse_status IN ('SUCCEEDED','PARTIAL') THEN 1 ELSE 0 END) AS reviewed_material_count,
  SUM(CASE WHEN m.parse_status = 'FAILED' THEN 1 ELSE 0 END)                  AS parse_failed_count,
  COUNT(m.id)                                                                 AS total_material_count,
  SUM(CASE WHEN v.initial_review_class = 'INITIAL_PASS'  THEN 1 ELSE 0 END)   AS initial_pass_count,
  SUM(CASE WHEN v.initial_review_class = 'PENDING_HUMAN' THEN 1 ELSE 0 END)   AS pending_human_count,
  SUM(CASE WHEN v.initial_review_class = 'RISK_FAIL'     THEN 1 ELSE 0 END)   AS risk_fail_count,
  (SELECT COUNT(*) FROM risk_case r WHERE r.case_id = c.id AND r.risk_level = 'HIGH')   AS high_risk_items,
  (SELECT COUNT(*) FROM risk_case r WHERE r.case_id = c.id AND r.risk_level = 'MEDIUM') AS medium_risk_items,
  (SELECT COUNT(*) FROM risk_case r WHERE r.case_id = c.id AND r.risk_level = 'LOW')    AS low_risk_items,
  (SELECT COUNT(*) FROM risk_case r
    WHERE r.case_id = c.id AND r.blocked = 1
      AND r.status NOT IN ('REJECTED_FALSE_POSITIVE','CLOSED'))                AS open_blocking_risk_items,
  -- 物料层初审通过率 = 初审通过物料数 / 参与初审物料数
  ROUND(
    SUM(CASE WHEN v.initial_review_class = 'INITIAL_PASS' THEN 1 ELSE 0 END)
    / NULLIF(SUM(CASE WHEN m.parse_status IN ('SUCCEEDED','PARTIAL') THEN 1 ELSE 0 END), 0)
  , 4) AS initial_pass_rate_by_material
FROM audit_case c
LEFT JOIN material m ON m.case_id = c.id
LEFT JOIN v_material_initial_review v ON v.material_id = m.id
GROUP BY c.id, c.case_no, c.name, c.status;
