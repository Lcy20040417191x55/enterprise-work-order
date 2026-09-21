package com.enterprise.workorder.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.InputStream;

/**
 * 下载一个附件所需的全部信息。
 *
 * <p><b>为什么要有个对象把流和元数据包在一起</b>：给 HTTP 响应写文件需要三样东西 ——
 * 数据流、文件名（进 Content-Disposition）、字节数（进 Content-Length）。
 * 让 Controller 分别去调三个方法，就会有"流已经打开了、取名字时又抛了异常"这种
 * 半开状态，而 {@link InputStream} 不关就泄漏文件句柄。打包成一个对象后，
 * "拿到它就说明三样都在手上"，Controller 只剩下 write 和 close 两件事。</p>
 *
 * <p><b>流由调用方负责关闭</b>：本类不实现 AutoCloseable，是刻意的 ——
 * 它只是"搬运工"，谁打开响应谁负责收尾，避免两处都以为对方会关。</p>
 */
@Data
public class AttachmentDownload {

    private String fileName;

    /**
     * 写回给浏览器的 MIME 类型。
     *
     * <p>这里统一用 {@code application/octet-stream}（见 Service 实现），
     * 而不是数据库里那个由客户端上报的 contentType。理由是：若原样回显
     * {@code text/html}，浏览器会尝试把附件当页面渲染并执行其中的脚本；
     * 强制 octet-stream 则一律触发"下载"行为，不会进入渲染流程。
     * 这是 Content-Disposition 之外的又一道保险。</p>
     */
    private String contentType;

    private Long fileSize;

    @Schema(description = "文件内容流，调用方负责关闭")
    private InputStream inputStream;
}
