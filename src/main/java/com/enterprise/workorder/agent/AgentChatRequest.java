package com.enterprise.workorder.agent;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 一次对话请求。
 *
 * <p><b>为什么历史消息由前端传、后端不存</b>：服务端存会话就要引入会话表、
 * 过期清理、以及"这个会话属于谁"的权限校验 —— 而本功能的核心价值是建单，
 * 多轮对话只是辅助。把历史放在前端内存里（刷新即清空），
 * 后端保持无状态，与 JWT 的设计取向一致。</p>
 *
 * <p>代价是历史可被客户端伪造。所以工具执行时<b>不能信任历史里的任何身份信息</b> ——
 * 身份只从令牌取，历史只是文本。</p>
 */
public record AgentChatRequest(

        @NotBlank(message = "请输入内容")
        @Size(max = 1000, message = "单条消息不能超过1000字")
        String message,

        /** 历史消息，按时间正序。可为空 */
        List<HistoryItem> history) {

    /**
     * 历史消息。
     *
     * @param role    user 或 assistant；system 一律拒绝，否则客户端可注入指令覆盖系统提示词
     * @param content 文本
     */
    public record HistoryItem(String role, String content) {
    }
}