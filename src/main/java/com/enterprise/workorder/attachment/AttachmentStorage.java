package com.enterprise.workorder.attachment;

import java.io.IOException;
import java.io.InputStream;

/**
 * 附件文件存储。只关心"字节往哪放、从哪取"，不涉及任何业务规则与数据库。
 *
 * <p><b>为什么先抽接口再写实现</b>：现在落在本地磁盘，但真正的生产部署往往要换成
 * 对象存储（OSS / S3 / MinIO），或者多实例时挂 NFS。换成对象存储后，
 * "路径"的含义会变成 key，"存在性检查"会变成一次网络请求 —— 这些差异只有在这一层
 * 才需要面对。业务层永远只说"把这个输入流存到某个相对路径下"，
 * 这样替换存储介质不会牵动附件业务代码。</p>
 *
 * <p><b>接口刻意不暴露 File、Path 这类类型</b>：一旦暴露，业务层就会忍不住去
 * {@code Files.exists()}、{@code file.length()}，于是"本地磁盘"这个假设就漏进了
 * 业务代码，接口也就白抽了。</p>
 */
public interface AttachmentStorage {

    /**
     * 生成一个新的相对存储路径，形如 {@code 2026/09/21/9f3c8b....pdf}。
     *
     * <p><b>为什么带日期目录</b>：所有文件平铺在一个目录下，操作系统的目录项会持续膨胀。
     * ext4 用哈希树后问题不大，但 {@code ls}、备份脚本、SFTP 客户端这类"按目录列文件"
     * 的工具会明显变慢，Windows 的资源管理器更是会直接卡住。按 yyyy/MM/dd 分三级，
     * 每个目录下的文件数就与"当天上传量"同量级。日期同时也是一个天然的生命周期边界，
     * 将来做归档或冷备可以直接按目录搬。</p>
     *
     * <p>文件名用 UUID 而不是原文件名或自增 ID：原文件名不可信（见 {@link FileTypes#sanitize}），
     * 自增 ID 要等数据库插入后才有、而文件必须先落盘才能拿到真实大小。
     * UUID 唯一、无需协调、不含任何用户输入。</p>
     *
     * @param extension 小写扩展名，可为空字符串；非空时会拼到文件名末尾
     */
    String newRelativePath(String extension);

    /**
     * 写入一个文件。
     *
     * @param relativePath {@link #newRelativePath} 生成，或数据库中已有的 storedPath
     * @return 实际写入的字节数
     * @throws IOException 磁盘写失败、空间不足等
     */
    long store(String relativePath, InputStream in) throws IOException;

    /**
     * 读取文件，返回可重复读的输入流。调用方负责用 try-with-resources 关闭。
     *
     * @throws IOException 文件不存在或不可读
     */
    InputStream load(String relativePath) throws IOException;

    /**
     * 删除文件。
     *
     * <p><b>不抛异常，返回布尔值</b>：删除失败最常见的原因是"文件本来就不在"
     * （上次清理过、或数据库记录是手工插的），这属于需要记日志但不需要中断业务的情况。
     * 若这里抛异常，调用方在删除附件记录时就会被一个"文件早没了"的问题卡住 ——
     * 用户想清理的东西反而清不掉。</p>
     */
    boolean delete(String relativePath);

    /** 文件是否存在，用于下载前给出 404 而不是让用户拿到一个空文件 */
    boolean exists(String relativePath);
}
