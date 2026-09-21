package com.enterprise.workorder.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.workorder.attachment.AttachmentProperties;
import com.enterprise.workorder.attachment.AttachmentStorage;
import com.enterprise.workorder.attachment.FileTypes;
import com.enterprise.workorder.common.BusinessException;
import com.enterprise.workorder.common.ResultCode;
import com.enterprise.workorder.dto.AttachmentDownload;
import com.enterprise.workorder.dto.AttachmentVO;
import com.enterprise.workorder.entity.SysUser;
import com.enterprise.workorder.entity.Ticket;
import com.enterprise.workorder.entity.TicketAttachment;
import com.enterprise.workorder.enums.TicketStatus;
import com.enterprise.workorder.mapper.TicketAttachmentMapper;
import com.enterprise.workorder.security.LoginUser;
import com.enterprise.workorder.security.SecurityUtils;
import com.enterprise.workorder.service.TicketAttachmentService;
import com.enterprise.workorder.service.TicketService;
import com.enterprise.workorder.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;

/**
 * 工单附件实现。
 *
 * <p><b>本类最需要解释的是事务与文件的关系。</b>数据库有事务，文件系统没有 ——
 * "往磁盘写了文件"和"往库里插了记录"是两个独立系统，无法一起提交或回滚。
 * 于是只存在两种一致性策略，必须二选一并明确接受它的代价：</p>
 *
 * <ol>
 *   <li><b>先写文件、再插记录</b>：若插记录失败，磁盘上留下一个没人引用的孤儿文件。
 *       代价是浪费磁盘，好处是用户永远不会看到"列表里有这条附件、点下载却 404"。</li>
 *   <li><b>先插记录、再写文件</b>：若写文件失败，库里留下一条指向不存在文件的记录。
 *       代价是用户看到一个坏掉的附件，而清理它需要人工介入。</li>
 * </ol>
 *
 * <p>本类选 1：孤儿文件是"看不见的浪费"，坏记录是"看得见的故障"。
 * 前者可以靠定期扫描目录、删掉没有对应记录的旧文件来回收；
 * 后者只要发生一次，就得有人去数据库里手工删行。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TicketAttachmentServiceImpl implements TicketAttachmentService {

    private final TicketAttachmentMapper attachmentMapper;
    private final TicketService ticketService;
    private final UserService userService;
    private final AttachmentStorage storage;
    private final AttachmentProperties properties;

    @Override
    public List<AttachmentVO> list(Long ticketId) {
        // 能看详情才能看附件，规则复用 TicketService，不在这里另写一份。
        // 用 getVisibleTicket 而不是 requireVisible：本方法还需要工单状态来算每行的
        // deletable，而 requireVisible 只验权限、不返回工单。若两者都调，
        // 同一张单会被查两遍、权限也判两遍 —— 一次调用同时拿到的写法更省也更难写错。
        Ticket ticket = ticketService.getVisibleTicket(ticketId);

        List<TicketAttachment> rows = attachmentMapper.selectList(
                new LambdaQueryWrapper<TicketAttachment>()
                        .eq(TicketAttachment::getTicketId, ticketId)
                        .orderByAsc(TicketAttachment::getId));
        return rows.stream().map(row -> toVO(row, ticket)).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AttachmentVO upload(Long ticketId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("请选择要上传的文件");
        }
        // requireAttachable 一次性校验：工单可见 + 状态允许增删附件 + 我是参与人
        Ticket ticket = ticketService.requireAttachable(ticketId);
        LoginUser current = SecurityUtils.getLoginUser();

        // ---- 第 1 道：大小。放在最前面，因为它最便宜（不需要读文件内容） ----
        long size = file.getSize();
        long limit = properties.maxSizeBytes();
        if (size > limit) {
            // 提示里带上具体数字。只说"文件过大"，用户只能一次次猜着试，
            // 而当接口是被脚本调用时，这句话更是完全无法行动。
            throw new BusinessException("文件大小 " + humanSize(size)
                    + " 超过上限 " + humanSize(limit));
        }

        String originalName = FileTypes.sanitize(file.getOriginalFilename());
        String extension = FileTypes.extensionOf(originalName);

        // ---- 第 2 道：扩展名白名单 ----
        if (extension.isEmpty()) {
            throw new BusinessException("文件没有扩展名，无法识别类型，请重命名后再上传");
        }
        if (!properties.extensionSet().contains(extension)) {
            throw new BusinessException("不支持的文件类型 ." + extension
                    + "，允许上传：" + String.join("、", properties.getAllowedExtensions()));
        }

        // ---- 第 3 道：文件头魔数。唯一能验证"这真的是 PDF"的手段 ----
        if (FileTypes.hasMagic(extension)) {
            byte[] head = readHead(file);
            if (!FileTypes.matchesMagic(extension, head)) {
                // 到这里说明扩展名与真实内容对不上。可能是用户手工改了后缀，
                // 也可能是在试图绕过校验 —— 两种情况的处理方式相同：拒绝。
                throw new BusinessException("文件内容与扩展名 ." + extension
                        + " 不符，请确认文件是否被改过后缀名");
            }
        }

        // ---- 落盘。文件名由服务端生成，不含任何用户输入 ----
        String relativePath = storage.newRelativePath(extension);
        try (InputStream in = file.getInputStream()) {
            storage.store(relativePath, in);
        } catch (IOException e) {
            // 磁盘满、无写权限这类问题必须打印堆栈：它通常不是单个请求的问题，
            // 而是整个部署环境的问题，值得让运维立刻看到
            log.error("附件写入磁盘失败: path={}", relativePath, e);
            throw new BusinessException("附件保存失败，请稍后重试或联系管理员");
        }

        TicketAttachment attachment = new TicketAttachment();
        attachment.setTicketId(ticketId);
        attachment.setUploaderId(current.getUserId());
        attachment.setUploaderName(displayName(current));
        attachment.setOriginalName(originalName);
        attachment.setStoredPath(relativePath);
        attachment.setContentType(file.getContentType());
        attachment.setFileSize(size);
        attachmentMapper.insert(attachment);

        log.info("工单 {} 上传附件 {} ({}), stored={}",
                ticket.getTicketNo(), originalName, humanSize(size), relativePath);
        return toVO(attachment, ticket);
    }

    @Override
    public AttachmentDownload download(Long ticketId, Long attachmentId) {
        ticketService.requireVisible(ticketId);
        TicketAttachment attachment = requireAttachmentInTicket(ticketId, attachmentId);

        InputStream in;
        try {
            in = storage.load(attachment.getStoredPath());
        } catch (IOException e) {
            // 记录在、文件没了。这种不一致必须记 error 级别的日志：
            // 它意味着有人直接删了磁盘文件、或者恢复备份时只恢复了数据库。
            log.error("附件记录存在但文件缺失: id={} path={}", attachment.getId(), attachment.getStoredPath());
            throw new BusinessException(ResultCode.NOT_FOUND, "附件文件已丢失，请联系管理员");
        }

        AttachmentDownload result = new AttachmentDownload();
        result.setFileName(attachment.getOriginalName());
        // 统一声明为二进制流，不用库里那个客户端上报的类型 ——
        // 具体原因见 AttachmentDownload#contentType
        result.setContentType("application/octet-stream");
        result.setFileSize(attachment.getFileSize());
        result.setInputStream(in);
        return result;
    }

    /**
     * 删除附件。
     *
     * <p><b>顺序是先删记录还是先删文件</b>：先删数据库记录。若先删文件而删除记录时失败，
     * 用户会看到一个点开报 404 的附件，且刷新页面它还在；先删记录则最坏情况是磁盘上多一个
     * 孤儿文件，而用户视角下这件事已经干净地结束了。这与 {@link #upload} 的取舍方向一致 ——
     * 一律偏向"用户看到的是完整的"。</p>
     *
     * <p><b>但事务回滚会带来一个反直觉的后果</b>：记录是逻辑删除、在事务里；文件是立即删除、
     * 无法回滚。若本方法在删文件之后还有别的操作并失败了，记录会回滚成"未删除"而文件已经没了。
     * 因此这里刻意把删文件放在方法的最后一步，之后不再有任何可能抛异常的代码。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long ticketId, Long attachmentId) {
        Ticket ticket = ticketService.requireAttachable(ticketId);
        TicketAttachment attachment = requireAttachmentInTicket(ticketId, attachmentId);

        LoginUser current = SecurityUtils.getLoginUser();
        if (!attachment.getUploaderId().equals(current.getUserId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "只能删除自己上传的附件");
        }

        attachmentMapper.deleteById(attachment.getId());
        log.info("工单 {} 的附件 {} 已删除 by {}", ticket.getTicketNo(),
                attachment.getOriginalName(), current.getUsername());

        // 最后一步：删磁盘文件。它不抛异常（失败只记日志），因此不会让上面的逻辑删除回滚
        storage.delete(attachment.getStoredPath());
    }

    // ==================================================================
    //  内部
    // ==================================================================

    /**
     * 取附件并确认它确实属于这张工单。
     *
     * <p><b>两个条件缺一不可。</b>若只用 id 查询，就出现了一条越权路径：
     * 拿 A 单的可见性判定，去操作 B 单的附件 —— 而 B 单可能是我根本看不到的。
     * 这类"资源归属校验"是水平越权的经典成因，写的时候少一行、测的时候很难想到。</p>
     */
    private TicketAttachment requireAttachmentInTicket(Long ticketId, Long attachmentId) {
        TicketAttachment attachment = attachmentId == null ? null : attachmentMapper.selectById(attachmentId);
        if (attachment == null || !ticketId.equals(attachment.getTicketId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "附件不存在");
        }
        return attachment;
    }

    /** 读文件开头的若干字节用于魔数比对。只读头部，不把整个文件读进内存 */
    private byte[] readHead(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            return in.readNBytes(FileTypes.HEAD_BYTES);
        } catch (IOException e) {
            log.warn("读取附件文件头失败: {}", file.getOriginalFilename(), e);
            throw new BusinessException("附件读取失败，请重新上传");
        }
    }

    /** 取显示名。用户被删/查不到时回落到登录名，不让快照字段变成空值 */
    private String displayName(LoginUser current) {
        SysUser user = userService.getById(current.getUserId());
        if (user != null && user.getRealName() != null && !user.getRealName().isBlank()) {
            return user.getRealName();
        }
        return current.getUsername();
    }

    private AttachmentVO toVO(TicketAttachment attachment, Ticket ticket) {
        AttachmentVO vo = new AttachmentVO();
        vo.setId(attachment.getId());
        vo.setTicketId(attachment.getTicketId());
        vo.setUploaderId(attachment.getUploaderId());
        vo.setUploaderName(attachment.getUploaderName());
        vo.setFileName(attachment.getOriginalName());
        vo.setContentType(attachment.getContentType());
        vo.setFileSize(attachment.getFileSize());
        vo.setCreatedAt(attachment.getCreatedAt());

        // 前端只用来决定是否显示删除按钮；真正的权限在 delete 里再校验一次。
        // 两个条件：本人上传 + 工单还没办结（办结后附件冻结）
        LoginUser current = SecurityUtils.getLoginUserOrNull();
        boolean isUploader = current != null && attachment.getUploaderId().equals(current.getUserId());
        vo.setDeletable(isUploader && isAttachable(ticket));
        return vo;
    }

    private boolean isAttachable(Ticket ticket) {
        TicketStatus status = parseStatusOrNull(ticket.getStatus());
        return status != null && status.isAttachable();
    }

    /** 宽松解析：脏数据只让按钮不可用，不让整个列表接口 500 */
    private TicketStatus parseStatusOrNull(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return TicketStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 把字节数说成人话，用于错误提示与日志 */
    private String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + "B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1fKB", bytes / 1024.0);
        }
        return String.format("%.1fMB", bytes / 1024.0 / 1024.0);
    }
}
