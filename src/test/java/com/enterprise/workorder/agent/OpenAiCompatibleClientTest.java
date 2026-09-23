package com.enterprise.workorder.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 请求体构造的测试。
 *
 * <p><b>这组用例守护的是「换供应商只改配置」这句承诺。</b>
 * 原先请求体里无条件带一个 {@code thinking} 字段 —— 那是 DeepSeek 专有的，
 * 智谱、通义、Kimi、OpenAI 官方都不认识它。若默认发送，换供应商的第一件事
 * 就是收到一个莫名其妙的 400，而配置看起来完全正确，
 * 排查方向会偏到 api-key 和 base-url 上去。</p>
 *
 * <p>不联网：只调 {@code buildRequestBody}，它不发出任何请求。
 * 这正是把它从 {@code chat()} 里抽出来的原因 —— 否则要验证"哪个字段没被发出去"，
 * 就得真的调一次外部服务，既慢又不确定。</p>
 */
@DisplayName("大模型客户端 - 请求体构造")
class OpenAiCompatibleClientTest {

    private OpenAiCompatibleClient client(boolean thinkingParam) {
        LlmProperties props = new LlmProperties();
        props.setApiKey("test-key");
        props.setThinkingParam(thinkingParam);
        return new OpenAiCompatibleClient(props, new com.fasterxml.jackson.databind.ObjectMapper());
    }

    private static List<LlmClient.Message> messages() {
        return List.of(LlmClient.Message.system("sys"), LlmClient.Message.user("三楼打印机坏了"));
    }

    @Test
    @DisplayName("默认不带 thinking 字段 —— 否则换智谱/通义会直接 400")
    void omitsThinkingByDefault() {
        Map<String, Object> body = client(false)
                .buildRequestBody(messages(), List.of(), null);

        assertThat(body).doesNotContainKey("thinking");
        assertThat(body).containsEntry("model", "deepseek-flash");
        assertThat(body).containsEntry("stream", false);
    }

    @Test
    @DisplayName("显式打开时才带 thinking，供 DeepSeek 使用")
    void includesThinkingWhenEnabled() {
        Map<String, Object> body = client(true)
                .buildRequestBody(messages(), List.of(), null);

        assertThat(body).containsKey("thinking");
        assertThat(body.get("thinking")).isEqualTo(Map.of("type", "disabled"));
    }

    @Test
    @DisplayName("没有工具时不带 tools，避免部分服务端对空数组报错")
    void omitsToolsWhenEmpty() {
        Map<String, Object> body = client(false)
                .buildRequestBody(messages(), List.of(), null);

        assertThat(body).doesNotContainKey("tools");
        assertThat(body).doesNotContainKey("tool_choice");
    }

    @Test
    @DisplayName("给了工具就带上 tools，并把入参 schema 原样嵌入")
    void includesTools() {
        LlmClient.ToolSpec spec = new LlmClient.ToolSpec(
                "draftTicket", "建单", "{\"type\":\"object\",\"properties\":{}}");

        Map<String, Object> body = client(false)
                .buildRequestBody(messages(), List.of(spec), null);

        assertThat(body).containsKey("tools");
        List<?> tools = (List<?>) body.get("tools");
        assertThat(tools).hasSize(1);
        Map<?, ?> first = (Map<?, ?>) tools.get(0);
        assertThat(first.get("type")).isEqualTo("function");
        Map<?, ?> fn = (Map<?, ?>) first.get("function");
        assertThat(fn.get("name")).isEqualTo("draftTicket");
        assertThat(fn.get("parameters")).isEqualTo(Map.of("type", "object", "properties", Map.of()));
    }

    @Test
    @DisplayName("429 与 5xx 才重试；401/402/400 重试只会让用户白等")
    void onlyRetriesTransientErrors() {
        assertThat(OpenAiCompatibleClient.isRetryable(429)).isTrue();
        assertThat(OpenAiCompatibleClient.isRetryable(500)).isTrue();
        assertThat(OpenAiCompatibleClient.isRetryable(503)).isTrue();
        assertThat(OpenAiCompatibleClient.isRetryable(401)).isFalse();
        assertThat(OpenAiCompatibleClient.isRetryable(402)).isFalse();
        assertThat(OpenAiCompatibleClient.isRetryable(400)).isFalse();
    }

    @Test
    @DisplayName("强制工具调用时带上 tool_choice，不依赖模型自觉")
    void setsForcedToolChoice() {
        LlmClient.ToolSpec spec = new LlmClient.ToolSpec(
                "draftTicket", "建单", "{\"type\":\"object\",\"properties\":{}}");

        Map<String, Object> body = client(false)
                .buildRequestBody(messages(), List.of(spec), "draftTicket");

        assertThat(body).containsKey("tool_choice");
        assertThat(body.get("tool_choice")).isEqualTo(Map.of(
                "type", "function", "function", Map.of("name", "draftTicket")));
    }
}
