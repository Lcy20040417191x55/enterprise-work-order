package com.enterprise.workorder.attachment;

import java.util.Locale;
import java.util.Map;

/**
 * 附件的类型判定与文件名清洗。全是无状态的纯函数，因此不依赖 Spring，可以直接单测。
 *
 * <p><b>本类要解决的核心问题</b>：{@code MultipartFile.getContentType()} 和文件名里的
 * 扩展名，两者都由客户端提供，都不可信。把 exe 改名成"发票.pdf"、再声明
 * {@code Content-Type: application/pdf}，只是一行代码的事。唯一能验证"这真的是 PDF"的
 * 办法是看内容本身 —— 每种常见格式的文件头都有固定的魔数字节。</p>
 *
 * <p><b>为什么扩展名和魔数两道都要查</b>：只查魔数，"a.txt 里装着 PDF"会通过，
 * 而按 .txt 存下来的文件在用户机器上双击会用记事本打开 —— 真正的 PDF 被当文本读，
 * 用户只会觉得文件坏了；只查扩展名，则等于完全不设防。</p>
 */
public final class FileTypes {

    private FileTypes() {
    }

    /** 原始文件名为空时的兜底展示名 */
    public static final String DEFAULT_NAME = "未命名文件";

    /** 展示名最大长度。数据库列是 VARCHAR(255)，留出余量 */
    private static final int MAX_NAME_LENGTH = 200;

    /** 校验魔数需要读到的头部字节数：最长的签名是 8 字节，读 16 字节留足余量 */
    public static final int HEAD_BYTES = 16;

    /**
     * 扩展名 -> 文件头魔数（十六进制前缀）。
     *
     * <p><b>不在这张表里的扩展名意味着"没有可靠魔数"</b>，例如 txt / csv
     * 本质上是任意文本，没有任何固定头部，这类扩展名只靠白名单把关。
     * 表里存在、但配置白名单里没有的扩展名，会被扩展名检查先一步拦下。</p>
     */
    private static final Map<String, String[]> MAGIC = Map.ofEntries(
            // PDF 以 "%PDF"（25 50 44 46）开头
            Map.entry("pdf", new String[]{"25504446"}),
            // PNG: 89 50 4E 47 0D 0A 1A 0A
            Map.entry("png", new String[]{"89504E47"}),
            // JPEG: FF D8 FF，第三字节是变体标识，只比前三个字节
            Map.entry("jpg", new String[]{"FFD8FF"}),
            Map.entry("jpeg", new String[]{"FFD8FF"}),
            // GIF87a / GIF89a 都以 "GIF8"（47 49 46 38）开头
            Map.entry("gif", new String[]{"47494638"}),
            // BMP 以 "BM"（42 4D）开头。只有两字节，是这张表里最弱的签名 ——
            // 但 BMP 规范如此，没有更强的头部可依赖
            Map.entry("bmp", new String[]{"424D"}),
            // ZIP: 50 4B 03 04（PK\003\004）
            Map.entry("zip", new String[]{"504B0304"}),
            // RAR4: 52 61 72 21 1A 07 00   RAR5: 52 61 72 21 1A 07 01 00
            // 取共同前缀即可同时覆盖两个版本
            Map.entry("rar", new String[]{"526172211A07"}),
            // 7z: 37 7A BC AF 27 1C
            Map.entry("7z", new String[]{"377ABCAF271C"}),
            // Office 2003 系列（doc/xls/ppt）是 OLE 复合文档：D0 CF 11 E0 A1 B1 1A E1
            Map.entry("doc", new String[]{"D0CF11E0A1B11AE1"}),
            Map.entry("xls", new String[]{"D0CF11E0A1B11AE1"}),
            Map.entry("ppt", new String[]{"D0CF11E0A1B11AE1"}),
            // Office 2007+ 系列（docx/xlsx/pptx）本质上都是 ZIP 包
            Map.entry("docx", new String[]{"504B0304"}),
            Map.entry("xlsx", new String[]{"504B0304"}),
            Map.entry("pptx", new String[]{"504B0304"})
    );

