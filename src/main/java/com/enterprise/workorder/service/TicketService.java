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

    /**
     * 导出前的准备：校验查询条件，并统计符合条件的总行数。
     *
     * <p>之所以要单独有一个"统计"步骤，是因为 Excel 的截断提示必须写在表头下方、
     * 数据上方，而 SXSSF 流式写出的行无法再回头插入 —— 所以"是否被截断"必须在
     * 开始写第一行之前就知道。</p>
     *
     * <p>同时这一步也是导出接口的"校验关口"：查询条件非法（如 scope 拼错）会在这里抛异常。
     * 此时调用方还没有碰 HttpServletResponse 的输出流，错误能被正常转成 JSON 返回。</p>
     */
    long countForExport(TicketQuery query);

    /**
     * 取一批导出数据（游标翻页）。
     *
     * <p><b>为什么不用 IPage 翻页</b>：{@code LIMIT 100000, 1000} 这种深分页，
     * MySQL 要先扫过并丢弃前 100000 行，越翻越慢，是典型的 O(n²)。
     * 游标法（{@code WHERE id < 上一批最后一个 id}）每批都直接走主键区间，
     * 耗时恒定，且不会因为"翻页期间有新数据插入"而漏行或重复。</p>
     *
     * @param lastId    上一批最后一行的 id；首批传 null
     * @param batchSize 每批行数
     */
    List<TicketVO> exportBatch(TicketQuery query, Long lastId, int batchSize);
}
