package com.enterprise.workorder.attachment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件名清洗与类型判定测试。
 *
 * <p><b>为什么这个纯单测值得单独存在</b>：附件上传的三道校验里，有两道全靠本类的函数 ——
 * 扩展名白名单判断与文件头魔数比对。它们是"上传接口是否会放过一个可执行文件"的唯一防线，
 * 而防线出问题时不会有任何报错，只会安静地放过。这类"沉默的失败"必须靠测试钉住。</p>
 *
 * <p>不启动 Spring、不连数据库：这些都是纯函数，输入输出完全确定。</p>
 */
@DisplayName("附件类型判定与文件名清洗")
class FileTypesTest {

    // ==================================================================
    //  扩展名提取
    // ==================================================================

    @Nested
    @DisplayName("扩展名提取")
    class Extension {

        @Test
        @DisplayName("取最后一个点之后的部分：文件名里本身带日期也不能取错")
        void shouldTakeLastSegment() {
            // 取第一个点会得到 "09.21 报销单.pdf"，类型判定立刻失效 ——
            // 而"文件名里带日期"在报销、合同这类业务里是常态
            assertThat(FileTypes.extensionOf("2026.09.21 报销单.pdf")).isEqualTo("pdf");
            assertThat(FileTypes.extensionOf("archive.tar.gz")).isEqualTo("gz");
        }

        @Test
        @DisplayName("统一转小写：IMG.PNG 与 img.png 是同一类文件")
        void shouldLowerCase() {
            // 不转小写的话白名单里得同时写 png 和 PNG，
            // 用户把相机导出的 IMG_0001.JPG 传上来就会被拒 —— 而它明明是合法图片
            assertThat(FileTypes.extensionOf("IMG_0001.JPG")).isEqualTo("jpg");
        }

        @Test
        @DisplayName("以点开头的隐藏文件没有扩展名：.bashrc 不是 bashrc 类型")
        void dotfileHasNoExtension() {
            // 这里如果判断写成 dot < 0，".bashrc" 会被识别成扩展名 "bashrc"，
            // 结果是一个"扩展名"通过了白名单检查（因为白名单里没有，反而会被拒），
            // 行为变得依赖白名单内容，而不是依赖文件名本身的性质
            assertThat(FileTypes.extensionOf(".bashrc")).isEmpty();
        }

        @Test
        @DisplayName("没有点、以点结尾、null 都返回空扩展名")
        void shouldReturnEmptyForNoExtension() {
            assertThat(FileTypes.extensionOf("README")).isEmpty();
            assertThat(FileTypes.extensionOf("报告.")).isEmpty();
            assertThat(FileTypes.extensionOf(null)).isEmpty();
            assertThat(FileTypes.extensionOf("")).isEmpty();
        }
    }

    // ==================================================================
    //  魔数比对
    // ==================================================================

    @Nested
    @DisplayName("文件头魔数比对")
    class Magic {

        @Test
        @DisplayName("PDF 以 %PDF 开头才算 PDF")
        void pdfShouldMatch() {
            assertThat(FileTypes.matchesMagic("pdf", head("%PDF-1.7\n..."))).isTrue();
        }

        @Test
        @DisplayName("把 exe 改名成 .pdf 必须被拦下：内容说了算，扩展名说了不算")
        void renamedExecutableShouldBeRejected() {
            // Windows PE 文件以 "MZ" 开头。用字节数组而不是字符串字面量，
            // 是因为 PE 头后面跟着 0x90 0x00 0x03 这些不可打印字节
            byte[] exe = {'M', 'Z', (byte) 0x90, 0x00, 0x03, 0x00};
            assertThat(FileTypes.matchesMagic("pdf", exe)).isFalse();
            // 用户（或攻击者）只要改个后缀，扩展名校验就完全失去意义
            assertThat(FileTypes.matchesMagic("pdf", head("MZ\u0090\u0000\u0003"))).isFalse();
        }

        @Test
        @DisplayName("首字节 0x89 的 PNG 必须能匹配：byte 是有符号的，不做无符号转换就会永远比不中")
        void pngWithHighBitByteShouldMatch() {
            // 这条守的是 toHex 里的 & 0xFF。少了它，0x89 会被格式化成长度 8 的
            // "FFFFFF89"，与魔数表 "89504E47" 永远匹配失败 ——
            // 症状是"所有 PNG 都传不上去"，而 PDF 却能传（%PDF 全在 ASCII 范围内）
            byte[] png = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
            assertThat(FileTypes.matchesMagic("png", png)).isTrue();
        }

