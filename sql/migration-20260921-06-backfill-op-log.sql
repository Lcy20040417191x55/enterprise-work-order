-- =====================================================================
--  存量工单的操作日志回填
--
--  背景：ticket_op_log 是本轮才加的表，在它之前创建/审批过的工单
--  没有任何操作日志。若不回填，详情页的"操作动态"对这些老单永远是空的 ——
--  而同一页上"审批轨迹"却显示着三条通过记录，用户会以为功能坏了。
--
--  回填依据只有两处现成数据：ticket 自己（创建时间、创建人）
--  和 approval_record（审批链上的每一步）。评论、附件的历史无法还原
--  （那时还没记日志），这部分只能从本轮起才有 —— 这是诚实的缺失，
--  比用 created_at 编造一条日志要好。
--
--  幂等：只给"一条日志都没有"的工单回填，重复执行不会翻倍。
-- =====================================================================

USE enterprise_work_order;

-- ---------------------------------------------------------------------
-- 1. 创建动作：每张非删除工单一条
--
--    用 INSERT ... SELECT 一次插完，而不是在应用里循环 —— 回填是一次性动作，
--    放在 SQL 里跑完就走，不会长期占着一段没人再执行的 Java 代码。
-- ---------------------------------------------------------------------
INSERT INTO ticket_op_log (ticket_id, operator_id, operator_name, action, detail, created_at)
SELECT t.id,
       t.creator_id,
       COALESCE(u.real_name, u.username, '系统'),
       'CREATE',
       '新建草稿',
       t.created_at
FROM ticket t
         LEFT JOIN sys_user u ON u.id = t.creator_id
WHERE t.deleted = 0
  -- 只回填完全没有日志的工单：已经在本轮产生过日志的单子不动，
  -- 否则每跑一次这个脚本，它的时间线上就会多出一条重复的"创建工单"
  AND NOT EXISTS (SELECT 1 FROM ticket_op_log l WHERE l.ticket_id = t.id);

-- ---------------------------------------------------------------------
-- 2. 审批链上的动作：直接搬 approval_record
--
--    没有用 JOIN 一次插完，而是分成 SUBMIT 与"其他"两句，原因是 detail 的取法不同：
--    SUBMIT 没有审批意见，而 APPROVE/REJECT 的 comment 必须带进日志。
--
--    幂等判断是"这张单 + 这个动作 + 这个时间点"是否已经存在，而不是笼统的
--    "这张单有没有日志"。第一版写成后者，结果第一段刚插完创建动作，
--    后两段就被自己的"已有日志"判断全部挡下 —— 五张单只回填出五条创建记录，
--    审批轨迹却是空的。幂等条件的粒度必须与要插入的行一一对应。
-- ---------------------------------------------------------------------
INSERT INTO ticket_op_log (ticket_id, operator_id, operator_name, action, detail, created_at)
SELECT r.ticket_id, r.approver_id, r.approver_name, 'SUBMIT', NULL, r.created_at
FROM approval_record r
         JOIN ticket t ON t.id = r.ticket_id
WHERE r.action = 'SUBMIT'
  AND t.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM ticket_op_log l
                  WHERE l.ticket_id = t.id AND l.action = 'SUBMIT' AND l.created_at = r.created_at)
ORDER BY r.id;
-- 按 approval_record.id 排序插入：id 是审批链的真实发生顺序，
-- 用它来决定日志行的先后，时间线才不会出现"驳回排在提交前面"。
-- （同一秒内的多条记录靠 created_at 分不出先后，这正是 history 接口
--   一直按 id 排序、而不是按时间排序的原因。）

INSERT INTO ticket_op_log (ticket_id, operator_id, operator_name, action, detail, created_at)
SELECT r.ticket_id, r.approver_id, r.approver_name, r.action, r.comment, r.created_at
FROM approval_record r
         JOIN ticket t ON t.id = r.ticket_id
WHERE r.action <> 'SUBMIT'
  AND t.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM ticket_op_log l
                  WHERE l.ticket_id = t.id AND l.action = r.action AND l.created_at = r.created_at)
ORDER BY r.id;