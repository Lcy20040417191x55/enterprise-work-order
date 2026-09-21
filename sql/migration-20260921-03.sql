-- =====================================================================
--  迁移脚本 20260921-03
--  适用对象：已执行过 20260920-01 / -02 的环境。
--  全新部署无需执行，直接用最新的 schema.sql 即可。
--
--  主题：第 2 轮功能的数据层 —— 站内通知、工单评论。
-- =====================================================================

USE enterprise_work_order;

-- ---------------------------------------------------------------------
-- 站内通知表
--
-- 设计取舍：通知采用"写扩散"（一发生就为每个接收人插一行），而不是
-- "读时计算"（用户打开通知中心时现场比对工单状态）。
-- 原因：读时计算无法回答"我什么时候看过了"——已读状态必须落库；
-- 而且每读一次都要扫全量工单，用户越多越慢。写扩散的代价是通知行数增长快，
-- 但通知是典型的"热数据"：超过一段时间就没人看，可以按 created_at 归档/清理。
--
-- biz_id 语义随 type 变化，目前都指向工单ID，点击后跳详情页。
-- 不做外键约束，理由与项目其它表一致：工单可逻辑删除，外键会让"保留审计数据"
-- 与"删除业务数据"互相打架。引用完整性放到 Service 层保证。
-- ---------------------------------------------------------------------
CREATE TABLE notification (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id     BIGINT       NOT NULL                COMMENT '接收人ID',
    type        VARCHAR(32)  NOT NULL                COMMENT '通知类型',
    title       VARCHAR(128) NOT NULL                COMMENT '标题',
    content     VARCHAR(500) NULL                    COMMENT '正文摘要',
    biz_id      BIGINT       NULL                    COMMENT '关联业务ID，通常是工单ID',
    is_read     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读 0未读1已读',
    read_at     DATETIME     NULL                    COMMENT '读取时间',
    deleted     TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除 0否1是',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),

    -- 通知中心的主查询是"我的通知，按时间倒序"，过滤列 user_id 必须打头。
    -- 把 is_read 放进索引第二列，是因为未读数的 COUNT 查询也走这一条：
    -- WHERE user_id = ? AND is_read = 0 AND deleted = 0。
    -- 若不建这个索引，用户越多、每个人查未读数越慢，而顶栏角标是
    -- 每次进页面都要问一次的接口，它慢会拖慢整个首屏。
    KEY idx_user_read (user_id, is_read, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '站内通知';

-- ---------------------------------------------------------------------
-- 工单评论表
--
-- 与 approval_record 的区别：审批记录是流程动作产生的、不可修改、不可删除，
-- 代表"制度"；评论是人与人之间的讨论，可以讨论到一半补充、可以撤回自己刚发错的内容，
-- 代表"沟通"。两者混在一张表里的话，前者需要的不可变性就没法保证。
-- ---------------------------------------------------------------------
CREATE TABLE ticket_comment (
    id          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    ticket_id   BIGINT        NOT NULL                COMMENT '工单ID',
    user_id     BIGINT        NOT NULL                COMMENT '评论人ID',
    user_name   VARCHAR(64)   NOT NULL                COMMENT '评论人姓名快照',
    content     VARCHAR(1000) NOT NULL                COMMENT '评论内容',
    deleted     TINYINT       NOT NULL DEFAULT 0      COMMENT '逻辑删除 0否1是',
    created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),

    -- 详情页固定按 ticket_id 过滤、按 id 正序排序，复合索引直接覆盖两者。
    KEY idx_ticket (ticket_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '工单评论';
