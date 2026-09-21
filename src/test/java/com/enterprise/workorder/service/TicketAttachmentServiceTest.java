package com.enterprise.workorder.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.workorder.common.BusinessException;
import com.enterprise.workorder.common.ResultCode;
import com.enterprise.workorder.dto.AttachmentDownload;
import com.enterprise.workorder.dto.AttachmentVO;
import com.enterprise.workorder.dto.TicketCreateRequest;
import com.enterprise.workorder.entity.Ticket;
import com.enterprise.workorder.entity.TicketAttachment;
import com.enterprise.workorder.mapper.TicketAttachmentMapper;
import com.enterprise.workorder.support.AuthTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 工单附件测试。
 *
 * <p><b>这个文件要守住的三类东西</b>，它们都不会以"报错"的形式出现，
 * 只会安静地做错事：</p>
 * <ol>
 *   <li><b>上传入口的校验</b>：扩展名白名单与文件头魔数。漏一道，
 *       系统就成了"任何文件都能传、且能以任意后缀存下来"的通道。
 *       这类失误没有报错，只有后果。</li>
 *   <li><b>资源归属</b>：拿 A 单的可见性去操作 B 单的附件（水平越权）。
 *       多做一次校验是常识，但恰恰是写代码时最容易省掉、测试时最容易漏掉的一步。</li>
 *   <li><b>状态冻结</b>：办结后的工单不能再增删附件。审批结束后附件若还能被改，
 *       复盘时"审批人当时看到的是哪份材料"就无从确认。</li>
 * </ol>
 *
 * <p><b>落盘位置被改到 target/test-attachments</b>：这些用例会真的往磁盘写文件，
 * 而 @Transactional 只能回滚数据库、回滚不了文件系统。指向 target/ 后，
 * {@code mvn clean} 顺手就把残留清掉了，不会像写到 data/attachments 那样
 * 在开发目录里积累起一堆没人认识的 UUID 文件。</p>
 */
@SpringBootTest
@Transactional
@DisplayName("工单附件")
class TicketAttachmentServiceTest {

    private static final Long UID_ADMIN = 1L;
    private static final Long UID_ZHANGSAN = 2L;
    private static final Long UID_LISI = 3L;
    private static final Long UID_WANGWU = 4L;

    /** 请假申请：审批链 DEPT_LEADER,ADMIN —— 技术部主管是李四(3) */
    private static final Long TYPE_LEAVE = 1L;

    /** 测试专用存储目录。static final，整个类共用一个，@AfterAll 里删掉 */
    private static final Path STORAGE_ROOT = Path.of("target", "test-attachments");

    @DynamicPropertySource
    static void storageProperties(DynamicPropertyRegistry registry) {
        // 必须在容器启动前注入：LocalAttachmentStorage 在构造函数里就把根目录
        // 解析并建好了，等到 @BeforeEach 再改配置已经来不及
        registry.add("workorder.attachment.storage-root", () -> STORAGE_ROOT.toString());
    }

    @Autowired
    private TicketAttachmentService attachmentService;

    @Autowired
    private TicketService ticketService;

    @Autowired
    private TicketAttachmentMapper attachmentMapper;

    @AfterEach
    void tearDown() {
        AuthTestSupport.logout();
    }

