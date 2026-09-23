package com.enterprise.workorder.agent;

/**
 * 智能建单的产物：一张尚未提交的草稿。
 *
 * <p><b>为什么产出的是草稿而不是直接建单</b>：模型会猜错 —— 把"不着急"听成紧急、
 * 把报销单归到 IT 类。若 Agent 直接落库并提交，错误的单子就已经进入审批流，
 * 需要有人去驳回、去作废，代价由别人承担。产出草稿则把最后一步交回用户：
 * 他看到的是一张填好的表单，可以改，可以放弃。AI 犯错时的爆炸半径因此被限制在
 * "多看一眼"这个量级。</p>
 *
 * <p><b>为什么带 confidence 与 reasoning</b>：模型对自己有多确定，只能由它自己说。
 * 界面据此决定"是否把类型选择框标黄提醒用户确认"。这不是炫技 ——
 * 没把握时明确说没把握，比给出一个笃定的错误答案更有用。</p>
 *
 * @param typeId      工单类型 id；为空表示没匹配上，由用户自己选
 * @param typeName    类型名，用于展示
 * @param title       标题
 * @param content     内容
 * @param priority    优先级，取值见 Priority 枚举
 * @param confidence  置信度 0~1
 * @param reasoning   判断依据，一句话，展示给用户看
 * @param warning     需要用户注意的问题，例如"类型未能确定，请手动选择"
 */
public record TicketDraft(
        Long typeId,
        String typeName,
        String title,
        String content,
        String priority,
        Double confidence,
        String reasoning,
        String warning) {
}