package com.enterprise.workorder.agent;

import com.enterprise.workorder.entity.TicketType;
import com.enterprise.workorder.security.LoginUser;
import com.enterprise.workorder.security.SecurityUtils;
import com.enterprise.workorder.service.TicketTypeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 工单智能助手。
 *
 * <p>它做三件事：把一句话整理成工单草稿、查当前用户的工单进展、回答与工单有关的问题。
 * 实现方式是「工具调用循环」，也就是模型自己决定要不要查数据、查什么，</p>
 *
 * <pre>
 *   用户提问 -> 模型（决定调用哪个工具）
 *                 |-- 要调工具 -> 执行 -> 结果回灌 -> 再问一次模型 --+
 *                 |                                              |
 *                 +---------------- 模型直接给出回答 &lt;-----------+
 * </pre>
 *
 * <p><b>为什么不是「让模型输出 JSON，后端解析」</b>：那条路要在提示词里反复叮嘱
 * 「只输出 JSON，不要解释」，而模型时不时会加上一句「好的，这是结果：」，
 * 于是解析失败。改成工具调用后，格式由 schema 约束，是协议层的事，
 * 不依赖模型的自觉 —— 这是官方推荐的用法。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {

    private final LlmClient llmClient;
    private final LlmProperties properties;
    private final TicketAgentTools tools;
    private final TicketTypeService ticketTypeService;

    /**
     * 系统提示词。
     *
     * <p>三条约束的由来：</p>
     * <ul>
     *   <li><b>类型清单动态注入</b> —— 不能把类型名写死在提示词里。
     *       管理员在后台新加一个类型，写死的话助手永远选不到它，
     *       而且没有任何报错，只是「怎么问都匹配不上」。</li>
     *   <li><b>不承诺已提交</b> —— 助手只能生成草稿。若不明确告知，
     *       模型会用「已为您提交」这种听起来很顺的话回答，
     *       用户以为单据已经在走了，实际上还躺在表单里等他点提交。</li>
     *   <li><b>不编造数据</b> —— 查不到就说查不到。模型的默认行为是补全，
     *       在工单系统里「编一个不存在的审批人」比「说不知道」有害得多。</li>
     * </ul>
     */
    private String systemPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("你是企业工单与审批系统里的助手。你可以帮用户做三件事：\n")
                .append("1. 把用户描述的一件事整理成工单草稿（调用 draftTicket）；\n")
                .append("2. 查询用户自己的工单进展（调用 queryMyTickets）；\n")
                .append("3. 查看某一张具体工单的详情、审批轨迹与被驳回原因（调用 getTicketDetail）。\n")
                .append("   当用户提到「那张单」「我上次提的」这类指向一张具体工单的说法时用它，"
                        + "先按单号或关键词查，查到后据实回答，不要替用户推测单号。\n\n")
                .append("必须遵守：\n")
                .append("- 你只能生成草稿，不能提交工单。不要说「已提交」「已受理」，\n")
                .append("  要说「已为你填好，请确认后提交」。\n")
                .append("- 只能查询当前登录用户有权查看的工单。查不到就如实说没有，不要推测或编造。\n")
                .append("- 判断工单类型时，只能从下面列出的类型里选，使用它的 code，不要自己发明编码。\n")
                .append("  若没有合适的类型，就把 typeCode 留空并在回答里说明，让用户自己选。\n")
                .append("- 优先级要克制：只有用户明确表达急迫（急、尽快、影响工作）时才用 HIGH 或 URGENT。\n")
                .append("- 回答用中文，简洁，不要罗列你自己调用了哪些工具。\n\n");

        List<TicketType> types = ticketTypeService.listEnabled();
        if (types.isEmpty()) {
            sb.append("【当前没有启用中的工单类型】\n")
                    .append("这种情况下不要生成草稿，直接告知用户需要先让管理员配置工单类型。\n");
        } else {
            sb.append("【可用工单类型】\n");
            for (TicketType t : types) {
                sb.append("- code=").append(t.getCode())
                        .append("  名称=").append(t.getName());
                if (StringUtils.hasText(t.getDescription())) {
                    sb.append("  说明=").append(t.getDescription());
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * 处理一轮对话。
     *
     * <p><b>登录人在这里取一次，之后全程用它</b>：工具执行期间不允许更换身份。
     * 这一点由 {@code SecurityUtils} 基于 SecurityContext 保证 ——
     * Agent 的执行没有被切到别的线程，因此上下文是同一个。</p>
     */
    public AgentChatResponse chat(AgentChatRequest request) {
        LoginUser current = SecurityUtils.getLoginUser();
        log.debug("智能助手请求: user={}, message={}", current.getUsername(), request.message());

        List<LlmClient.Message> messages = new ArrayList<>();
        messages.add(LlmClient.Message.system(systemPrompt()));

        // 历史消息按"只信文本、不信身份"的原则转接。
        // role 只允许 user/assistant：客户端若能塞 system 消息，就等同于能改写系统提示词。
        if (request.history() != null) {
            List<AgentChatRequest.HistoryItem> history = request.history();
            int from = Math.max(0, history.size() - properties.getMaxHistoryMessages());
            for (AgentChatRequest.HistoryItem item : history.subList(from, history.size())) {
                if (item == null || !StringUtils.hasText(item.content())) {
                    continue;
                }
                String role = item.role() == null ? "" : item.role().trim().toLowerCase();
                if ("user".equals(role)) {
                    messages.add(LlmClient.Message.user(item.content()));
                } else if ("assistant".equals(role)) {
                    messages.add(LlmClient.Message.assistant(item.content()));
                }
                // 其它 role 一律丢弃
            }
        }
        messages.add(LlmClient.Message.user(request.message()));

        List<LlmClient.ToolSpec> specs = tools.specs();
        List<String> used = new ArrayList<>();
        TicketDraft draft = null;

        // 工具调用循环。上限 maxToolRounds 是必须的：模型有可能在
        // listTicketTypes 与 queryMyTickets 之间反复调用不收敛，
        // 没有上限时这个请求会一直占着线程直到超时。
        for (int round = 0; round < properties.getMaxToolRounds(); round++) {
            LlmClient.ChatResult result = llmClient.chat(messages, specs, null);

            if (!result.hasToolCalls()) {
                String reply = StringUtils.hasText(result.content())
                        ? result.content()
                        : "我没太理解，可以再具体描述一下要办的事吗？";
                return new AgentChatResponse(reply, draft, used);
            }

            messages.add(LlmClient.Message.assistantToolCalls(result.toolCalls()));

            for (LlmClient.ToolCall call : result.toolCalls()) {
                used.add(call.name());
                String toolResult = tools.execute(call.name(), call.arguments());
                if (TicketAgentTools.TOOL_DRAFT_TICKET.equals(call.name())) {
                    // 草稿可能被调用多次（模型自我修正），以最后一次为准
                    TicketDraft parsed = tools.parseDraft(toolResult);
                    if (parsed != null) {
                        draft = parsed;
                    }
                }
                messages.add(LlmClient.Message.tool(call.id(), toolResult));
            }
        }

        // 轮次用尽仍未收敛：不能把中间状态直接抛给用户，
        // 因为可能已经拿到了草稿，那部分结果是有价值的
        log.warn("智能助手工具调用轮次达到上限 {}", properties.getMaxToolRounds());
        String reply = draft != null
                ? "已为你整理好草稿，请确认后提交。"
                : "这个问题我处理起来有点绕，能换个说法再描述一次吗？";
        return new AgentChatResponse(reply, draft, used);
    }
}