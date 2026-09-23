package com.enterprise.workorder.agent;

import java.util.List;

/**
 * 大模型调用边界。
 *
 * <p><b>为什么要有这个接口，而不是直接 new 一个 HTTP 客户端到处用</b>：
 * AgentService 需要的是"给我一段回答"，不应该知道 URL 长什么样、超时怎么设、
 * 报文里 {@code tool_calls} 嵌在第几层。把这些关在实现类里之后，</p>
 * <ul>
 *   <li>写单元测试时可以塞一个假的实现，不必真的去调外部接口（也不花钱）；</li>
 *   <li>换协议（例如改用 Anthropic 格式）只新增一个实现类，业务代码不动。</li>
 * </ul>
 *
 * <p>下面这几个 record 定义成接口的内部类型，而不是各自独立成文件：
 * 它们只在"调用大模型"这一件事里出现，离开这个场景没有任何意义 ——
 * 独立成 {@code dto} 包下的公开类型，等于对外承诺它们可以被别的模块复用。</p>
 */
public interface LlmClient {

    /**
     * 一条对话消息。
     *
     * @param role        system / user / assistant / tool
     * @param content     文本内容；assistant 只调工具不回答时可为 null
     * @param toolCalls   仅 assistant 消息使用：模型这一轮请求调用的工具
     * @param toolCallId  仅 tool 消息使用：这条结果对应哪一次工具调用
     */
    record Message(String role, String content, List<ToolCall> toolCalls, String toolCallId) {

        public static Message system(String content) {
            return new Message("system", content, null, null);
        }

        public static Message user(String content) {
            return new Message("user", content, null, null);
        }

        public static Message assistant(String content) {
            return new Message("assistant", content, null, null);
        }

        /** 模型请求调用工具的那一轮，必须原样回灌给下一轮请求 */
        public static Message assistantToolCalls(List<ToolCall> calls) {
            return new Message("assistant", null, calls, null);
        }

        public static Message tool(String toolCallId, String content) {
            return new Message("tool", content, null, toolCallId);
        }
    }

    /**
     * 一次工具调用请求。
     *
     * @param arguments 模型生成的 JSON 字符串。<b>它不保证合法</b>，
     *                  官方文档明确说明模型可能生成非法 JSON 或凭空造出 schema 里没有的参数，
     *                  所以调用方必须先校验再使用，不能直接当成可信输入。
     */
    record ToolCall(String id, String name, String arguments) {
    }

    /**
     * 暴露给模型的工具说明。
     *
     * @param parametersJson JSON Schema 字符串，描述参数结构
     */
    record ToolSpec(String name, String description, String parametersJson) {
    }

    /**
     * 一次调用的结果。
     *
     * @param content      模型的自然语言回答；只调工具时为 null
     * @param toolCalls    模型请求调用的工具，可能为空
     * @param finishReason 结束原因，{@code tool_calls} 表示它还要继续调工具
     */
    record ChatResult(String content, List<ToolCall> toolCalls, String finishReason) {

        public boolean hasToolCalls() {
            return toolCalls != null && !toolCalls.isEmpty();
        }
    }

    /**
     * 发起一次对话补全。
     *
     * @param forcedToolName 非空时强制模型调用指定工具（用于"结构化输出"场景：
     *                       与其求模型"请只输出 JSON"，不如把它变成一个必须调用的工具，
     *                       格式由 schema 保证，不依赖模型的自觉）
     */
    ChatResult chat(List<Message> messages, List<ToolSpec> tools, String forcedToolName);
}