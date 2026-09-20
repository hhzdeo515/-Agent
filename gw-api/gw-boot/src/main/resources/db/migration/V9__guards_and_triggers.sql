-- ============================================================================
-- V8 数据库级守卫：不可变性、越权关闭拦截、非法状态跃迁拦截、内容变更自动留痕
-- 对应设计文档：01-数据模型与DDL.md §7；02-状态机与权限矩阵.md §5.3
--
-- 为什么这些规则要落到数据库：
--   AGENTS.md 第 8 条要求"前端按钮、后端接口和数据库权限都必须遵守同一套授权规则，
--   不能只在界面上隐藏按钮"。MySQL 没有行级权限，触发器是让"数据库权限"真实生效的
--   唯一手段——它同时挡住了"绕过应用层直接改库"这条路径（补数据脚本、运维手工 SQL）。
-- ============================================================================

SET NAMES utf8mb4;

DELIMITER //

-- ════════════════════════════════════════════════════════════════════════════
-- 一、不可变性：历史记录一律 append-only
--    AGENTS.md 第 7 条：不得使用新文件覆盖旧版本
--    AGENTS.md 第 9 条：不得使用删除历史记录的方式表示风险已经解决
-- ════════════════════════════════════════════════════════════════════════════

CREATE TRIGGER trg_material_version_no_update
BEFORE UPDATE ON material_version FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT = 'material_version is immutable: upload a new version instead of overwriting';
END//

CREATE TRIGGER trg_material_version_no_delete
BEFORE DELETE ON material_version FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT = 'material_version cannot be deleted (audit trail required)';
END//

CREATE TRIGGER trg_legal_signature_no_update
BEFORE UPDATE ON legal_signature FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'legal_signature is append-only';
END//

CREATE TRIGGER trg_legal_signature_no_delete
BEFORE DELETE ON legal_signature FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'legal_signature cannot be deleted';
END//

CREATE TRIGGER trg_review_record_no_update
BEFORE UPDATE ON review_record FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'review_record is append-only';
END//

-- AGENTS.md 第 5 条：被标记为误判的记录不能删除，必须保存法务理由
CREATE TRIGGER trg_review_record_no_delete
BEFORE DELETE ON review_record FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT = 'review_record cannot be deleted (audit trail required)';
END//

CREATE TRIGGER trg_evidence_anchor_no_update
BEFORE UPDATE ON evidence_anchor FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT = 'evidence_anchor is immutable within a version';
END//

CREATE TRIGGER trg_evidence_anchor_no_delete
BEFORE DELETE ON evidence_anchor FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT = 'evidence_anchor cannot be deleted (risk location references it)';
END//

CREATE TRIGGER trg_risk_revision_no_update
BEFORE UPDATE ON risk_case_revision FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'risk_case_revision is append-only';
END//

CREATE TRIGGER trg_risk_revision_no_delete
BEFORE DELETE ON risk_case_revision FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT = 'risk_case_revision cannot be deleted (audit trail required)';
END//

-- ════════════════════════════════════════════════════════════════════════════
-- 二、越权拦截：AI 不得关闭风险、签名人必须是法务
--    AGENTS.md 第 1 条：AI 不得冒充法务给出不可撤销的最终法律结论
--    AGENTS.md 第 8 条：AI 无权代替法务执行最终批准、法务签名或风险关闭
-- ════════════════════════════════════════════════════════════════════════════

-- Risk Case 进入 CLOSED 必须已存在 RISK_CLOSE 签名
CREATE TRIGGER trg_risk_case_close_requires_signature
BEFORE UPDATE ON risk_case FOR EACH ROW
BEGIN
  IF NEW.status = 'CLOSED' AND (OLD.status IS NULL OR OLD.status <> 'CLOSED') THEN
    IF NOT EXISTS (
      SELECT 1 FROM legal_signature
       WHERE risk_case_id = NEW.id AND sign_type = 'RISK_CLOSE'
    ) THEN
      SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'risk_case cannot be CLOSED without a legal signature (AI has no authority)';
    END IF;
  END IF;
