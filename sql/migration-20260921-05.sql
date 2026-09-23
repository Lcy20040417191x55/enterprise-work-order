-- =====================================================================
--  迁移脚本 20260921-05
--  适用对象：已执行过 20260920-01 / -02、20260921-03 / -04 的环境。
--  全新部署无需执行，直接用最新的 schema.sql 即可。
--
--  主题：第 5 轮功能的数据层 —— 工单操作日志（审计轨迹）与催办。
--  幂等：重复执行不会报错。
-- =====================================================================

USE enterprise_work_order;

-- ---------------------------------------------------------------------
-- 工单操作日志表
--
-- 【为什么已经有 approval_record 了还要这张表】
--   approval_record 是"审批链"的权威数据：它带 step（第几级），
--   而可见性判定（hasApprovalRecord）和级次计算都依赖这个语义。
--   把"评论""传附件""催办"塞进那张表，会立刻产生两个后果：
--     1. step 对一个评论而言没有意义，只能填 0，于是"step=0 表示提交"这条
--        隐含约定被打破，任何按 step 过滤的统计都会开始算错；
--     2. approval_record 参与"谁有权看这张单"的判定 —— 往里插一条评论，
--        等于把评论人提升成了"历史审批人"，凭空获得查看权。
--   所以两张表的分工是：approval_record 给程序算状态，ticket_op_log 给人读历史。
--   审批动作会同时写入两边，但那是刻意的冗余：一份供机器判定，一份供人复盘。
--
-- 【为什么没有 deleted / updated_at】
--   与 approval_record 同理，这是只追加不修改的审计表。
--   审计记录一旦可改可删，就不再是审计记录了。
--
-- 【为什么 operator_id 允许为 NULL】
--   目前所有操作都有真人操作。但审计表要能容纳"没有人"的动作 ——
--   例如将来加定时任务自动关闭超期工单，那时没有操作人，
--   把 operator_id 设成 0 或 -1 是在伪造一个不存在的用户，
--   让 NULL 表示"系统"，语义上最诚实。
--
-- 【为什么 detail 存文本而不是存结构化参数】
--   这里存的是"给人看的一句话"（附件名、评论摘要、催办给谁）。
--   它不参与任何计算，只参与展示，因此没必要为了"可解析"牺牲可读性 ——
--   真正需要结构化查询的东西（谁、什么时候、什么动作）已经单独成列了。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ticket_op_log (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    ticket_id     BIGINT       NOT NULL                COMMENT '所属工单ID',
    operator_id   BIGINT       NULL                    COMMENT '操作人ID，NULL 表示系统动作',
    operator_name VARCHAR(64)  NOT NULL                COMMENT '操作人姓名快照',
    action        VARCHAR(32)  NOT NULL                COMMENT '操作类型，取值见 TicketOpAction',
    detail        VARCHAR(500) NULL                    COMMENT '人读的补充说明',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发生时间',
    PRIMARY KEY (id),

    -- 详情页固定按 ticket_id 过滤、按 id 正序排序，复合索引直接覆盖两者。
    -- id 参与索引的第二个原因是催办要查"这张单最近一次催办是什么时候"，
    -- 那条查询是 WHERE ticket_id = ? AND action = 'URGE'，也走这个前缀。
    KEY idx_ticket (ticket_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '工单操作日志';