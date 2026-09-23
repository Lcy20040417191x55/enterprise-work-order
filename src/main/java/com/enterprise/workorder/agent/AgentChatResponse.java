package com.enterprise.workorder.agent;

import java.util.List;

/**
 * 一次对话的响应。
 *
 * @param reply     助手的自然语言回答，直接展示
 * @param draft     若这一轮识别出建单意图，携带可填充的草稿；否则为 null
 * @param toolsUsed 这一轮实际调用过的工具名，仅用于调试与前端展示"我做了什么"
 */
public record AgentChatResponse(String reply, TicketDraft draft, List<String> toolsUsed) {
}