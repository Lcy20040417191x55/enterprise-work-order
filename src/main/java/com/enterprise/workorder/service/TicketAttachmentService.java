package com.enterprise.workorder.service;

import com.enterprise.workorder.dto.AttachmentDownload;
import com.enterprise.workorder.dto.AttachmentVO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 工单附件服务。
 *
 * <p><b>权限模型</b>（与评论有意不同，值得对照着看）：</p>
 * <ul>
 *   <li><b>看</b>：能看工单详情的人都能看附件，复用 {@code TicketService} 的可见性规则。</li>
 *   <li><b>传</b>：工单尚未办结时，创建人与当前待办人都可以传。双方都可能需要补材料 ——
 *       申请人补一张发票扫描件，审批人回传一份签署版。</li>
 *   <li><b>删</b>：只有上传者本人。理由与评论一致 —— 附件是"当时看到的是什么"的凭证，
 *       让别人能替你删，争议发生时就没有中立记录了。</li>
 * </ul>
 *
 * <p><b>办结后冻结</b>：APPROVED / CLOSED 状态的工单不能增删附件。审批已经结束，
 * 若还能往里塞东西，事后复盘时"审批人当时看到的是不是这份材料"就无从确认了。</p>
 */
public interface TicketAttachmentService {

    /** 附件列表，按上传时间正序（与讨论区一致，读起来是顺着发生的顺序） */
    List<AttachmentVO> list(Long ticketId);

    /**
     * 上传附件。
     *
     * <p>校验顺序是"先便宜后昂贵"：大小 -> 扩展名 -> 文件头魔数 -> 落盘。
     * 每道不过就直接抛错，不会产生任何磁盘写入。</p>
     */
    AttachmentVO upload(Long ticketId, MultipartFile file);

    /** 下载。返回的流由调用方关闭 */
    AttachmentDownload download(Long ticketId, Long attachmentId);

    /** 删除附件（逻辑删除记录 + 物理删除文件）。仅上传者本人可删 */
    void delete(Long ticketId, Long attachmentId);
}