END//

-- 签名人必须持有 LEGAL 角色。
-- 注意：这是最后一道兜底，它无法表达项目范围级别的鉴权；完整的授权判断仍在应用层。
-- 它的价值在于确保不存在"绕过应用层直接插签名"的路径。
CREATE TRIGGER trg_signature_requires_privilege
BEFORE INSERT ON legal_signature FOR EACH ROW
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM sys_user_role ur
      JOIN sys_role r ON r.id = ur.role_id
     WHERE ur.user_id = NEW.signer_id AND r.code = 'LEGAL'
  ) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'signer does not hold the LEGAL role';
  END IF;
END//

-- ════════════════════════════════════════════════════════════════════════════
-- 三、非法状态跃迁拦截
--    白名单表在 V9 中创建并填充；此处只做校验，避免把几十个 IF 写死在触发器里。
-- ════════════════════════════════════════════════════════════════════════════

CREATE TRIGGER trg_risk_case_transition_guard
BEFORE UPDATE ON risk_case FOR EACH ROW
BEGIN
  IF NEW.status <> OLD.status THEN
    IF NOT EXISTS (
      SELECT 1 FROM risk_status_transition
       WHERE from_status = OLD.status AND to_status = NEW.status
    ) THEN
      SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'illegal risk_case status transition';
    END IF;

    -- AGENTS.md 第 5 条：待人工判断的风险只有在法务作出处理后才能继续流转；
    -- 第 7 条：只有 AI 复审通过 + 法务确认 + 签名后 Risk Case 才能进入 Closed。
    IF NEW.status = 'CLOSED' AND OLD.status <> 'LEGAL_FINAL_REVIEW' THEN
      SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'risk_case can only be CLOSED from LEGAL_FINAL_REVIEW';
    END IF;
  END IF;
END//

-- ════════════════════════════════════════════════════════════════════════════
-- 四、风险内容变更自动留痕
--    AGENTS.md 第 7 条：若原风险已解决但发现新增风险，应新建关联的 Risk Case，
--    而不是悄悄改变原风险的含义。
--    若 risk_text 是单值且随版本更新，整改推进后就无法还原旧版本的风险原文——本触发器解决该问题。
--
--    取舍：触发器只负责"旧值不丢"，reason 写固定文本、changed_by 留空；
--    变更者的精确身份由应用层在同一事务内写入 review_record。
-- ════════════════════════════════════════════════════════════════════════════

CREATE TRIGGER trg_risk_case_revision_snapshot
BEFORE UPDATE ON risk_case FOR EACH ROW
BEGIN
  DECLARE v_next INT;

  IF NOT (NEW.risk_text        <=> OLD.risk_text)
     OR NOT (NEW.risk_level     <=> OLD.risk_level)
     OR NOT (NEW.confidence     <=> OLD.confidence)
     OR NOT (NEW.suggestion     <=> OLD.suggestion)
     OR NOT (NEW.recommended_copy <=> OLD.recommended_copy)
     OR NOT (NEW.required_evidence <=> OLD.required_evidence) THEN

    -- 用 SELECT ... INTO 中转，规避 MySQL "不能在 INSERT 的子查询中引用目标表" 的限制
    SELECT COALESCE(MAX(revision_no), 0) + 1 INTO v_next
      FROM risk_case_revision WHERE risk_case_id = OLD.id;

    INSERT INTO risk_case_revision
      (risk_case_id, revision_no, material_version_id, risk_text, risk_level, confidence,
       suggestion, recommended_copy, required_evidence, reason)
    VALUES
      (OLD.id, v_next, OLD.current_version_id, OLD.risk_text, OLD.risk_level, OLD.confidence,
       OLD.suggestion, OLD.recommended_copy, OLD.required_evidence, '内容变更自动快照');
  END IF;
END//

DELIMITER ;
