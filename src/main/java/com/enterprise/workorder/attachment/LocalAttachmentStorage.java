package com.enterprise.workorder.attachment;

import com.enterprise.workorder.common.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 本地磁盘存储实现。
 *
 * <p><b>本类最关键的一段代码是 {@link #resolve}</b>，不是 {@code store}。
 * 写文件谁都会写，真正容易出事的是"用户可控的字符串参与路径拼接" ——
 * 一旦某个入口漏掉了校验，{@code ../../} 就能把文件写到存储根目录之外。
 * 所以这里把所有路径拼接收敛到唯一一个方法，并在其中做归一化 + 前缀校验，
 * 让"越界"在架构上不可能发生，而不是靠每个调用点自觉。</p>
 */
@Slf4j
@Component
public class LocalAttachmentStorage implements AttachmentStorage {

    private static final DateTimeFormatter DIR_FMT = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    /** 存储根目录的绝对、已归一化路径，所有越界判断都以它为基准 */
    private final Path root;

    public LocalAttachmentStorage(AttachmentProperties properties) {
        // toAbsolutePath().normalize() 是必须的：配置里写 "data/attachments" 时，
        // 相对路径在不同工作目录下解析结果不同；而 normalize 会把 "a/../b" 这类
        // 写法折叠掉。不先归一化，下面的 startsWith 校验就是不可靠的。
        this.root = Paths.get(properties.getStorageRoot()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            // 启动即失败，而不是等到用户第一次上传才报错。
            // 配置错了就应该拒绝启动，让问题在部署阶段暴露。
            throw new IllegalStateException("附件存储根目录创建失败: " + root, e);
        }
        log.info("附件存储根目录: {}", root);
    }

    @Override
    public String newRelativePath(String extension) {
        String suffix = (extension == null || extension.isBlank()) ? "" : "." + extension;
        // 日期目录 + UUID 文件名。两段都不含任何用户输入，因此返回值天然安全。
        return LocalDate.now().format(DIR_FMT) + "/" + UUID.randomUUID().toString().replace("-", "") + suffix;
    }

    @Override
    public long store(String relativePath, InputStream in) throws IOException {
        Path target = resolve(relativePath);
        // 目录按需创建，而不是在 newRelativePath 时就建：
        // 若上传中途失败（超限、类型不符），已经建出来的空目录会一直留在磁盘上，
        // 日积月累全是空目录。
        Files.createDirectories(target.getParent());
        // 先写临时文件再原子改名，避免"半个文件"被下载到。
        // 同一个目录下改名是原子的，因此任何时刻 target 要么不存在、要么是完整文件。
        Path temp = target.resolveSibling(target.getFileName() + ".part");
        try {
            long written = Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            return written;
        } catch (IOException e) {
            // 失败时清理临时文件：留下 .part 除了占空间没有任何用处
            deleteQuietly(temp);
            throw e;
        }
    }

    @Override
    public InputStream load(String relativePath) throws IOException {
        Path target = resolve(relativePath);
        if (!Files.isRegularFile(target)) {
            // 这里必须主动抛，不能让 Files.newInputStream 自己报
            // NoSuchFileException：调用方需要区分"文件丢了"和"磁盘坏了"
            throw new IOException("附件文件不存在: " + relativePath);
        }
        return Files.newInputStream(target);
    }

    @Override
    public boolean delete(String relativePath) {
        try {
            return Files.deleteIfExists(resolve(relativePath));
        } catch (IOException e) {
            // 删不掉（文件被占用、权限不足）不该阻断业务：数据库里那条记录该删还是要删。
            // 磁盘上残留的孤儿文件由定期清理任务兜底 —— 反过来做（因为删不掉文件就拒绝删记录）
            // 会让用户在页面上看到一个永远删不掉的附件。
            log.warn("附件文件删除失败，将留下孤儿文件: {}", relativePath, e);
            return false;
        }
    }

    @Override
    public boolean exists(String relativePath) {
        try {
            return Files.isRegularFile(resolve(relativePath));
        } catch (BusinessException e) {
            // 路径非法等同于"不存在"，不向上抛：判断存在性时不该因为路径可疑而炸掉整个请求
            return false;
        }
    }

    /**
     * 把相对路径解析成绝对路径，并确保它落在存储根目录内。
     *
     * <p><b>这是本类唯一允许拼接路径的地方。</b>两道防线：</p>
     * <ol>
     *   <li>{@code normalize()} 把 {@code a/../../b} 折叠成实际指向的位置。
     *       少了这一步，{@code startsWith} 判断的是"字符串前缀"而不是"真实位置"，
     *       形同虚设。</li>
     *   <li>{@code startsWith(root)} 校验归一化后的绝对路径确实在根目录之下。
     *       注意这里比的是 Path 对象而不是字符串 —— Path 的 startsWith 按路径段比较，
     *       能正确区分 {@code /data/attach} 与 {@code /data/attachments-evil}，
     *       字符串版则会误判成"在范围内"。</li>
     * </ol>
     *
     * <p>相对路径理论上只来自本类生成的 UUID 路径和数据库里的 storedPath，
     * 两者都不含用户输入。但"理论上"三个字正是安全问题的温床 ——
     * 只要将来有人加了个按文件名下载的接口，这道校验就是最后一道闸门。</p>
     */
    private Path resolve(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new BusinessException("附件路径为空");
        }
        Path resolved = root.resolve(relativePath).normalize();
        if (!resolved.startsWith(root)) {
            log.warn("检测到越界的附件路径: root={} relative={}", root, relativePath);
            throw new BusinessException("附件路径非法");
        }
        return resolved;
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 临时文件清理失败不值得再报一次错，原始异常更重要
        }
    }
}
