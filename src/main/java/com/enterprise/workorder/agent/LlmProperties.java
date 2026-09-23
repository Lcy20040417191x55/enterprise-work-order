package com.enterprise.workorder.agent;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * 大模型配置，对应 application.yml 中的 app.llm.*
 *
 * <p><b>为什么 base-url 和 model 都做成配置项</b>：本类只实现 OpenAI 兼容的
 * chat/completions 协议，而 DeepSeek、通义、Kimi、OpenAI 官方都提供这套协议。
 * 换供应商时只改这两行配置，Java 代码一行不动 —— 这是"抽象一层"的实际收益，
 * 而不是为了设计模式而设计模式。若把供应商写死在代码里，
 * 将来遇到限流（DeepSeek 的 429）想临时切到别家，就得改代码重新打包发布。</p>
 *
 * <p><b>为什么 Thinking 默认关闭</b>：DeepSeek 的 thinking 模式默认开启，
 * 模型会先输出一段思维链再给答案。这对复杂推理有价值，但本项目两个场景都不需要：
 * 建单解析是"把一句话填进四个字段"，对话问答是"调个查询接口再复述"。
 * 开着它只会让单次响应从 1~2 秒变成 5~10 秒、输出 token 翻几倍（思维链按输出计费），
 * 而准确率没有可感知的提升。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.llm")
public class LlmProperties {

    /**
     * 服务地址。DeepSeek 为 https://api.deepseek.com（不要带 /v1，它的路由不认）。
     * 换成 OpenAI 官方时填 https://api.openai.com/v1。
     */
    private String baseUrl = "https://api.deepseek.com";

    /**
     * 模型名。注意 DeepSeek 已经换过一轮命名：
     * 旧文档里的 deepseek-chat 现已不再出现在可用模型列表里，
     * 当前可用的只有 deepseek-flash（快、便宜）与 deepseek-v4-pro（强、贵）。
     * 本项目用 flash。
     */
    private String model = "deepseek-flash";

    /**
     * API Key。刻意不给默认值 —— 留空时 {@link #isReady()} 返回 false，
     * Agent 功能整体降级为"提示未配置"，而不是启动就报错。
     * 理由：这个 Key 是可选的附加能力，没有它时工单系统本身必须完全可用。
     */
    private String apiKey;

    /** 单次请求超时。含连接与读取，取值要覆盖模型生成整段回答的时间。 */
    private Duration timeout = Duration.ofSeconds(30);

    /**
     * 429 / 5xx 时的额外重试次数。
     *
     * <p><b>为什么免费模型必须重试</b>：实测智谱免费模型 glm-4.5-flash 在
     * 不同时段会有相当比例的请求直接返回 429「访问量过大」——
     * 不重试时用户看到的是随机失败，同一个输入有时成功有时报错，
     * 看起来像系统坏了。加两次重试后实测成功率从约一半提升到 4/5。</p>
     *
     * <p>只重试 429 与 5xx：401/402/400 重试多少次结果都一样，只会让用户多等。</p>
     */
    private int maxRetries = 2;

    /** 首次重试前的等待时长；第 n 次重试等待 n 倍，避免同时涌入继续加剧拥堵 */
    private Duration retryBackoff = Duration.ofSeconds(2);

    /** 是否启用思考模式（仅在 {@link #thinkingParam} 为 true 时才发往服务端）。 */
    private boolean thinking = false;

    /**
     * 是否在请求体里携带 thinking 参数。
     *
     * <p><b>默认 false，因为 thinking 是 DeepSeek 专有字段。</b>
     * 智谱、通义、Kimi、OpenAI 官方都不认识它，带上可能直接返回 400 ——
     * 而本项目号称「换供应商只改 base-url 与 model」，若默认发送，
     * 这个说法就不成立：换个供应商第一件事是莫名其妙地报参数错误。
     * 需要 DeepSeek 的思考模式时才显式打开。</p>
     */
    private boolean thinkingParam = false;

    /** 工具调用的最大轮次，防止模型在两个工具之间来回调用形成死循环 */
    private int maxToolRounds = 4;

    /** 单轮对话最多携带的历史消息条数，防止前端传超长历史把上下文撑爆 */
    private int maxHistoryMessages = 20;

    /**
     * 配置是否完整。
     *
     * <p>判断的是"有没有 Key"，而不是"服务能不能连通"—— 后者要发一次真实请求才能知道，
     * 不适合放在每次调用前的判断里。连通性由调用时的异常兜底。</p>
     */
    public boolean isReady() {
        return StringUtils.hasText(apiKey);
    }

    /** 拼接 chat/completions 路径，容忍 baseUrl 结尾多写一个斜杠 */
    public String chatCompletionsUrl() {
        String base = baseUrl == null ? "" : baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/chat/completions";
    }
}