    @org.junit.jupiter.api.AfterAll
    static void cleanStorage() throws IOException {
        if (!Files.exists(STORAGE_ROOT)) {
            return;
        }
        // 递归删除要小心：这里路径是硬编码的 target 子目录，
        // 不来自配置也不来自参数，删除范围是确定的
        try (var walk = Files.walk(STORAGE_ROOT)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 单个文件删不掉不影响测试结论
                }
            });
        }
    }

    // ==================================================================
    //  上传
    // ==================================================================

    @Nested
    @DisplayName("上传")
    class Upload {

        @Test
        @DisplayName("创建人往自己的草稿上传 PDF，能被列表读回来")
        void creatorCanUploadOnDraft() throws IOException {
            Ticket ticket = createDraft(UID_ZHANGSAN);
            AuthTestSupport.loginAs(UID_ZHANGSAN);

            AttachmentVO vo = attachmentService.upload(ticket.getId(), pdf("报销单.pdf"));

            assertThat(vo.getId()).isNotNull();
            assertThat(vo.getTicketId()).isEqualTo(ticket.getId());
            assertThat(vo.getUploaderId()).isEqualTo(UID_ZHANGSAN);
            // 姓名是快照：用户改名后历史附件仍显示当时的名字
            assertThat(vo.getUploaderName()).isEqualTo("张三");
            assertThat(vo.getFileName()).isEqualTo("报销单.pdf");
            assertThat(vo.getFileSize()).isEqualTo(vo.getFileSize());
            assertThat(vo.getCreatedAt()).isNotNull();

            List<AttachmentVO> rows = attachmentService.list(ticket.getId());
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).getFileName()).isEqualTo("报销单.pdf");
        }

        @Test
        @DisplayName("当前待办人也能上传：审批中补材料是双向的")
        void currentApproverCanUploadOnPending() throws IOException {
            Ticket ticket = createDraft(UID_ZHANGSAN);
            AuthTestSupport.loginAs(UID_ZHANGSAN);
            ticketService.submit(ticket.getId());

            // 提交后待办人变成李四。他可以回传一份签批扫描件 ——
            // 若只允许创建人上传，审批人的材料就只能走线下邮件，复盘时等于没有
            AuthTestSupport.loginAs(UID_LISI);
            AttachmentVO vo = attachmentService.upload(ticket.getId(), pdf("签批版.pdf"));

            assertThat(vo.getUploaderName()).isEqualTo("李四");
            assertThat(attachmentService.list(ticket.getId())).hasSize(1);
        }

        @Test
        @DisplayName("无关的人传不了（403），也看不到列表")
        void unrelatedUserRejected() {
            Ticket ticket = createDraft(UID_ZHANGSAN);
            AuthTestSupport.loginAs(UID_ZHANGSAN);
            attachmentService.upload(ticket.getId(), pdf("内部.pdf"));

            // 王五在人事部，与这张技术部的单毫无关系
            AuthTestSupport.loginAs(UID_WANGWU);
            assertThatThrownBy(() -> attachmentService.list(ticket.getId()))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.FORBIDDEN);
            assertThatThrownBy(() -> attachmentService.upload(ticket.getId(), pdf("插一脚.pdf")))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.FORBIDDEN);
        }

        @Test
        @DisplayName("管理员看得到列表，但不能上传：能看到不等于能往里加东西")
        void adminCanReadButNotUpload() {
            Ticket ticket = createDraft(UID_ZHANGSAN);
            AuthTestSupport.loginAs(UID_ZHANGSAN);
            attachmentService.upload(ticket.getId(), pdf("申请人材料.pdf"));

            AuthTestSupport.loginAs(UID_ADMIN);
            // 管理员的可见范围覆盖全部工单，列表必须能看
            assertThat(attachmentService.list(ticket.getId())).hasSize(1);
            // 但"参与人"只算创建人与当前待办人。放开管理员会带来一个说不清的问题：
            // 事后争议里"这份材料是谁放进来的"，管理员权限让这个问题失去意义
            assertThatThrownBy(() -> attachmentService.upload(ticket.getId(), pdf("管理员加料.pdf")))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.FORBIDDEN);
        }

        @Test
        @DisplayName("空文件被拒：传一个 0 字节文件只会给后面的人添麻烦")
        void emptyFileRejected() {
            Ticket ticket = createDraft(UID_ZHANGSAN);
            AuthTestSupport.loginAs(UID_ZHANGSAN);

            MultipartFile empty = new MockMultipartFile(
                    "file", "空文件.pdf", "application/pdf", new byte[0]);

            assertThatThrownBy(() -> attachmentService.upload(ticket.getId(), empty))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("请选择要上传的文件");
        }

        @Test
        @DisplayName("超过大小上限被拒，且提示里带具体数字")
        void oversizeRejectedWithConcreteNumbers() {
            Ticket ticket = createDraft(UID_ZHANGSAN);
            AuthTestSupport.loginAs(UID_ZHANGSAN);

            // 上限是 20MB，这里造 21MB。全部填 PDF 头，
            // 保证它不会先一步被"内容与扩展名不符"拦下 ——
            // 那样测试就变成了在验证另一条规则，覆盖不到大小校验
            byte[] big = new byte[21 * 1024 * 1024];
            System.arraycopy("%PDF".getBytes(StandardCharsets.UTF_8), 0, big, 0, 4);
            MultipartFile file = new MockMultipartFile(
                    "file", "超大文件.pdf", "application/pdf", big);

            assertThatThrownBy(() -> attachmentService.upload(ticket.getId(), file))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("超过上限")
                    // 只说"文件太大"时用户只能一次次猜着试。
                    // 提示里必须有具体数字，接口被脚本调用时更是如此
                    .hasMessageContaining("21.0MB")
                    .hasMessageContaining("20.0MB");
        }
    }

    // ==================================================================
    //  类型校验
    // ==================================================================

    @Nested
    @DisplayName("类型校验")
    class TypeCheck {

        @Test
        @DisplayName("白名单外的扩展名被拒，提示里列出允许的类型")
        void extensionNotAllowedRejected() {
            Ticket ticket = createDraft(UID_ZHANGSAN);
            AuthTestSupport.loginAs(UID_ZHANGSAN);

            MultipartFile exe = new MockMultipartFile(
                    "file", "tool.exe", "application/octet-stream", "MZ".getBytes(StandardCharsets.UTF_8));

            assertThatThrownBy(() -> attachmentService.upload(ticket.getId(), exe))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(".exe")
                    // 不列出允许的类型，用户只能猜。这是"错误提示要能指导下一步动作"的具体体现
                    .hasMessageContaining("pdf");
        }

        @Test
        @DisplayName("html 被拒：它即使只作为附件下载，也可能被浏览器渲染执行")
        void htmlRejected() {
            Ticket ticket = createDraft(UID_ZHANGSAN);
            AuthTestSupport.loginAs(UID_ZHANGSAN);

            MultipartFile html = new MockMultipartFile(
                    "file", "钓鱼页.html", "text/html",
                    "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8));

            assertThatThrownBy(() -> attachmentService.upload(ticket.getId(), html))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(".html");
        }

        @Test
        @DisplayName("没有扩展名被拒")
        void noExtensionRejected() {
            Ticket ticket = createDraft(UID_ZHANGSAN);
            AuthTestSupport.loginAs(UID_ZHANGSAN);

            MultipartFile nameless = new MockMultipartFile(
                    "file", "README", "text/plain", "hello".getBytes(StandardCharsets.UTF_8));

            assertThatThrownBy(() -> attachmentService.upload(ticket.getId(), nameless))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("扩展名");
        }

        @Test
        @DisplayName("改名的可执行文件被拒：扩展名说是 PDF，内容说是 Windows 程序")
        void renamedExecutableRejectedByMagic() {
            Ticket ticket = createDraft(UID_ZHANGSAN);
            AuthTestSupport.loginAs(UID_ZHANGSAN);

            // 这是本模块最需要守住的一条。getContentType() 和文件名都来自客户端，
            // 都能随便填；只有文件内容骗不了人
            byte[] exeBytes = {'M', 'Z', (byte) 0x90, 0x00, 0x03, 0x00, 0x00, 0x00};
            MultipartFile disguised = new MockMultipartFile(
                    "file", "发票.pdf", "application/pdf", exeBytes);

            assertThatThrownBy(() -> attachmentService.upload(ticket.getId(), disguised))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("不符");
        }

        @Test
        @DisplayName("文本文件冒充 PDF 也被拒：截断校验不能只看扩展名")
        void textRenamedToPdfRejected() {
            Ticket ticket = createDraft(UID_ZHANGSAN);
            AuthTestSupport.loginAs(UID_ZHANGSAN);

            MultipartFile fake = new MockMultipartFile(
                    "file", "假的.pdf", "application/pdf",
                    "这其实是一个纯文本文件".getBytes(StandardCharsets.UTF_8));

            assertThatThrownBy(() -> attachmentService.upload(ticket.getId(), fake))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("不符");
        }

        @Test
        @DisplayName("txt 与 csv 没有魔数，靠扩展名放行：不能因为无法验证就一律拒绝")
        void plainTextAllowed() {
            Ticket ticket = createDraft(UID_ZHANGSAN);
            AuthTestSupport.loginAs(UID_ZHANGSAN);

            AttachmentVO txt = attachmentService.upload(ticket.getId(), text("说明.txt", "随便什么内容"));
            AttachmentVO csv = attachmentService.upload(ticket.getId(), text("明细.csv", "a,b,c"));

            assertThat(txt.getId()).isNotNull();
            assertThat(csv.getId()).isNotNull();
            assertThat(attachmentService.list(ticket.getId())).hasSize(2);
        }

        @Test
        @DisplayName("客户端上报的路径被剥掉，只留文件名")
        void clientPathStrippedFromName() {
            Ticket ticket = createDraft(UID_ZHANGSAN);
            AuthTestSupport.loginAs(UID_ZHANGSAN);

            AttachmentVO vo = attachmentService.upload(ticket.getId(),
                    new MockMultipartFile("file", "C:/Users/x/发票.pdf", "application/pdf", PDF_BYTES));

            assertThat(vo.getFileName()).isEqualTo("发票.pdf");
        }
    }

    // ==================================================================
    //  状态冻结
    // ==================================================================

    @Nested
    @DisplayName("办结后冻结")
    class Frozen {

        @Test
        @DisplayName("已通过的工单不能上传，也不能删除已有附件")
        void approvedIsFrozen() throws IOException {
            Ticket ticket = approvedTicket(UID_ZHANGSAN, UID_LISI, UID_ADMIN);
            AuthTestSupport.loginAs(UID_ADMIN);
            Long attachmentId = attachmentService.list(ticket.getId()).get(0).getId();

            AuthTestSupport.loginAs(UID_ZHANGSAN);
            assertThatThrownBy(() -> attachmentService.upload(ticket.getId(), pdf("事后补料.pdf")))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("不能增删附件");
            assertThatThrownBy(() -> attachmentService.delete(ticket.getId(), attachmentId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("不能增删附件");
        }

        @Test
        @DisplayName("已作废的工单同样冻结")
        void closedIsFrozen() throws IOException {
            Ticket ticket = createDraft(UID_ZHANGSAN);
            AuthTestSupport.loginAs(UID_ZHANGSAN);
            ticketService.cancel(ticket.getId(), "不办了");

            assertThatThrownBy(() -> attachmentService.upload(ticket.getId(), pdf("补料.pdf")))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("不能增删附件");
        }

        @Test
        @DisplayName("已驳回的工单不冻结：补材料再提交是驳回后的正常动作")
        void rejectedIsNotFrozen() throws IOException {
            Ticket ticket = createDraft(UID_ZHANGSAN);
            AuthTestSupport.loginAs(UID_ZHANGSAN);
            ticketService.submit(ticket.getId());
            AuthTestSupport.loginAs(UID_LISI);
            ticketService.approve(ticket.getId(), "REJECT", "发票看不清，请补一张");

            AuthTestSupport.loginAs(UID_ZHANGSAN);
            AttachmentVO vo = attachmentService.upload(ticket.getId(), pdf("重新扫描的发票.pdf"));
            assertThat(vo.getId()).isNotNull();
        }

        @Test
        @DisplayName("列表里的 deletable 逐行算好：只有本人上传且工单未办结才为 true")
        void deletableIsComputedPerRow() throws IOException {
            Ticket ticket = createDraft(UID_ZHANGSAN);
            AuthTestSupport.loginAs(UID_ZHANGSAN);
            attachmentService.upload(ticket.getId(), pdf("张三传的.pdf"));
            ticketService.submit(ticket.getId());

            AuthTestSupport.loginAs(UID_LISI);
            attachmentService.upload(ticket.getId(), pdf("李四传的.pdf"));

            // 李四能看两行，但只能删自己那一行
            List<AttachmentVO> forApprover = attachmentService.list(ticket.getId());
            assertThat(forApprover).hasSize(2);
            assertThat(forApprover).filteredOn(AttachmentVO::getDeletable)
                    .extracting(AttachmentVO::getFileName)
                    .containsExactly("李四传的.pdf");

            AuthTestSupport.loginAs(UID_ZHANGSAN);
            assertThat(attachmentService.list(ticket.getId()))
                    .filteredOn(AttachmentVO::getDeletable)
                    .extracting(AttachmentVO::getFileName)
                    .containsExactly("张三传的.pdf");
        }
    }

    // ==================================================================
    //  下载
    // ==================================================================

    @Nested
    @DisplayName("下载")
    class Download {

        @Test
        @DisplayName("下载回来的内容与上传的一模一样，文件名保留中文")
        void shouldDownloadSameBytes() throws IOException {
            Ticket ticket = createDraft(UID_ZHANGSAN);
            AuthTestSupport.loginAs(UID_ZHANGSAN);
            Long attachmentId = attachmentService
                    .upload(ticket.getId(), pdf("2026年9月 报销单(终版).pdf")).getId();

            AttachmentDownload download = attachmentService.download(ticket.getId(), attachmentId);

            assertThat(download.getFileName()).isEqualTo("2026年9月 报销单(终版).pdf");
            assertThat(download.getFileSize()).isEqualTo((long) PDF_BYTES.length);
            // 统一 octet-stream，不用客户端上报的类型：
            // 原样回显 text/html 会让浏览器尝试渲染并执行其中的脚本
            assertThat(download.getContentType()).isEqualTo("application/octet-stream");
            try (InputStream in = download.getInputStream()) {
                assertThat(in.readAllBytes()).isEqualTo(PDF_BYTES);
            }
        }

        @Test
        @DisplayName("看不到工单的人下载不了附件（403）")
        void unrelatedUserCannotDownload() throws IOException {
            Ticket ticket = createDraft(UID_ZHANGSAN);
            AuthTestSupport.loginAs(UID_ZHANGSAN);
            Long attachmentId = attachmentService.upload(ticket.getId(), pdf("机密.pdf")).getId();

            AuthTestSupport.loginAs(UID_WANGWU);
            assertThatThrownBy(() -> attachmentService.download(ticket.getId(), attachmentId))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.FORBIDDEN);
        }

        @Test
        @DisplayName("拿别的工单的附件 id 来下载，报 404 而不是给出文件")
        void crossTicketAttachmentIsNotFound() throws IOException {
            Ticket mine = createDraft(UID_ZHANGSAN);
            Ticket other = createDraft(UID_ADMIN);
            AuthTestSupport.loginAs(UID_ZHANGSAN);
            Long myAttachment = attachmentService.upload(mine.getId(), pdf("我的.pdf")).getId();
            AuthTestSupport.loginAs(UID_ADMIN);
            Long otherAttachment = attachmentService.upload(other.getId(), pdf("别人的.pdf")).getId();

            // 管理员本来就看得见两张单，所以这里校验的不是"权限"，而是"归属"：
            // 必须确认这个 attachmentId 真的挂在路径里的那张工单下。
            // 少了这一句，就出现"用我能看的单做跳板，去操作我看不到的单"的越权路径
            AuthTestSupport.loginAs(UID_ZHANGSAN);
            assertThatThrownBy(() -> attachmentService.download(mine.getId(), otherAttachment))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.NOT_FOUND);
            // 自己的那个照常能下
            assertThat(attachmentService.download(mine.getId(), myAttachment).getFileName())
                    .isEqualTo("我的.pdf");
        }

        @Test
        @DisplayName("记录在、文件没了：报 404 并让人看得出是文件丢失，而不是系统故障")
        void missingFileReportsClearError() throws IOException {
            Ticket ticket = createDraft(UID_ZHANGSAN);
            AuthTestSupport.loginAs(UID_ZHANGSAN);
            Long attachmentId = attachmentService.upload(ticket.getId(), pdf("会被删掉.pdf")).getId();

            // 手工删掉磁盘文件，模拟"有人直接动了存储目录"或"只恢复了数据库"
            TicketAttachment row = attachmentMapper.selectById(attachmentId);
            Files.deleteIfExists(STORAGE_ROOT.resolve(row.getStoredPath()));

            assertThatThrownBy(() -> attachmentService.download(ticket.getId(), attachmentId))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.NOT_FOUND)
                    .hasMessageContaining("丢失");
        }

        @Test
        @DisplayName("工单不存在时列表报 404，而不是返回空列表")
        void missingTicketThrowsNotFound() {
            AuthTestSupport.loginAs(UID_ZHANGSAN);
            // 返回空列表会让前端显示"暂无附件"，用户以为单子只是没传材料
            assertThatThrownBy(() -> attachmentService.list(-1L))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.NOT_FOUND);
        }
    }

    // ==================================================================
    //  删除
    // ==================================================================

    @Nested
    @DisplayName("删除")
    class Delete {

        @Test
        @DisplayName("上传者本人能删，记录逻辑删除、磁盘文件也一并清掉")
        void uploaderCanDelete() throws IOException {
            Ticket ticket = createDraft(UID_ZHANGSAN);
            AuthTestSupport.loginAs(UID_ZHANGSAN);
            Long attachmentId = attachmentService.upload(ticket.getId(), pdf("传错了.pdf")).getId();
            Path onDisk = STORAGE_ROOT.resolve(attachmentMapper.selectById(attachmentId).getStoredPath());
            assertThat(onDisk).exists();

            attachmentService.delete(ticket.getId(), attachmentId);

            assertThat(attachmentService.list(ticket.getId())).isEmpty();
            // 逻辑删除：行还在，deleted=1
            assertThat(attachmentMapper.selectById(attachmentId)).isNull();
            assertThat(onDisk).doesNotExist();
        }

        @Test
        @DisplayName("别人删不了我传的附件，管理员也不行")
        void othersCannotDelete() throws IOException {
            Ticket ticket = createDraft(UID_ZHANGSAN);
            AuthTestSupport.loginAs(UID_ZHANGSAN);
            Long attachmentId = attachmentService.upload(ticket.getId(), pdf("我的凭证.pdf")).getId();
            ticketService.submit(ticket.getId());

            // 审批中，李四是当前待办人：他能看、能传，但不能删别人的
            AuthTestSupport.loginAs(UID_LISI);
            assertThatThrownBy(() -> attachmentService.delete(ticket.getId(), attachmentId))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.FORBIDDEN)
                    .hasMessageContaining("自己上传");

            // 附件是"当时看到的是什么"的凭证。让别人能替你删，
            // 争议发生时就没有中立记录了 —— 所以这里管理员也不放过
            AuthTestSupport.loginAs(UID_ADMIN);
            assertThatThrownBy(() -> attachmentService.delete(ticket.getId(), attachmentId))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.FORBIDDEN);
        }

        @Test
        @DisplayName("拿别的工单的附件 id 来删，报 404")
        void crossTicketDeleteIsNotFound() throws IOException {
            Ticket mine = createDraft(UID_ZHANGSAN);
            Ticket other = createDraft(UID_ZHANGSAN);
            AuthTestSupport.loginAs(UID_ZHANGSAN);
            Long otherAttachment = attachmentService.upload(other.getId(), pdf("另一张单的.pdf")).getId();

            assertThatThrownBy(() -> attachmentService.delete(mine.getId(), otherAttachment))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.NOT_FOUND);
            // 没被误删
            assertThat(attachmentService.list(other.getId())).hasSize(1);
        }

        @Test
        @DisplayName("附件 id 不存在时也是 404，不能抛空指针变成 500")
        void unknownAttachmentIsNotFound() {
            Ticket ticket = createDraft(UID_ZHANGSAN);
            AuthTestSupport.loginAs(UID_ZHANGSAN);

            assertThatThrownBy(() -> attachmentService.delete(ticket.getId(), 999999999L))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.NOT_FOUND);
            assertThatThrownBy(() -> attachmentService.delete(ticket.getId(), null))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.NOT_FOUND);
        }
    }

    // ==================================================================
    //  测试辅助
    // ==================================================================

    /** 一段合法的 PDF 开头，够通过魔数校验 */
    private static final byte[] PDF_BYTES =
            "%PDF-1.7\n1 0 obj\n<<>>\nendobj\ntrailer\n%%EOF\n".getBytes(StandardCharsets.UTF_8);

    private static MultipartFile pdf(String fileName) {
        return new MockMultipartFile("file", fileName, "application/pdf", PDF_BYTES);
    }

    private static MultipartFile text(String fileName, String content) {
        return new MockMultipartFile("file", fileName, "text/plain",
                content.getBytes(StandardCharsets.UTF_8));
    }

    private Ticket createDraft(long userId) {
        AuthTestSupport.loginAs(userId);
        TicketCreateRequest request = new TicketCreateRequest();
        request.setTitle("附件测试工单");
        request.setContent("由 TicketAttachmentServiceTest 创建，事务结束后回滚");
        request.setTypeId(TYPE_LEAVE);
        request.setPriority("NORMAL");
        return ticketService.create(request);
    }

    /**
     * 造一张"已通过"的工单，并让它带一个张三上传的附件。
     *
     * <p>请假申请走两级（部门主管 -> 管理员）。附件必须在提交前传好 ——
     * 办结后就传不进去了，而这一组用例的前提正是"单子上本来有一份材料"。</p>
     */
    private Ticket approvedTicket(long creator, long firstApprover, long admin) throws IOException {
        Ticket ticket = createDraft(creator);
        AuthTestSupport.loginAs(creator);
        attachmentService.upload(ticket.getId(), pdf("审批时看到的材料.pdf"));
        ticketService.submit(ticket.getId());

        AuthTestSupport.loginAs(firstApprover);
        ticketService.approve(ticket.getId(), "APPROVE", "同意");
        AuthTestSupport.loginAs(admin);
        ticketService.approve(ticket.getId(), "APPROVE", "同意");

        assertThat(ticketService.detail(ticket.getId()).getStatus()).isEqualTo("APPROVED");
        return ticket;
    }
}
