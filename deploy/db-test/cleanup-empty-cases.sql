-- 联调清理：把开发过程中自动创建的空任务（无物料、无风险）归档。
-- 不删除任何记录：review_record 为 append-only，历史必须保留；
-- 归档只改变任务状态，任务仍可按 id 查到。
INSERT INTO review_record (case_id, actor_type, actor_id, action, opinion, from_status, to_status, payload)
SELECT id, 'HUMAN', 1, 'CASE_STATUS_CHANGE', '联调产生的空任务，归档清理（无物料、无风险）',
       status, 'ARCHIVED',
       JSON_OBJECT('event', 'DEV_CLEANUP_ARCHIVE',
                   'reason', 'empty draft created during local integration')
  FROM audit_case
 WHERE id BETWEEN 3 AND 12 AND material_count = 0 AND status = 'DRAFT';

UPDATE audit_case SET status = 'ARCHIVED'
 WHERE id BETWEEN 3 AND 12 AND material_count = 0 AND status = 'DRAFT';

SELECT COUNT(*) AS archived_now FROM audit_case WHERE status = 'ARCHIVED';
