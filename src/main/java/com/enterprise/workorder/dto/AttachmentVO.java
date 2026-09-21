package com.enterprise.workorder.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工单附件展示对象。
 *
 * <p><b>注意这里刻意没有 storedPath 字段。</b>它现在不需要给前端用，
 * 将来大概也不需要；而一旦它出现在 JSON 里，就等于把服务器的目录结构
 * 公布给了每一个能看工单的人 —— 攻击者据此可以推断部署路径、找到其它服务的落盘位置。
 * VO 与实体的差别正是为了这种"实体有、前端不该有"的字段而存在的。</p>
 */
@Data
public class AttachmentVO {

    private Long id;

    private Long ticketId;

    private Long uploaderId;

    private String uploaderName;

    /** 原始文件名，展示与下载都用它 */
    private String fileName;

    /** 浏览器上报的 MIME 类型，仅供前端选图标；判断文件类型时不可信任它 */
    private String contentType;

    private Long fileSize;

    private LocalDateTime createdAt;

    @Schema(description = "当前登录人是否可以删除这个附件（本人上传 + 工单尚未办结）")
    private Boolean deletable;
}