    /**
     * 取小写扩展名，不含点。没有扩展名（或只有结尾一个点）时返回空字符串。
     *
     * <p>取的是<b>最后一个</b>点之后的部分：{@code 2026.09.21 报销单.pdf}
     * 必须识别成 pdf，而不是 "21 报销单.pdf"。</p>
     */
    public static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        // dot == 0 说明是 ".bashrc" 这类隐藏文件，它没有扩展名
        if (dot <= 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** 该扩展名是否有已知魔数需要比对 */
    public static boolean hasMagic(String extension) {
        return extension != null && MAGIC.containsKey(extension.toLowerCase(Locale.ROOT));
    }

    /**
     * 文件头是否符合该扩展名的魔数。
     *
     * @param extension 小写扩展名
     * @param head      文件开头的若干字节
     * @return 无已知魔数的扩展名恒返回 true（交给扩展名白名单把关）；
     *         有魔数的扩展名，只有 head 匹配上任一版本时才返回 true
     */
    public static boolean matchesMagic(String extension, byte[] head) {
        String[] signatures = MAGIC.get(extension == null ? "" : extension.toLowerCase(Locale.ROOT));
        if (signatures == null) {
            // 没有已知魔数 => 本方法不做判断。绝不能返回 false，
            // 否则 txt / csv 这类合法文件永远传不上来
            return true;
        }
        if (head == null || head.length == 0) {
            return false;
        }
        String hex = toHex(head);
        for (String signature : signatures) {
            if (hex.startsWith(signature)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 清洗用户提供的文件名，得到可以安全展示、安全回填进响应头的名字。
     *
     * <p><b>这里只做"展示与回填"的清洗</b> —— 磁盘上的文件名完全由服务端生成，
     * 不走这个函数。原因是：只要有一处把用户文件名当路径用，就等于把
     * "向磁盘任意位置写文件"的能力交给了 HTTP 请求方。</p>
     *
     * <p>具体清掉四类东西：</p>
     * <ol>
     *   <li><b>路径前缀</b>：部分浏览器（老 IE、某些移动端 WebView）上传时会带上完整
     *       路径 {@code C:\Users\x\发票.pdf}，存下来难看，还暴露了客户端目录结构。</li>
     *   <li><b>控制字符</b>：文件名里混入 {@code \r\n} 可以向响应头注入内容 ——
     *       Content-Disposition 正是把文件名拼进 HTTP 头的地方，这是历史上真实出过的漏洞。</li>
     *   <li><b>开头的点</b>：{@code ..}、{@code .bashrc} 这类以点开头的名字没有正常用途，
     *       留着只是给路径拼接留想象空间。</li>
     *   <li><b>超长名字</b>：数据库列是 VARCHAR(255)，超长会在插入时才报错，
     *       那时文件已经落盘，还得回头清理。</li>
     * </ol>
     */
    public static String sanitize(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return DEFAULT_NAME;
        }
        // 两种分隔符都要剥：反斜杠在 Linux 上不是分隔符，但 Windows 客户端会发过来
        String name = rawName.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        // \p{Cntrl} 覆盖全部 ASCII 控制字符，含 \r \n \t 与 NUL
        name = name.replaceAll("\\p{Cntrl}", "").trim();

        while (name.startsWith(".")) {
            name = name.substring(1);
        }
        if (name.isEmpty()) {
            return DEFAULT_NAME;
        }
        if (name.length() > MAX_NAME_LENGTH) {
            // 先算扩展名再裁长度：把扩展名裁掉会让后续的类型校验失去依据
            String extension = extensionOf(name);
            String suffix = extension.isEmpty() ? "" : "." + extension;
            int keep = MAX_NAME_LENGTH - suffix.length();
            name = name.substring(0, Math.max(1, keep)) + suffix;
        }
        return name;
    }

    /**
     * 字节数组转大写十六进制串，用于与魔数表比对。
     *
     * <p>用 {@code & 0xFF} 把 byte 转成 0~255 的无符号值：Java 的 byte 是有符号的，
     * 直接格式化会把 0x89 变成 {@code ffffff89}，与魔数表永远比不中。</p>
     */
    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString().toUpperCase(Locale.ROOT);
    }
}
