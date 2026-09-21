-- =====================================================================
--  第 3 轮变更：工单附件
--  执行: mysql -uroot -p enterprise_work_order < sql/migration-20260921-04.sql
--  幂等：重复执行不会报错
-- =====================================================================

USE enterprise_work_order;

-- ---------------------------------------------------------------------
-- 工单附件表
--
-- 【为什么存 stored_path 而不是绝对路径、也不存文件内容】
--   1. 不存 BLOB：附件是 1MB~20MB 级别的大对象，塞进 ticket 所在的库会让
--      备份体积、主从同步延迟、Buffer Pool 命中率一起恶化。文件系统的
--      大文件读写本身就是为这种负载设计的。
--   2. 不存绝对路径：绝对路径一旦入库就有了环境绑定。开发机是 D:\...，
--      服务器是 /opt/...，换一次环境就得全表 UPDATE 一遍；备份恢复到
--      另一台机器后，所有附件的路径全是错的。存"相对于存储根目录的路径"，
--      根目录由配置给出，数据本身与部署位置无关。
--
-- 【为什么 original_name 与 stored_path 是两列】
--   磁盘上的文件名由服务端生成（UUID），绝不采用用户上传时的文件名。
--   用户给的名字是不可信输入，可能含 ../ 用来写到仓库外，也可能是
--   "CON"、"NUL" 这类 Windows 保留名，还可能在两个用户之间撞名覆盖。
--   原文件名只作为展示与下载时回填用，存在这里而不是拿来当路径。
--
-- 【为什么不做外键】
--   与项目其它表一致：工单是逻辑删除，附件行也要能保留审计痕迹。
--   引用完整性放在 Service 层保证。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ticket_attachment (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    ticket_id     BIGINT       NOT NULL                COMMENT '所属工单ID',
    uploader_id   BIGINT       NOT NULL                COMMENT '上传人ID',
    uploader_name VARCHAR(64)  NOT NULL                COMMENT '上传人姓名快照',
    original_name VARCHAR(255) NOT NULL                COMMENT '上传时的原始文件名（仅展示与下载回填用）',
    stored_path   VARCHAR(255) NOT NULL                COMMENT '相对存储根目录的路径，如 2026/09/21/9f3c....pdf',
    content_type  VARCHAR(128) NULL                    COMMENT '浏览器上报的 MIME 类型（不可信，仅用于展示图标）',
    file_size     BIGINT       NOT NULL                COMMENT '字节数',
    deleted       TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除 0否1是',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),

    -- 详情页固定按 ticket_id 过滤、按 id 正序排，复合索引直接覆盖两者。
    -- 与 ticket_comment 同构：这类"挂在主单下的小表"查询形状完全一致。
    KEY idx_ticket (ticket_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '工单附件';
