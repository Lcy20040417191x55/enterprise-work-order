package com.enterprise.workorder.agent;

import com.enterprise.workorder.common.BusinessException;
import com.enterprise.workorder.common.ResultCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容协议的模型客户端。
 *
 * <p>DeepSeek、通义、Kimi、OpenAI 官方都提供 {@code POST /chat/completions} 这套报文格式，
 * 差异只在 base-url 与 model 名，因此一个实现就能覆盖。换供应商改配置即可。</p>
 *
 * <p><b>为什么自己拼 Map 而不是写一堆 DTO</b>：请求体的形状有两个特点 ——
 * 字段大量可选（tools、tool_choice 只在需要时出现），且部分字段要按情况省略
 * （assistant 只调工具时 content 必须缺省而不是 null，否则部分服务端会报错）。
 * 用 Map 加条件 put，省掉十几个只有 getter/setter 的类，且"哪些字段会出现在报文里"
 * 在一处就能读全。</p>
 *
 * <p><b>为什么用 RestClient 而不是 WebClient</b>：本项目是同步阻塞的 Spring MVC + JDBC，
 * 引入响应式栈只会多一套线程模型要理解，而这里没有任何流式需求
 * （Agent 的回复要等工具执行完才能生成，天然是同步的）。</p>
 */
@Slf4j
@Component
public class OpenAiCompatibleClient implements LlmClient {

    private final LlmProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public OpenAiCompatibleClient(LlmProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;

        // 用 JDK 自带的 HttpClient 作为底层，避免为了设超时再引入 HttpComponents。
        // 超时必须显式设置：不设时默认是"无限等待"，模型服务偶尔挂住会把 Tomcat 线程占满。
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getTimeout())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.getTimeout());

        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .build();
    }

    @Override
    public ChatResult chat(List<Message> messages, List<ToolSpec> tools, String forcedToolName) {
        if (!properties.isReady()) {
            throw new BusinessException(ResultCode.BIZ_ERROR,
                    "智能助手未配置：请在 application-local.yml 中填写 app.llm.api-key");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.getModel());
        body.put("messages", toRequestMessages(messages));
        body.put("stream", false);
        // 始终显式声明思考模式开关。该供应商默认是开启的，若只在关闭时传参，
        // 换一家默认关闭的供应商就会行为不一致 —— 显式传值才与环境无关。
        body.put("thinking", Map.of("type", properties.isThinking() ? "enabled" : "disabled"));
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", toRequestTools(tools));
            if (forcedToolName != null && !forcedToolName.isBlank()) {
                // 强制调用：把"请按这个格式回答"变成协议层的约束，不依赖模型自觉
                body.put("tool_choice", Map.of(
                        "type", "function",
                        "function", Map.of("name", forcedToolName)));
            }
        }

        String raw;
        try {
            raw = restClient.post()
                    .uri(properties.chatCompletionsUrl())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            throw translate(e);
        } catch (Exception e) {
            // 连接超时、DNS 失败、读超时都会走到这里。这类错误必须给出可操作的提示，
            // 否则用户只看到"系统繁忙"，无从判断是网络问题还是配置问题。
            log.warn("调用大模型失败: {}", e.getMessage());
            throw new BusinessException(ResultCode.BIZ_ERROR,
                    "智能助手暂时不可用（网络或超时），请稍后重试或手动填单");
        }

        return parse(raw);
    }

    /**
     * 把服务端返回的错误码翻译成用户能看懂的话。
     *
     * <p>这四类错误的处理方式完全不同：401 要去改配置，402 要去充值，
     * 429 要等一会儿，5xx 只需重试。统一报"调用失败"等于让用户自己猜。</p>
     */
    private BusinessException translate(RestClientResponseException e) {
        int status = e.getStatusCode().value();
        log.warn("大模型返回错误状态 {}: {}", status, e.getResponseBodyAsString());
        String message = switch (status) {
            case 401 -> "智能助手鉴权失败：API Key 无效，请检查 app.llm.api-key";
            case 402 -> "智能助手余额不足，请到模型服务商控制台充值";
            case 429 -> "智能助手请求过于频繁，请稍后再试";
            case 400, 422 -> "智能助手请求参数有误，请检查 app.llm.model 是否为可用模型";
            default -> status >= 500
                    ? "智能助手服务暂时不可用，请稍后重试"
                    : "智能助手调用失败（HTTP " + status + "）";
        };
        return new BusinessException(ResultCode.BIZ_ERROR, message);
    }

    private List<Map<String, Object>> toRequestMessages(List<Message> messages) {
        List<Map<String, Object>> list = new ArrayList<>(messages.size());
        for (Message m : messages) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("role", m.role());
            if (m.content() != null) {
                item.put("content", m.content());
            }
            if (m.toolCalls() != null && !m.toolCalls().isEmpty()) {
                List<Map<String, Object>> calls = new ArrayList<>();
                for (ToolCall c : m.toolCalls()) {
                    calls.add(Map.of(
                            "id", c.id(),
                            "type", "function",
                            "function", Map.of("name", c.name(), "arguments", c.arguments())));
                }
                item.put("tool_calls", calls);
            }
            if (m.toolCallId() != null) {
                item.put("tool_call_id", m.toolCallId());
            }
            list.add(item);
        }
        return list;
    }

    private List<Map<String, Object>> toRequestTools(List<ToolSpec> tools) {
        List<Map<String, Object>> list = new ArrayList<>(tools.size());
        for (ToolSpec t : tools) {
            Map<String, Object> parameters;
            try {
                parameters = objectMapper.readValue(t.parametersJson(), Map.class);
            } catch (Exception e) {
                // schema 是自己写的常量，解析不了说明代码有错，属于开发期问题，
                // 不硬崩整个请求，跳过该工具并留下日志
                log.error("工具 {} 的参数 schema 不是合法 JSON，已跳过", t.name(), e);
                continue;
            }
            list.add(Map.of("type", "function", "function", Map.of(
                    "name", t.name(),
                    "description", t.description(),
                    "parameters", parameters)));
        }
        return list;
    }

    private ChatResult parse(String raw) {
        JsonNode root;
        try {
            root = objectMapper.readTree(raw);
        } catch (Exception e) {
            log.error("大模型返回的不是合法 JSON: {}", raw, e);
            throw new BusinessException(ResultCode.BIZ_ERROR, "智能助手返回内容无法解析，请重试");
        }

        JsonNode choice = root.path("choices").path(0);
        if (choice.isMissingNode()) {
            throw new BusinessException(ResultCode.BIZ_ERROR, "智能助手未返回有效结果，请重试");
        }

        JsonNode message = choice.path("message");
        String content = message.path("content").isNull() ? null : message.path("content").asText(null);

        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode call : message.path("tool_calls")) {
            JsonNode fn = call.path("function");
            calls.add(new ToolCall(
                    call.path("id").asText(),
                    fn.path("name").asText(),
                    fn.path("arguments").asText("{}")));
        }

        String finishReason = choice.path("finish_reason").asText(null);
        return new ChatResult(content, calls, finishReason);
    }
}