        @Test
        @DisplayName("Office 2003 的 OLE 头首字节也是高位字节，同样要能匹配")
        void docOleHeaderShouldMatch() {
            byte[] ole = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
                    (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
            assertThat(FileTypes.matchesMagic("doc", ole)).isTrue();
            assertThat(FileTypes.matchesMagic("xls", ole)).isTrue();
            assertThat(FileTypes.matchesMagic("ppt", ole)).isTrue();
        }

        @Test
        @DisplayName("docx 与 zip 都是 PK 开头的压缩包，两者都能匹配")
        void zipBasedFormatsShareSignature() {
            byte[] zip = {0x50, 0x4B, 0x03, 0x04};
            assertThat(FileTypes.matchesMagic("docx", zip)).isTrue();
            assertThat(FileTypes.matchesMagic("xlsx", zip)).isTrue();
            assertThat(FileTypes.matchesMagic("zip", zip)).isTrue();
        }

        @Test
        @DisplayName("txt / csv 没有可靠魔数，一律放行，交给扩展名白名单把关")
        void textFormatsHaveNoMagic() {
            // 这里如果返回 false，所有文本文件都传不上来。
            // "没有可判断的依据"和"判断结果为否"是两回事，不能混为一谈
            assertThat(FileTypes.hasMagic("txt")).isFalse();
            assertThat(FileTypes.matchesMagic("txt", head("随便什么内容"))).isTrue();
            assertThat(FileTypes.matchesMagic("csv", head("a,b,c"))).isTrue();
        }

        @Test
        @DisplayName("空文件头一律不匹配：读不到内容时不能假定它是对的")
        void emptyHeadShouldNotMatch() {
            assertThat(FileTypes.matchesMagic("pdf", new byte[0])).isFalse();
            assertThat(FileTypes.matchesMagic("pdf", null)).isFalse();
        }

        @Test
        @DisplayName("头部比签名还短时不匹配，且不能下标越界")
        void shorterThanSignatureShouldNotMatch() {
            // 上传 2 字节的文件、扩展名写成 .pdf，这是绕过校验的常见尝试
            assertThat(FileTypes.matchesMagic("pdf", new byte[]{0x25, 0x50})).isFalse();
        }
    }

    // ==================================================================
    //  文件名清洗
    // ==================================================================

    @Nested
    @DisplayName("文件名清洗")
    class Sanitize {

        @Test
        @DisplayName("剥掉客户端带上来的完整路径：老浏览器上传时会带上盘符与目录")
        void shouldStripWindowsPath() {
            assertThat(FileTypes.sanitize("C:\\Users\\x\\发票.pdf")).isEqualTo("发票.pdf");
            assertThat(FileTypes.sanitize("/home/x/发票.pdf")).isEqualTo("发票.pdf");
        }

        @Test
        @DisplayName("路径穿越尝试只能剩下最后一段，拿不到 ../")
        void shouldDefeatTraversal() {
            // 这个结果即使被误当成路径使用，也只是当前目录下的一个普通文件名。
            // 真正落盘的文件名由服务端生成 UUID，这里是第三道保险
            assertThat(FileTypes.sanitize("..\\..\\etc\\passwd.txt")).isEqualTo("passwd.txt");
        }

        @Test
        @DisplayName("清掉控制字符：文件名会被拼进 Content-Disposition，回车换行能注入响应头")
        void shouldStripControlCharacters() {
            // 这是历史上真实出现过的漏洞类别（HTTP 响应头注入）。
            // 攻击者把文件名设成 "a.pdf\r\nSet-Cookie: x=1"，若原样拼进响应头，
            // 就能凭空插入一个响应头
            assertThat(FileTypes.sanitize("报\r\n告.pdf")).isEqualTo("报告.pdf");
            assertThat(FileTypes.sanitize("a\tb.pdf")).isEqualTo("ab.pdf");
        }

        @Test
        @DisplayName("剥掉开头的点：.. 与 .bashrc 这类名字没有正常用途")
        void shouldStripLeadingDots() {
            assertThat(FileTypes.sanitize(".bashrc")).isEqualTo("bashrc");
            // 全是点的情况必须兜底，否则会返回空字符串，
            // 后面的扩展名校验会拿到空名字，报出一句让人摸不着头脑的错
            assertThat(FileTypes.sanitize("...")).isEqualTo(FileTypes.DEFAULT_NAME);
        }

        @Test
        @DisplayName("空名与空白名回落到默认展示名")
        void shouldFallbackToDefaultName() {
            assertThat(FileTypes.sanitize(null)).isEqualTo(FileTypes.DEFAULT_NAME);
            assertThat(FileTypes.sanitize("   ")).isEqualTo(FileTypes.DEFAULT_NAME);
        }

        @Test
        @DisplayName("超长名字被截断，但扩展名必须保留")
        void shouldTruncateButKeepExtension() {
            String longName = "报".repeat(300) + ".pdf";
            String cleaned = FileTypes.sanitize(longName);

            // 保留扩展名是硬要求：类型校验与前端按扩展名选图标都依赖它。
            // 若先按长度裁再取扩展名，超长文件名的扩展名会被裁掉，
            // 结果是"这个文件类型不允许上传"，而用户看到的名字明明是以 .pdf 结尾的
            assertThat(cleaned).endsWith(".pdf");
            assertThat(cleaned).hasSize(200);
        }

        @Test
        @DisplayName("正常文件名原样保留，包括空格、括号与中文")
        void shouldKeepNormalNameIntact() {
            assertThat(FileTypes.sanitize("2026年9月 报销单(终版).pdf"))
                    .isEqualTo("2026年9月 报销单(终版).pdf");
        }
    }

    /** 把字符串按 UTF-8 转成字节数组，用作测试用的文件头 */
    private static byte[] head(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
