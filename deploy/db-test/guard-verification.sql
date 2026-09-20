-- ============================================================================
-- 数据库守卫实证脚本
--
-- 目的：证明"前端按钮、后端接口、数据库权限三处一致"中的**数据库那一层真的生效**，
--       而不是文档里的一句承诺。对应 02-状态机与权限矩阵.md §10 的 T3/T4/T7/T12/T13/T14。
--
-- 用法（在 gw_audit 库上执行，脚本自带清理，可重复运行）：
--   docker exec -i gw-audit-mysql mysql -uroot -proot gw_audit < guard-verification.sql
--
-- 判定：每一项输出 BLOCKED 表示守卫生效（期望），ALLOWED 表示守卫失效（缺陷）。
-- ============================================================================

DROP PROCEDURE IF EXISTS gw_verify_guards;

DELIMITER //
CREATE PROCEDURE gw_verify_guards()
BEGIN
  DECLARE v_uid      BIGINT UNSIGNED;
  DECLARE v_uid2     BIGINT UNSIGNED;
  DECLARE v_rid_legal BIGINT UNSIGNED;
  DECLARE v_rid_brand BIGINT UNSIGNED;
  DECLARE v_pid      BIGINT UNSIGNED;
  DECLARE v_cid      BIGINT UNSIGNED;
  DECLARE v_mid      BIGINT UNSIGNED;
  DECLARE v_vid      BIGINT UNSIGNED;
  DECLARE v_riskid   BIGINT UNSIGNED;
  DECLARE v_rr       BIGINT UNSIGNED;

  -- 结果收集
  DROP TEMPORARY TABLE IF EXISTS gw_guard_result;
  CREATE TEMPORARY TABLE gw_guard_result (
    seq INT, item VARCHAR(120), expect VARCHAR(10), actual VARCHAR(12), verdict VARCHAR(8)
  );

  -- ── 准备基础数据 ─────────────────────────────────────────────────────────
  INSERT INTO sys_user (username, display_name) VALUES ('gw_test_legal', '测试法务');
  SET v_uid = LAST_INSERT_ID();
  INSERT INTO sys_user (username, display_name) VALUES ('gw_test_brand', '测试品牌');
  SET v_uid2 = LAST_INSERT_ID();

  SELECT id INTO v_rid_legal FROM sys_role WHERE code = 'LEGAL';
  SELECT id INTO v_rid_brand FROM sys_role WHERE code = 'BRAND';
  INSERT INTO sys_user_role (user_id, role_id) VALUES (v_uid, v_rid_legal), (v_uid2, v_rid_brand);

  INSERT INTO biz_project (code, name, owner_id) VALUES ('GW_TEST', '守卫验证项目', v_uid);
  SET v_pid = LAST_INSERT_ID();

  INSERT INTO audit_case (case_no, name, project_id, submitter_id, owner_id, status)
    VALUES ('GW-TEST-0001', '守卫验证任务', v_pid, v_uid2, v_uid, 'DRAFT');
  SET v_cid = LAST_INSERT_ID();

  INSERT INTO material (case_id, name, material_type, parse_status)
    VALUES (v_cid, '测试海报.png', 'IMAGE', 'SUCCEEDED');
  SET v_mid = LAST_INSERT_ID();

  INSERT INTO material_version (material_id, version_no, version_label, file_object_key,
                                file_sha256, file_size, mime_type, uploader_id)
    VALUES (v_mid, 1, 'V1', 'test/v1.png', REPEAT('a', 64), 1024, 'image/png', v_uid2);
  SET v_vid = LAST_INSERT_ID();
  UPDATE material SET current_version_id = v_vid WHERE id = v_mid;

  INSERT INTO evidence_anchor (anchor_id, material_version_id, anchor_type, locator, text,
                               source_engine, ordinal)
    VALUES ('A-0001', v_vid, 'TEXT_LINE',
            '{"bbox":{"x":10,"y":10,"w":100,"h":20},"image_width":1000,"image_height":1000,"scale_ratio":1.0}',
            '行业第一', 'qwen-vl-ocr', 1);

  INSERT INTO risk_case (risk_no, case_id, material_id, first_version_id, current_version_id,
                         risk_type, risk_level, confidence, status, risk_text, reason,
                         rule_refs, evidence_refs, unsupported_claims, blocked,
                         dedup_key, model_id, pipeline_version, prompt_version, ruleset_version)
    VALUES ('RK-GW-TEST-01', v_cid, v_mid, v_vid, v_vid,
            'ABSOLUTE_CLAIM', 'HIGH', 0.9000, 'OPEN', '行业第一', '涉嫌绝对化用语',
            '[]', '[]', '[]', 1,
            REPEAT('b', 64), 'qwen3-vl-8b-thinking', '0.1.0', '0.1.0', '0.1.0');
  SET v_riskid = LAST_INSERT_ID();

  INSERT INTO review_record (case_id, risk_case_id, material_version_id, actor_type,
                             ai_model_id, action, opinion)
    VALUES (v_cid, v_riskid, v_vid, 'AI', 'qwen3-vl-8b-thinking', 'AI_INITIAL_REVIEW', '初审完成');
  SET v_rr = LAST_INSERT_ID();

  -- ══ T13：物料版本不可覆盖 ══════════════════════════════════════════════════
  BEGIN
    DECLARE CONTINUE HANDLER FOR SQLEXCEPTION SET @r = 'BLOCKED';
    SET @r = 'ALLOWED';
    UPDATE material_version SET file_sha256 = REPEAT('c', 64) WHERE id = v_vid;
  END;
  INSERT INTO gw_guard_result VALUES (1, 'T13 UPDATE material_version（版本不可覆盖）',
    'BLOCKED', @r, IF(@r='BLOCKED','PASS','FAIL'));

  -- ══ T12：审核记录不可删除 ══════════════════════════════════════════════════
  BEGIN
    DECLARE CONTINUE HANDLER FOR SQLEXCEPTION SET @r = 'BLOCKED';
    SET @r = 'ALLOWED';
    DELETE FROM review_record WHERE id = v_rr;
  END;
  INSERT INTO gw_guard_result VALUES (2, 'T12 DELETE review_record（审计链不可删）',
    'BLOCKED', @r, IF(@r='BLOCKED','PASS','FAIL'));

  -- ══ T3：无签名不得关闭风险（AGENTS.md 第 7 条）══════════════════════════════
  BEGIN
    DECLARE CONTINUE HANDLER FOR SQLEXCEPTION SET @r = 'BLOCKED';
    SET @r = 'ALLOWED';
    -- 先合法推进到 LEGAL_FINAL_REVIEW，再尝试直接关闭
    UPDATE risk_case SET status = 'PENDING_LEGAL_DECISION' WHERE id = v_riskid;
    UPDATE risk_case SET status = 'CONFIRMED' WHERE id = v_riskid;
    UPDATE risk_case SET status = 'AWAITING_REVISION' WHERE id = v_riskid;
    UPDATE risk_case SET status = 'RESUBMITTED' WHERE id = v_riskid;
    UPDATE risk_case SET status = 'AI_REREVIEW' WHERE id = v_riskid;
    UPDATE risk_case SET status = 'LEGAL_FINAL_REVIEW' WHERE id = v_riskid;
    -- 此时没有 legal_signature，关闭必须被拒
    UPDATE risk_case SET status = 'CLOSED' WHERE id = v_riskid;
  END;
  INSERT INTO gw_guard_result VALUES (3, 'T3 无签名 CLOSED（AI 不得关闭风险）',
    'BLOCKED', @r, IF(@r='BLOCKED','PASS','FAIL'));

  -- ══ T4：非法跃迁（终态不可再流转）══════════════════════════════════════════
  INSERT INTO risk_case (risk_no, case_id, material_id, first_version_id, current_version_id,
                         risk_type, risk_level, confidence, status, risk_text, reason,
                         rule_refs, evidence_refs, unsupported_claims, blocked,
                         dedup_key, model_id, pipeline_version, prompt_version, ruleset_version)
    VALUES ('RK-GW-TEST-02', v_cid, v_mid, v_vid, v_vid,
            'PRICE_CLAIM', 'LOW', 0.8000, 'OPEN', '全网最低价', '价格宣传需谨慎',
            '[]', '[]', '[]', 0,
            REPEAT('d', 64), 'qwen3-vl-8b-thinking', '0.1.0', '0.1.0', '0.1.0');
  SET @risk2 = LAST_INSERT_ID();
  BEGIN
    DECLARE CONTINUE HANDLER FOR SQLEXCEPTION SET @r = 'BLOCKED';
    SET @r = 'ALLOWED';
    -- OPEN 直接跳 CLOSED：不在白名单内
    UPDATE risk_case SET status = 'CLOSED' WHERE id = @risk2;
  END;
  INSERT INTO gw_guard_result VALUES (4, 'T4 OPEN → CLOSED（非法跃迁）',
    'BLOCKED', @r, IF(@r='BLOCKED','PASS','FAIL'));

  -- ══ T5：非 LEGAL 用户签名必须被拒（AGENTS.md 第 1 条）════════════════════════
  BEGIN
    DECLARE CONTINUE HANDLER FOR SQLEXCEPTION SET @r = 'BLOCKED';
    SET @r = 'ALLOWED';
    INSERT INTO legal_signature (risk_case_id, signer_id, signer_role, sign_type,
                                 signature_hash)
      VALUES (@risk2, v_uid2, 'BRAND', 'RISK_CLOSE', REPEAT('e', 64));
  END;
  INSERT INTO gw_guard_result VALUES (5, 'T5 BRAND 角色签名（签名人须为法务）',
    'BLOCKED', @r, IF(@r='BLOCKED','PASS','FAIL'));

  -- ══ T7：误判不填理由必须被拒（AGENTS.md 第 5 条）════════════════════════════
  BEGIN
    DECLARE CONTINUE HANDLER FOR SQLEXCEPTION SET @r = 'BLOCKED';
    SET @r = 'ALLOWED';
    INSERT INTO review_record (case_id, risk_case_id, actor_type, actor_id, action, opinion)
      VALUES (v_cid, @risk2, 'HUMAN', v_uid, 'LEGAL_FALSE_POSITIVE', NULL);
  END;
  INSERT INTO gw_guard_result VALUES (6, 'T7 误判不填理由（CHECK 约束）',
    'BLOCKED', @r, IF(@r='BLOCKED','PASS','FAIL'));

  -- ══ 悬空锚点引用必须被拒（复合外键，02 §5.3）═════════════════════════════════
  BEGIN
    DECLARE CONTINUE HANDLER FOR SQLEXCEPTION SET @r = 'BLOCKED';
    SET @r = 'ALLOWED';
    INSERT INTO risk_anchor_ref (risk_case_id, anchor_id, material_version_id, ref_role)
      VALUES (@risk2, 'A-9999', v_vid, 'PRIMARY');
  END;
  INSERT INTO gw_guard_result VALUES (7, '悬空锚点引用（复合外键 fk_rar_anchor）',
    'BLOCKED', @r, IF(@r='BLOCKED','PASS','FAIL'));

  -- ══ V2 版本缺修改说明必须被拒（CHECK 约束，AGENTS.md 第 7 条）═════════════════
  BEGIN
    DECLARE CONTINUE HANDLER FOR SQLEXCEPTION SET @r = 'BLOCKED';
    SET @r = 'ALLOWED';
    INSERT INTO material_version (material_id, version_no, version_label, file_object_key,
                                  file_sha256, file_size, mime_type, uploader_id, upload_reason)
      VALUES (v_mid, 2, 'V2', 'test/v2.png', REPEAT('f', 64), 2048, 'image/png', v_uid2, NULL);
  END;
  INSERT INTO gw_guard_result VALUES (8, 'V2 未填修改说明（CHECK ck_version_reason）',
    'BLOCKED', @r, IF(@r='BLOCKED','PASS','FAIL'));

  -- ══ 风险内容变更自动留痕（AGENTS.md 第 7 条：不得悄悄改写原风险）══════════════
  UPDATE risk_case SET risk_text = '行业第一（已修改）' WHERE id = @risk2;
  SELECT COUNT(*) INTO @snap FROM risk_case_revision WHERE risk_case_id = @risk2;
  INSERT INTO gw_guard_result VALUES (9, '风险内容变更自动快照 risk_case_revision',
    '>=1', CAST(@snap AS CHAR), IF(@snap >= 1,'PASS','FAIL'));

  SELECT seq AS seq, item AS item, expect AS expect, actual AS actual, verdict AS verdict
    FROM gw_guard_result ORDER BY seq;
END//
DELIMITER ;

-- 在事务中执行并回滚：本脚本插入的测试数据不会落库，可安全重复运行。
-- 必须回滚的原因：material_version / review_record / evidence_anchor / legal_signature
-- 都被触发器禁止 UPDATE/DELETE（这正是它们的设计目的），若不回滚，
-- 这些测试数据将永久留在数据库里且无法清理。
START TRANSACTION;
CALL gw_verify_guards();
ROLLBACK;

DROP PROCEDURE gw_verify_guards;
