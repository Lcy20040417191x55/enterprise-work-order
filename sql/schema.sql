-- =====================================================================
--  企业工单与审批系统 —— 数据库初始化脚本
--  数据库: enterprise_work_order
--  字符集: utf8mb4 / utf8mb4_general_ci
--  执行: mysql -uroot -p < sql/schema.sql
-- =====================================================================

CREATE DATABASE IF NOT EXISTS enterprise_work_order
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_general_ci;

USE enterprise_work_order;

-- 按外键依赖倒序清理，便于重复执行
DROP TABLE IF EXISTS approval_record;
DROP TABLE IF EXISTS ticket_comment;
DROP TABLE IF EXISTS notification;
DROP TABLE IF EXISTS ticket;
DROP TABLE IF EXISTS ticket_type;
DROP TABLE IF EXISTS sys_user;
DROP TABLE IF EXISTS department;

-- ---------------------------------------------------------------------
-- 部门表
-- ---------------------------------------------------------------------
CREATE TABLE department (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    name        VARCHAR(64)  NOT NULL                COMMENT '部门名称',
    parent_id   BIGINT       NOT NULL DEFAULT 0      COMMENT '上级部门ID，0为顶级',
    leader_id   BIGINT       NULL                    COMMENT '部门主管用户ID',
    sort        INT          NOT NULL DEFAULT 0      COMMENT '排序号',
    deleted     TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除 0否1是',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_parent (parent_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '部门';

-- ---------------------------------------------------------------------
-- 用户表
--   role_code: EMPLOYEE 普通员工 / APPROVER 审批人 / ADMIN 管理员
-- ---------------------------------------------------------------------
CREATE TABLE sys_user (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    username      VARCHAR(64)  NOT NULL                COMMENT '登录名',
    password      VARCHAR(100) NOT NULL                COMMENT 'BCrypt 加密后的密码',
    real_name     VARCHAR(64)  NOT NULL                COMMENT '姓名',
    email         VARCHAR(128) NULL                    COMMENT '邮箱',
    phone         VARCHAR(32)  NULL                    COMMENT '手机号',
    department_id BIGINT       NULL                    COMMENT '所属部门ID',
    role_code     VARCHAR(32)  NOT NULL DEFAULT 'EMPLOYEE' COMMENT '角色编码',
    status        TINYINT      NOT NULL DEFAULT 1      COMMENT '状态 1启用0禁用',
    deleted       TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除 0否1是',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username),
    KEY idx_department (department_id),
    KEY idx_role (role_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户';

-- ---------------------------------------------------------------------
-- 工单类型表
--   approval_flow: 审批链，逗号分隔的角色编码，决定需要几级审批、每级谁来审
--                  例如 'DEPT_LEADER,ADMIN' 表示先部门主管、再管理员
-- ---------------------------------------------------------------------
CREATE TABLE ticket_type (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    code          VARCHAR(32)  NOT NULL                COMMENT '类型编码',
    name          VARCHAR(64)  NOT NULL                COMMENT '类型名称',
    description   VARCHAR(255) NULL                    COMMENT '描述',
    approval_flow VARCHAR(255) NOT NULL DEFAULT 'DEPT_LEADER' COMMENT '审批链角色，逗号分隔',
    sort          INT          NOT NULL DEFAULT 0      COMMENT '排序号',
    enabled       TINYINT      NOT NULL DEFAULT 1      COMMENT '是否启用 1是0否',
    deleted       TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除 0否1是',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    -- 只约束"未删除"的编码。若写成 UNIQUE KEY uk_code (code)，被逻辑删除的行
    -- （deleted=1）依然占着这个编码，管理员就无法再用同一个 code 新建类型 ——
    -- 而 MyBatis-Plus 的逻辑删除在应用层看起来那行"已经不存在了"。
    -- 函数索引在 deleted=1 时取 NULL，而唯一索引不约束 NULL，两边语义因此对齐。
    -- 详见 sql/migration-20260920-01.sql 的说明。
    UNIQUE KEY uk_code_active ((IF(deleted = 0, code, NULL)))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '工单类型';

-- ---------------------------------------------------------------------
-- 工单表（聚合根）
--   status: DRAFT 草稿 / PENDING 审批中 / APPROVED 已通过 / REJECTED 已驳回
--           WITHDRAWN 已撤回 / CLOSED 已归档
-- ---------------------------------------------------------------------
CREATE TABLE ticket (
    id                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    ticket_no           VARCHAR(32)  NOT NULL                COMMENT '工单编号',
    title               VARCHAR(128) NOT NULL                COMMENT '标题',
    content             TEXT         NULL                    COMMENT '内容',
    type_id             BIGINT       NOT NULL                COMMENT '工单类型ID',
    priority            VARCHAR(16)  NOT NULL DEFAULT 'NORMAL' COMMENT '优先级 LOW/NORMAL/HIGH/URGENT',
    status              VARCHAR(16)  NOT NULL DEFAULT 'DRAFT'  COMMENT '状态',
    creator_id          BIGINT       NOT NULL                COMMENT '创建人ID',
    department_id       BIGINT       NULL                    COMMENT '创建人部门ID',
    current_step        INT          NOT NULL DEFAULT 0      COMMENT '当前审批级次，从1开始',
    total_step          INT          NOT NULL DEFAULT 0      COMMENT '审批总级次',
    current_approver_id BIGINT       NULL                    COMMENT '当前待审批人ID',
    submitted_at        DATETIME     NULL                    COMMENT '提交时间',
    finished_at         DATETIME     NULL                    COMMENT '办结时间',
    deleted             TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除 0否1是',
    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_ticket_no (ticket_no),
    KEY idx_creator (creator_id),
    KEY idx_status (status),
    KEY idx_approver (current_approver_id, status),
    KEY idx_type (type_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '工单';

-- ---------------------------------------------------------------------
-- 单号序列表
--   ticket.ticket_no 需要唯一且按天连续。原实现是 SELECT MAX(ticket_no)+1，
--   它有个致命前提：读最大值与写入必须在同一瞬间完成。
--   实际上"读最大值"发生在事务提交之前，并发请求会读到同一份快照、算出同一个序号，
--   第二个插入撞上 uk_ticket_no 直接失败 —— 实测 20 并发只有 3 个成功。
--   改为在本表上做原子自增：INSERT ... ON DUPLICATE KEY UPDATE 会对该行加排他锁
--   并持有到事务提交，并发请求被串行化，各自拿到不同的序号。
--   多实例部署同样安全（锁在数据库，不在 JVM）。
-- ---------------------------------------------------------------------
CREATE TABLE ticket_no_seq (
    seq_date    DATE    NOT NULL                COMMENT '日期',
    current_val INT     NOT NULL DEFAULT 0      COMMENT '当天已发放的最大序号',
    PRIMARY KEY (seq_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '工单单号序列，每天一行';

-- 用已有工单回填，避免升级后从 1 开始而撞上历史单号。
-- ticket_no 形如 TK20260920-0041：第 3 位起 8 位是日期，第 12 位起是序号。
INSERT INTO ticket_no_seq (seq_date, current_val)
SELECT STR_TO_DATE(SUBSTRING(ticket_no, 3, 8), '%Y%m%d')                 AS d,
       MAX(CAST(SUBSTRING(ticket_no, 12) AS UNSIGNED))                   AS v
FROM ticket
WHERE ticket_no LIKE 'TK________-%'
GROUP BY d
ON DUPLICATE KEY UPDATE current_val = GREATEST(current_val, VALUES(current_val));

-- ---------------------------------------------------------------------
-- 审批记录表（只追加，不修改）
--   action: SUBMIT 提交 / APPROVE 通过 / REJECT 驳回 / WITHDRAW 撤回 / CANCEL 作废
-- ---------------------------------------------------------------------
CREATE TABLE approval_record (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    ticket_id     BIGINT       NOT NULL                COMMENT '工单ID',
    step          INT          NOT NULL                COMMENT '审批级次',
    approver_id   BIGINT       NULL                    COMMENT '操作人ID',
    approver_name VARCHAR(64)  NULL                    COMMENT '操作人姓名快照',
    action        VARCHAR(16)  NOT NULL                COMMENT '动作',
    comment       VARCHAR(500) NULL                    COMMENT '审批意见',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_ticket (ticket_id, step),
    -- scope=done（"我审过的"）要按操作人过滤审批记录。
    -- 没有这个索引时该查询是全表扫描：EXPLAIN 显示 type=ALL、possible_keys=NULL。
    -- idx_ticket 的首列是 ticket_id，帮不上按 approver_id 过滤的忙。
    -- 带上 action 是因为过滤条件里同时限定 action IN ('APPROVE','REJECT')，
    -- 复合索引可以直接覆盖这两列，不必回表判断。
    KEY idx_approver (approver_id, action)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '审批记录';

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

-- =====================================================================
--  初始化数据
-- =====================================================================

INSERT INTO department (id, name, parent_id, sort) VALUES
    (1, '总公司',   0, 1),
    (2, '技术部',   1, 1),
    (3, '人事部',   1, 2),
    (4, '财务部',   1, 3);

-- 明文密码均为 123456，下列 BCrypt 哈希已用 BCryptPasswordEncoder.matches() 实测通过
INSERT INTO sys_user (id, username, password, real_name, email, department_id, role_code) VALUES
    (1, 'admin',    '$2a$10$oLJEVK/DTEsAXtC31a4xEunvdDb2LJdKQ4GXskoQBMxy.tgJvsLmC', '系统管理员', 'admin@enterprise.com',   1, 'ADMIN'),
    (2, 'zhangsan', '$2a$10$XaEWVgisOOGEjyra9avEwOJR4Oz84/D17Ovw/KEghdJ564wFFRDpC', '张三',       'zhangsan@enterprise.com', 2, 'EMPLOYEE'),
    (3, 'lisi',     '$2a$10$qDOu6DtVTELFYc2FzZrhsutYkzsPbTcDLdmmjWAAmhcU9vWVoIVLa', '李四',       'lisi@enterprise.com',     2, 'APPROVER'),
    (4, 'wangwu',   '$2a$10$.8AZ1IidJ.IzVL.N.aRDueRxUDGqSBIQubKftbWhSM79WJ/5sKMBK', '王五',       'wangwu@enterprise.com',   3, 'APPROVER');

UPDATE department SET leader_id = 3 WHERE id = 2;
UPDATE department SET leader_id = 4 WHERE id = 3;

INSERT INTO ticket_type (id, code, name, description, approval_flow, sort) VALUES
    (1, 'LEAVE',    '请假申请', '员工请假审批',       'DEPT_LEADER,ADMIN', 1),
    (2, 'EXPENSE',  '费用报销', '差旅、办公费用报销', 'DEPT_LEADER,ADMIN', 2),
    (3, 'IT_REPAIR','IT报修',   '办公设备故障报修',   'DEPT_LEADER',       3),
    (4, 'PURCHASE', '采购申请', '办公用品及设备采购', 'DEPT_LEADER,ADMIN', 4);