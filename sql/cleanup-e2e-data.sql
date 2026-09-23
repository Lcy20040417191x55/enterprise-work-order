-- =====================================================================
--  清理自动化测试产生的数据，回到"干净演示库"状态。
--
--  这些 id 是 e2e 脚本自己打印出来的（ui-smoke / round2 / round3 / round4），
--  不是猜的。测试脚本刻意不自己清库 —— 脚本运行在有 Playwright 的环境里，
--  让它直连数据库意味着把库口令写进前端仓库，代价远大于手工清一次。
-- =====================================================================

USE enterprise_work_order;

DELETE FROM ticket_op_log;
DELETE FROM ticket_comment;
DELETE FROM ticket_attachment;
DELETE FROM notification;
DELETE FROM approval_record;
DELETE FROM ticket;

-- 单号序列也要清：留着今天的 4 会让下一张单从 0005 开始，
-- 而库里其实一张单都没有，看起来像丢数据
DELETE FROM ticket_no_seq;