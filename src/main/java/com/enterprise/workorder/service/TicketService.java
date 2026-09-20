package com.enterprise.workorder.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.enterprise.workorder.dto.TicketCreateRequest;
import com.enterprise.workorder.dto.TicketQuery;
import com.enterprise.workorder.dto.TicketUpdateRequest;
import com.enterprise.workorder.dto.TicketVO;
import com.enterprise.workorder.entity.ApprovalRecord;
import com.enterprise.workorder.entity.Ticket;

import java.util.List;

/**
 * 工单服务。
 *
 * <p><b>状态流转的唯一入口</b>：工单状态只能经由本接口的方法改变，
 * 任何地方都不允许直接 {@code ticket.setStatus(...)} 后 updateById。
 * 否则审批轨迹会与实际状态脱节，事后无法复盘"这张单到底谁批的"。</p>
 */
public interface TicketService {

    /** 创建工单（草稿） */
    Ticket create(TicketCreateRequest request);

    /** 提交工单，进入审批流。DRAFT / REJECTED / WITHDRAWN 状态可用 */
    void submit(Long ticketId);

    /** 审批：通过或驳回。驳回必须填审批意见 */
    void approve(Long ticketId, String action, String comment);

    /** 撤回自己提交的工单。仅 PENDING 状态可用 */
    void withdraw(Long ticketId);

    /**
     * 修改工单内容。仅创建人本人、且仅草稿 / 已驳回 / 已撤回状态可改。
     *
     * <p>驳回后必须能改 —— 否则"驳回"这个动作毫无意义：申请人既不知道要改什么，
     * 也无法改，只能原样再提交一次，审批人只能再驳一次。</p>
     */
    TicketVO update(Long ticketId, TicketUpdateRequest request);

    /** 删除草稿。仅创建人本人、且仅从未提交过的草稿可删 */
    void delete(Long ticketId);

    /** 作废工单。仅创建人本人、且仅草稿 / 已驳回 / 已撤回状态可作废 */
    void cancel(Long ticketId, String comment);

    /** 分页查询工单 */
    IPage<TicketVO> page(TicketQuery query);

    /** 工单详情 */
    TicketVO detail(Long ticketId);

    /** 工单的审批轨迹，按发生顺序返回 */
    List<ApprovalRecord> history(Long ticketId);
}
