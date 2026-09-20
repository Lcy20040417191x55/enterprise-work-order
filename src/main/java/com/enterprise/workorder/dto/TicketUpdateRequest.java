package com.enterprise.workorder.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改工单内容的请求体。
 *
 * <p><b>为什么不复用 {@link TicketCreateRequest}</b>：那个类给 priority 写了默认值
 * {@code = "NORMAL"}。放在创建场景是对的（不填就是普通优先级），但放在修改场景
 * 会造成静默覆盖 —— 调用方只想改标题、priority 传了 null，反序列化后却变成 "NORMAL"，
 * 于是原有的 URGENT 被悄悄改掉，调用方与用户都察觉不到。
 * 因此修改用的 DTO 里 priority 保持可为 null，null 表示"这一项不动"。</p>
 */
@Data
public class TicketUpdateRequest {

    @NotBlank(message = "标题不能为空")
    @Size(max = 128, message = "标题长度不能超过128")
    private String title;

    /** 允许传 null 或空串，表示清空内容 */
    @Size(max = 5000, message = "内容长度不能超过5000")
    private String content;

    @NotNull(message = "请选择工单类型")
    private Long typeId;

    /** 为 null 表示保持原优先级不变 */
    private String priority;
}
