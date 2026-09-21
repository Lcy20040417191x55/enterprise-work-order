package com.enterprise.workorder.controller;

import com.enterprise.workorder.common.Result;
import com.enterprise.workorder.dto.AttachmentDownload;
import com.enterprise.workorder.dto.AttachmentVO;
import com.enterprise.workorder.service.TicketAttachmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 工单附件接口。
 *
 * <p>路径挂在 {@code /api/tickets/{ticketId}/attachments} 之下，与评论接口同构 ——
 * 附件从属于工单，url 里带上 ticketId 后，权限校验的入口自然就有了工单上下文，
 * 不必再按附件 id 反查"它属于哪张单"。</p>
 *
 * <p><b>本类不做任何权限判断</b>：能不能看、能不能传、能不能删全部由 Service 决定。
 * Controller 里写 if 判断权限的坏处是同一个规则会有 N 份，而 N 份迟早不一致。</p>
 */
@Tag(name = "工单附件", description = "工单下的文件上传、下载与删除")
@RestController
@RequestMapping("/api/tickets/{ticketId}/attachments")
@RequiredArgsConstructor
public class TicketAttachmentController {

    private final TicketAttachmentService attachmentService;

    @Operation(summary = "附件列表", description = "按上传顺序正序；能看工单详情的人都能看附件")
    @GetMapping
    public Result<List<AttachmentVO>> list(@PathVariable Long ticketId) {
        return Result.success(attachmentService.list(ticketId));
    }

    /**
     * 上传附件。
     *
     * <p><b>为什么不用 @RequestBody 而用 @RequestParam("file")</b>：
     * 文件上传是 {@code multipart/form-data}，每个表单项都不是 JSON 值，
     * 用 @RequestBody 会直接 415。参数名固定为 file，让前端 Element Plus 的
     * el-upload 用默认字段名就能传上来，不需要额外约定。</p>
     *
     * <p><b>为什么不写 consumes = MULTIPART_FORM_DATA_VALUE</b>：写了之后，
     * 若某个客户端漏设 Content-Type，会得到 415 而不是我们那句
     * "请选择要上传的文件"。少一层技术性错误、多一句人话，对调用方更友好。</p>
     */
    @Operation(summary = "上传附件",
            description = "大小上限与允许的扩展名见 application.yml 的 workorder.attachment.*；"
                    + "已办结（已通过/已作废）的工单不能上传")
    @PostMapping
    public Result<AttachmentVO> upload(@PathVariable Long ticketId,
                                       @RequestParam("file") MultipartFile file) {
        return Result.success("附件已上传", attachmentService.upload(ticketId, file));
    }

    /**
     * 下载附件。
     *
     * <p><b>为什么不映射成静态资源</b>：本项目已配置 {@code add-mappings: false}，
     * 静态资源映射整体关闭；更重要的是，静态资源是"任何人知道 URL 就能下载"，
     * 而工单附件必须校验"你能不能看这张单"。所以下载只能走接口，由 Service 验权后再写流。</p>
     *
     * <p><b>为什么手动写 response 而不返回 ResponseEntity&lt;Resource&gt;</b>：
     * 与导出接口保持一致。Resource 方案要先把流包成 ByteArrayResource 才能拿到长度，
     * 相当于把整个文件读进内存；而这里直接把 {@link InputStream} 拷进响应流，
     * 20MB 的文件也只用几 KB 缓冲。</p>
     */
    @Operation(summary = "下载附件", description = "返回文件流，前端需以 blob 方式接收")
    @GetMapping("/{attachmentId}/download")
    public void download(@PathVariable Long ticketId,
                         @PathVariable Long attachmentId,
                         HttpServletResponse response) throws IOException {
        AttachmentDownload download = attachmentService.download(ticketId, attachmentId);

        // 统一 octet-stream：不用库里那个客户端上报的 Content-Type。
        // 若原样回显 text/html，浏览器会尝试把它当页面渲染并执行其中的脚本 ——
        // 附件是"下载下来看"的东西，不该进入渲染流程。
        response.setContentType("application/octet-stream");
        if (download.getFileSize() != null) {
            // 设了 Content-Length，浏览器才能显示下载进度条；不设则只能显示"未知大小"
            response.setContentLengthLong(download.getFileSize());
        }

        // 中文文件名必须 URL 编码，否则 Tomcat 按 ISO-8859-1 写出响应头，用户看到乱码。
        // filename* 是 RFC 5987 的写法（带编码声明），现代浏览器优先读它；
        // filename 保留一个编码后的版本给老客户端兜底。
        // 注意 replace("+", "%20")：URLEncoder 会把空格编成 +，而 + 在 URL 里
        // 只在查询串中表示空格，放在响应头里会被原样当成加号。
        String encoded = URLEncoder.encode(download.getFileName(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + encoded + "\"; filename*=UTF-8''" + encoded);
        // 附件内容可能被删除或更新，不允许中间层缓存
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");

        // try-with-resources 关的是文件流；响应流不关 —— 它归容器管，
        // 在这里关掉会让后续的 flush 失败
        try (InputStream in = download.getInputStream()) {
            OutputStream out = response.getOutputStream();
            in.transferTo(out);
            out.flush();
        }
    }

    @Operation(summary = "删除附件", description = "只能删除自己上传的附件；已办结的工单不能删除")
    @DeleteMapping("/{attachmentId}")
    public Result<Void> delete(@PathVariable Long ticketId,
                               @PathVariable Long attachmentId) {
        attachmentService.delete(ticketId, attachmentId);
        return Result.success("附件已删除", null);
    }
}
