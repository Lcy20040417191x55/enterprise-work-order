package com.enterprise.workorder.attachment;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 附件相关配置，对应 application.yml 中的 workorder.attachment.*
 *
 * <p><b>为什么这些值必须可配置而不是写死在代码里</b>：
 * 存储根目录换环境就得变（开发机 Windows、服务器 Linux）；文件大小上限取决于
 * 部署方的磁盘与带宽；允许的扩展名更是每家企业规定都不同 —— 财务要传发票 PDF，
 * 研发可能还想传日志压缩包。写死的结果是每次调整都要改代码、重新打包、重新发布。</p>
 *
 * <p><b>DataSize 的用法</b>：Spring Boot 对这个类型做宽松绑定，
 * 配置里写 {@code 20MB}、{@code 20mb}、{@code 20480KB} 都能解析成同一个值，
 * 比让运维自己换算字节数靠谱得多。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "workorder.attachment")
public class AttachmentProperties {

    /**
     * 附件存储根目录。可以是相对路径（相对进程工作目录）或绝对路径。
     *
     * <p>默认值刻意放在项目目录下的 data/，而不是系统临时目录：临时目录可能被
     * 操作系统定期清理，文件会无声无息地消失而数据库记录还在 ——
     * 用户点下载得到 404，却查不出任何原因。</p>
     */
    private String storageRoot = "data/attachments";

    /**
     * 单个文件的业务上限。
     *
     * <p><b>这个值与 spring.servlet.multipart.max-file-size 是两个不同的关口</b>，
     * 且刻意让 Spring 那边的值更大一点，原因见 application.yml 的注释：
     * 只有留在业务层判断，才能给出"你传了 24.3MB，上限 20MB"这样带具体数字的提示。</p>
     */
    private DataSize maxSize = DataSize.ofMegabytes(20);

    /**
     * 允许上传的扩展名（不含点）。
     *
     * <p><b>用白名单而不是黑名单</b>：黑名单永远列不全 —— 今天想到 exe、bat，
     * 明天冒出 chm、lnk、scf，漏一个就是一个洞。白名单的失效方式是"用户抱怨传不了
     * 某类文件"，是可发现的；黑名单的失效方式是"某类恶意文件被放过"，是不可发现的。</p>
     *
     * <p>默认清单里刻意不含 html / svg / js：这三类即使只作为附件下载，
     * 浏览器也可能把它们渲染成页面并执行其中的脚本。</p>
     */
    private List<String> allowedExtensions = List.of(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "txt", "csv", "png", "jpg", "jpeg", "gif", "bmp",
            "zip", "rar", "7z");

    /** 归一化成小写集合，避免配置里写成 PDF、Pdf 时校验失败 */
    public Set<String> extensionSet() {
        return allowedExtensions.stream()
                .filter(e -> e != null && !e.isBlank())
                .map(e -> e.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }

    /** 上限的字节数，业务层比较用 */
    public long maxSizeBytes() {
        return maxSize.toBytes();
    }
}
