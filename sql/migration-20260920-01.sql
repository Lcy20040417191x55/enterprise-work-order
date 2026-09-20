-- =====================================================================
--  迁移脚本 20260920-01
--  适用对象：已经按旧版 schema.sql 建过库的环境（开发库 enterprise_work_order）
--  全新部署无需执行本脚本，直接用最新的 schema.sql 即可。
--
--  可用以下语句检查是否已执行过，避免重复跑：
--    SHOW TABLES LIKE 'ticket_no_seq';
--    SHOW INDEX FROM ticket_type WHERE Key_name = 'uk_code_active';
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1) 单号序列表
--    修复：并发创建工单时，多个请求用 SELECT MAX(ticket_no)+1 算出同一个序号，
--          插入撞唯一索引报错，接口 500。实测 20 并发只有 3 个成功。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ticket_no_seq (
    seq_date    DATE    NOT NULL                COMMENT '日期',
    current_val INT     NOT NULL DEFAULT 0      COMMENT '当天已发放的最大序号',
    PRIMARY KEY (seq_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '工单单号序列，每天一行';

-- 用已有工单回填，避免升级后序号从 1 开始而撞上历史单号。
-- ticket_no 形如 TK20260920-0041：第 3 位起 8 位为日期，第 12 位起为序号。
INSERT INTO ticket_no_seq (seq_date, current_val)
SELECT STR_TO_DATE(SUBSTRING(ticket_no, 3, 8), '%Y%m%d') AS d,
       MAX(CAST(SUBSTRING(ticket_no, 12) AS UNSIGNED))   AS v
FROM ticket
WHERE ticket_no LIKE 'TK________-%'
GROUP BY d
ON DUPLICATE KEY UPDATE current_val = GREATEST(current_val, VALUES(current_val));

-- ---------------------------------------------------------------------
-- 2) 工单类型编码的唯一索引：改为"只约束未删除的行"
--    修复：类型被逻辑删除后，delete 只把 deleted 置 1，那一行仍占用 uk_code，
--          于是管理员重新建一个同名编码的类型会 500。
--          实测：建类型 -> 删除 -> 用同一 code 重建 -> HTTP 500。
--
--    原索引 uk_code(code) 有两个问题：
--      a. 它约束的是"历史上出现过的所有编码"，而业务上要约束的是"当前在用的编码"；
--      b. MyBatis-Plus 的逻辑删除对数据库是透明的 —— 库只看到一行 deleted=1 的记录，
--         并不知道这在应用层"已经不存在了"。两边对"唯一"的理解不一致。
--
--    新索引是函数索引：deleted=0 时取 code 本身，deleted=1 时取 NULL。
--    MySQL 的唯一索引不约束 NULL（可以有任意多个 NULL），于是一条 code 最多只有一个
--    未删除行，而已删除的行可以任意堆积。约束范围第一次与应用层语义对齐。
--
--    注意 ticket 表的 uk_ticket_no 不适用这个改法：工单号要求"永不重复"，
--    连已删除的单据也不能复用单号，那里保留原来的索引是正确的。
-- ---------------------------------------------------------------------
ALTER TABLE ticket_type DROP INDEX uk_code;
ALTER TABLE ticket_type ADD UNIQUE KEY uk_code_active ((IF(deleted = 0, code, NULL)));
