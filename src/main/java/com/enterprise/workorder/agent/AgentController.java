package com.enterprise.workorder.agent;

import com.enterprise.workorder.common.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 智能助手接口。
 *
 * <p><b>为什么路径是 /api/agent 而不是挂到 /api/tickets 下</b>：
 * 它不操作某一张工单，而是「用自然语言驱动工单能力」的另一个入口。
 * 挂到工单下会让人以为它接收 ticketId。</p>
 *
 * <p>权限上不额外限制 —— 任何登录用户都能用，且只能用自己身份去看自己的数据。
 * 这一点不靠注解保证，而是靠 {@link TicketAgentTools} 从令牌取身份、
 * 复用 {@code TicketService} 的可见性规则来保证。</p>
 */
@Tag(name = "智能助手", description = "用自然语言建单与查询工单进展")
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentController {

    private final AgentService agentService;
    private final LlmProperties llmProperties;

    @Operation(summary = "对话", description = "可识别建单意图并返回草稿；也可查询当前用户的工单进展")
    @PostMapping("/chat")
    public Result<AgentChatResponse> chat(@Valid @RequestBody AgentChatRequest request) {
        return Result.success(agentService.chat(request));
    }

    /**
     * 助手是否可用。
     *
     * <p><b>为什么要单独有这个接口</b>：没配 API Key 时，前端不该把入口亮出来 ——
     * 让用户点进去、输入一大段话、再收到「未配置」的报错，是很差的体验。
     * 前端进入工单页时探一次这个接口，据此决定显示还是隐藏入口。</p>
     *
     * <p>只返回布尔值，不回显 Key 本身，也不暴露 base-url 与模型名之外的配置细节。</p>
     */
    @Operation(summary = "助手可用性", description = "未配置 API Key 时返回 available=false，前端据此隐藏入口")
    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        return Result.success(Map.of(
                "available", llmProperties.isReady(),
                "model", llmProperties.isReady() ? llmProperties.getModel() : ""));
    }
}