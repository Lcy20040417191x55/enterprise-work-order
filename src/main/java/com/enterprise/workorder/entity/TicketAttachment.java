package com.enterprise.workorder.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 工单附件。
 *
 * <p><b>本表只存元数据，文件本身在磁盘上。</b>两者的对应关系是
 * {@link #storedPath}，它是一条<b>相对于存储根目录</b>的路径。
 * 不存绝对路径的原因见 sql/schema.sql 中建表注释。</p>
 *
 * <p><b>originalName 与 storedPath 的分工，是这里最容易被写错的一处</b>：
 * 上传来的是"用户说的文件名"，落盘时用的是"服务端生成的文件名"。
 * 让后者去用前者，就等于把 {@code ../../} 写文件的能力交给了 HTTP 请求方。
 * 因此 originalName 只承担两个职责：列表里给人看、下载时回填。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ticket_attachment")
public class TicketAttachment extends BaseEntity {

    private Long ticketId;

    private Long uploaderId;

    /** 上传人姓名快照。与 approval_record.approver_name 同理：用户改名后历史记录仍显示当时的名字 */
    private String uploaderName;

    /** 上传时的原始文件名。可能含中文、空格、括号，但绝不允许含路径分隔符（落库前已清洗） */
    private String originalName;

    /** 相对存储根目录的路径，形如 2026/09/21/9f3c8b....pdf。服务端生成，不含任何用户输入 */
    private String storedPath;

    /**
     * 浏览器上报的 MIME 类型。
     *
     * <p><b>这一列不可信</b>：它由客户端随意填写，把 exe 改成 application/pdf
     * 只是一行代码的事。真正的类型判断靠"扩展名白名单 + 文件头魔数比对"，
     * 本列只用于在前端选个图标，不参与任何安全判断。</p>
     */
    private String contentType;

    private Long fileSize;
}
