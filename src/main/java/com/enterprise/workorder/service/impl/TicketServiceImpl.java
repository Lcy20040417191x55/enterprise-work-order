package com.enterprise.workorder.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.enterprise.workorder.common.BusinessException;
import com.enterprise.workorder.common.ResultCode;
import com.enterprise.workorder.dto.TicketCreateRequest;
import com.enterprise.workorder.dto.TicketQuery;
import com.enterprise.workorder.dto.TicketUpdateRequest;
import com.enterprise.workorder.dto.TicketVO;
import com.enterprise.workorder.entity.ApprovalRecord;
import com.enterprise.workorder.entity.Department;
import com.enterprise.workorder.entity.SysUser;
import com.enterprise.workorder.entity.Ticket;
import com.enterprise.workorder.entity.TicketType;
import com.enterprise.workorder.enums.ApprovalAction;
import com.enterprise.workorder.enums.Priority;
import com.enterprise.workorder.enums.RoleCode;
import com.enterprise.workorder.enums.TicketStatus;
import com.enterprise.workorder.mapper.ApprovalRecordMapper;
import com.enterprise.workorder.mapper.DepartmentMapper;
import com.enterprise.workorder.mapper.TicketMapper;
import com.enterprise.workorder.mapper.TicketNoSeqMapper;
import com.enterprise.workorder.mapper.TicketTypeMapper;
import com.enterprise.workorder.security.LoginUser;
import com.enterprise.workorder.security.SecurityUtils;
import com.enterprise.workorder.service.ApprovalFlowResolver;
import com.enterprise.workorder.service.NotificationService;
import com.enterprise.workorder.service.TicketService;
import com.enterprise.workorder.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 工单核心服务：状态机的唯一实现处。
 *
 * <p><b>设计约定</b></p>
 * <ol>
 *   <li>所有状态流转只能经由本类的方法发生，Controller 不得直接改 status。</li>
 *   <li>每次流转都要写一条 approval_record，保证轨迹完整可追溯。</li>
 *   <li>"处于某状态时能不能做某事"的判断委托给 {@link TicketStatus}，
 *       本类只负责"做"，不重复定义规则。</li>
 *   <li>凡是会改状态的方法，都要先 {@link #requireTicketForUpdate} 拿行锁。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TicketServiceImpl implements TicketService {

    private static final DateTimeFormatter NO_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * 单号前缀的日期格式。
     *
     * <p>这里曾经有一个 {@code private static final Object NO_GENERATOR_LOCK}，
     * 用来给"读最大单号 + 生成下一个"加进程内锁。它已被删除 ——
     * 因为那把锁既锁不住多实例，也锁不住事务：{@code synchronized} 块在方法返回时就释放了，
     * 而 INSERT 要到事务提交才落库，中间仍有一个可被其他请求插入的窗口。
     * 详见 {@link #generateTicketNo()}。</p>
     */

    // ---- 查询范围常量。用常量集合而非散落的字面量，避免拼写不一致 ----
    private static final String SCOPE_MINE = "mine";
    private static final String SCOPE_TODO = "todo";
    private static final String SCOPE_DONE = "done";
    private static final String SCOPE_ALL = "all";
    private static final List<String> VALID_SCOPES =
            List.of(SCOPE_MINE, SCOPE_TODO, SCOPE_DONE, SCOPE_ALL);

    /** 状态枚举名，用于报错时提示可选值 */
    private static final List<String> STATUS_NAMES =
            Arrays.stream(TicketStatus.values()).map(Enum::name).toList();

    /** 审批意见长度上限，与 approval_record.comment 的列宽以及 DTO 上的 @Size 保持一致 */
    private static final int COMMENT_MAX_LENGTH = 500;

    private final TicketMapper ticketMapper;
    private final TicketNoSeqMapper ticketNoSeqMapper;
    private final TicketTypeMapper ticketTypeMapper;
    private final ApprovalRecordMapper approvalRecordMapper;
    private final DepartmentMapper departmentMapper;
    private final UserService userService;
    private final ApprovalFlowResolver approvalFlowResolver;

    /**
     * 通知服务。
     *
     * <p>注意这里注入的不是 Mapper 而是 Service：写通知要判断收件人，
     * 收件人规则（创建人 + 当前待办人 + 历史审批人，去重且排除评论人）
     * 只应该存在于通知服务内部。若工单服务自己拼收件人列表，规则就会有两份。</p>
     */
    private final NotificationService notificationService;

    // ==================================================================
    //  创建
    // ==================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Ticket create(TicketCreateRequest request) {
        LoginUser current = SecurityUtils.getLoginUser();

        TicketType type = requireEnabledType(request.getTypeId());

        String priority = StringUtils.hasText(request.getPriority())
                ? request.getPriority().trim().toUpperCase(Locale.ROOT)
                : Priority.NORMAL.name();
        requireValidPriority(priority);

        SysUser creator = userService.getById(current.getUserId());

        Ticket ticket = new Ticket();
        ticket.setTicketNo(generateTicketNo());
        ticket.setTitle(request.getTitle().trim());
        ticket.setContent(request.getContent());
        ticket.setTypeId(type.getId());
        ticket.setPriority(priority);
        ticket.setStatus(TicketStatus.DRAFT.name());
        ticket.setCreatorId(current.getUserId());
        ticket.setDepartmentId(creator == null ? null : creator.getDepartmentId());
        ticket.setCurrentStep(0);
        ticket.setTotalStep(0);

        ticketMapper.insert(ticket);
        log.info("工单已创建: {} by {}", ticket.getTicketNo(), current.getUsername());
        return ticket;
    }

    // ==================================================================
    //  提交：DRAFT / REJECTED / WITHDRAWN -> PENDING
    // ==================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void submit(Long ticketId) {
        LoginUser current = SecurityUtils.getLoginUser();
        Ticket ticket = requireTicketForUpdate(ticketId);
        requireCreator(ticket, current, "只能提交自己创建的工单");

        TicketStatus status = parseStatus(ticket.getStatus());
        if (!status.isSubmittable()) {
            throw new BusinessException("当前状态为「" + status.getLabel() + "」，不能提交");
        }

        TicketType type = requireEnabledType(ticket.getTypeId());
        List<Long> chain = approvalFlowResolver.resolveChain(
                type.getApprovalFlow(), ticket.getDepartmentId(), ticket.getCreatorId());

        // 驳回后重新提交，级次归 1 重新走。注意 totalStep 用的是实际生效的链长，
        // 不是配置里的级数 —— 详情见 ApprovalFlowResolver#resolveChain 的说明。
        ticket.setStatus(TicketStatus.PENDING.name());
        ticket.setCurrentStep(1);
        ticket.setTotalStep(chain.size());
        ticket.setCurrentApproverId(chain.get(0));
        ticket.setSubmittedAt(LocalDateTime.now());
        ticket.setFinishedAt(null);
        ticketMapper.updateById(ticket);

        writeRecord(ticket, 0, current, ApprovalAction.SUBMIT, null);

        // 通知当前待办人。放在 writeRecord 之后，是为了让审批轨迹先落库 ——
        // 两者同事务，顺序不影响最终一致性，但若通知插入抛异常（例如列宽越界），
        // 日志里能先看到轨迹已写入，排查时更容易定位是通知环节出的问题。
        notificationService.notifyTodo(ticket.getId(), chain.get(0),
                ticket.getTicketNo(), ticket.getTitle(), "由 " + displayName(current) + " 提交");

        log.info("工单 {} 已提交，共 {} 级审批，当前审批人 {}",
                ticket.getTicketNo(), chain.size(), chain.get(0));
    }

    // ==================================================================
    //  审批：PENDING -> PENDING(下一级) / APPROVED / REJECTED
    // ==================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void approve(Long ticketId, String action, String comment) {
        LoginUser current = SecurityUtils.getLoginUser();
        Ticket ticket = requireTicketForUpdate(ticketId);

        TicketStatus status = parseStatus(ticket.getStatus());
        if (!status.isApprovable()) {
            throw new BusinessException("当前状态为「" + status.getLabel() + "」，不能审批");
        }

        // 关键鉴权：只有当前待办人本人可以审批。
        // 这一条同时挡住了"部门主管审批自己提交的单" —— 自审规避后，
        // 主管自建的单根本不会把 current_approver_id 指向他本人。
        if (!current.getUserId().equals(ticket.getCurrentApproverId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "您不是该工单的当前审批人");
        }

        ApprovalAction approvalAction = parseApprovalAction(action);
        String trimmedComment = trimToNull(comment);

        // 驳回必须说明理由：否则申请人只知道"被拒了"，不知道改哪里。
        // 这是业务规则，放在 Service 而不是靠前端校验 —— 接口是公开的，
        // 前端校验挡不住直接调接口的调用方。
        if (approvalAction == ApprovalAction.REJECT && trimmedComment == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "驳回工单时必须填写驳回理由");
        }

        // 记录本次审批人所处的级次。必须先捕获：通过后 currentStep 会被改成下一级，
        // 若此时再读 currentStep，审批轨迹会整体错位一级。
        int approverStep = ticket.getCurrentStep();

        if (approvalAction == ApprovalAction.REJECT) {
            ticket.setStatus(TicketStatus.REJECTED.name());
            ticket.setCurrentApproverId(null);
            ticket.setFinishedAt(LocalDateTime.now());
            ticketMapper.updateById(ticket);

            writeRecord(ticket, approverStep, current, ApprovalAction.REJECT, trimmedComment);
            notificationService.notifyRejected(ticket.getId(), ticket.getCreatorId(),
                    ticket.getTicketNo(), ticket.getTitle(), trimmedComment);
            log.info("工单 {} 被 {} 驳回", ticket.getTicketNo(), current.getUsername());
            return;
        }

        // 通过：判断是否还有下一级
        boolean lastStep = ticket.getCurrentStep() >= ticket.getTotalStep();
        if (lastStep) {
            ticket.setStatus(TicketStatus.APPROVED.name());
            ticket.setCurrentApproverId(null);
            ticket.setFinishedAt(LocalDateTime.now());
            ticketMapper.updateById(ticket);
            notificationService.notifyApproved(ticket.getId(), ticket.getCreatorId(),
                    ticket.getTicketNo(), ticket.getTitle());
            log.info("工单 {} 审批完成，全部通过", ticket.getTicketNo());
        } else {
            int nextStep = ticket.getCurrentStep() + 1;
            // 这里用 requireType 而不是 requireEnabledType：类型被管理员停用，
            // 不该把已经在流程里的单子卡死。停用只影响"还能不能新建这类单"。
            TicketType type = requireType(ticket.getTypeId());
            List<Long> chain = approvalFlowResolver.resolveChain(
                    type.getApprovalFlow(), ticket.getDepartmentId(), ticket.getCreatorId());

            // 审批中途主数据可能变了（部门换主管、管理员被停用），链长因此可能与提交时不同。
            // 按"当前链"取下一级，取不到就说明配置改坏了；不静默放行，否则会产生无人可审的僵尸工单。
            if (nextStep > chain.size()) {
                throw new BusinessException("工单类型「" + type.getName() + "」的审批链已变更，"
                        + "第 " + nextStep + " 级不存在，请联系管理员核查后再审批");
            }
            ticket.setCurrentStep(nextStep);
            ticket.setCurrentApproverId(chain.get(nextStep - 1));
            // 链可能因主数据变化而变短，totalStep 要跟着修正，否则详情页会显示"第 3/2 级"
            ticket.setTotalStep(chain.size());
            ticketMapper.updateById(ticket);
            // 流转到下一级时通知新的待办人。上一级审批人不需要通知 ——
            // 他自己刚点的通过，不需要系统再告诉他"你点过了"。
            notificationService.notifyTodo(ticket.getId(), ticket.getCurrentApproverId(),
                    ticket.getTicketNo(), ticket.getTitle(), "已通过上一级审批");
            log.info("工单 {} 第 {} 级通过，流转至第 {} 级，审批人 {}",
                    ticket.getTicketNo(), nextStep - 1, nextStep, ticket.getCurrentApproverId());
        }

        writeRecord(ticket, approverStep, current, ApprovalAction.APPROVE, trimmedComment);
    }

    // ==================================================================
    //  撤回：PENDING -> WITHDRAWN
    // ==================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void withdraw(Long ticketId) {
        LoginUser current = SecurityUtils.getLoginUser();
        Ticket ticket = requireTicketForUpdate(ticketId);
        requireCreator(ticket, current, "只能撤回自己创建的工单");

        TicketStatus status = parseStatus(ticket.getStatus());
        if (!status.isWithdrawable()) {
            throw new BusinessException("当前状态为「" + status.getLabel() + "」，不能撤回");
        }

        ticket.setStatus(TicketStatus.WITHDRAWN.name());
        ticket.setCurrentApproverId(null);
        ticket.setFinishedAt(LocalDateTime.now());
        ticketMapper.updateById(ticket);

        writeRecord(ticket, ticket.getCurrentStep(), current, ApprovalAction.WITHDRAW, null);
        log.info("工单 {} 已撤回", ticket.getTicketNo());
    }

    // ==================================================================
    //  修改：仅 DRAFT / REJECTED / WITHDRAWN
    // ==================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TicketVO update(Long ticketId, TicketUpdateRequest request) {
        LoginUser current = SecurityUtils.getLoginUser();
        Ticket ticket = requireTicketForUpdate(ticketId);
        requireCreator(ticket, current, "只能修改自己创建的工单");

        TicketStatus status = parseStatus(ticket.getStatus());
        if (!status.isEditable()) {
            throw new BusinessException("当前状态为「" + status.getLabel() + "」，不能修改内容。"
                    + "只有草稿、已驳回、已撤回的工单可以修改");
        }

        TicketType type = requireEnabledType(request.getTypeId());

        String priority = StringUtils.hasText(request.getPriority())
                ? request.getPriority().trim().toUpperCase(Locale.ROOT)
                : null;
        if (priority != null) {
            requireValidPriority(priority);
        }

        // 刻意不改 currentStep / totalStep / finishedAt：
        // 已驳回的单子保留"第几级驳的、什么时候驳的"反而更有用，
        // 申请人能看到上一轮是在哪一步被卡住的。
        // 这些字段由 submit() 在重新提交时统一重置，职责不重叠。
        ticket.setTitle(request.getTitle().trim());
        ticket.setContent(request.getContent());
        ticket.setTypeId(type.getId());
        if (priority != null) {
            ticket.setPriority(priority);
        }
        ticketMapper.updateById(ticket);

        log.info("工单 {} 内容已修改 by {}", ticket.getTicketNo(), current.getUsername());
        return toVO(ticket);
    }

    // ==================================================================
    //  删除：仅草稿
    // ==================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long ticketId) {
        LoginUser current = SecurityUtils.getLoginUser();
        Ticket ticket = requireTicketForUpdate(ticketId);
        requireCreator(ticket, current, "只能删除自己创建的工单");

        TicketStatus status = parseStatus(ticket.getStatus());
        if (!status.isDeletable()) {
            throw new BusinessException("当前状态为「" + status.getLabel() + "」，不能删除。"
                    + "只有尚未提交过的草稿可以删除");
        }

        // 走逻辑删除（@TableLogic），记录仍留在库里，便于事后追溯"这条草稿存在过"。
        // MyBatis-Plus 会自动把 deleteById 翻译成 UPDATE ticket SET deleted=1。
        ticketMapper.deleteById(ticketId);
        log.info("草稿 {} 已删除 by {}", ticket.getTicketNo(), current.getUsername());
    }

    // ==================================================================
    //  作废：DRAFT / REJECTED / WITHDRAWN -> CLOSED
    // ==================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long ticketId, String comment) {
        LoginUser current = SecurityUtils.getLoginUser();
        Ticket ticket = requireTicketForUpdate(ticketId);
        requireCreator(ticket, current, "只能作废自己创建的工单");

        TicketStatus status = parseStatus(ticket.getStatus());
        if (!status.isCancelable()) {
            throw new BusinessException("当前状态为「" + status.getLabel() + "」，不能作废。"
                    + "审批中的工单请使用撤回");
        }

        ticket.setStatus(TicketStatus.CLOSED.name());
        ticket.setCurrentApproverId(null);
        ticket.setFinishedAt(LocalDateTime.now());
        ticketMapper.updateById(ticket);

        writeRecord(ticket, ticket.getCurrentStep(), current, ApprovalAction.CANCEL, trimToNull(comment));
        log.info("工单 {} 已作废 by {}", ticket.getTicketNo(), current.getUsername());
    }

    // ==================================================================
    //  查询
    // ==================================================================

    @Override
    public IPage<TicketVO> page(TicketQuery query) {
        LoginUser current = SecurityUtils.getLoginUser();
        LambdaQueryWrapper<Ticket> wrapper = buildFilter(query, current);

        // 必须补一个 id 作为次序键。只按 created_at 排序是不稳定的：
        // 同一秒内创建的工单 created_at 完全相同（实测库里 54 行只有 17 个不同的时间戳），
        // MySQL 对并列行的返回顺序不做任何保证，且翻页时两次查询可能给出不同顺序 ——
        // 表现为"某条工单在第 1 页和第 2 页各出现一次，另一条一条都没出现过"。
        // id 自增且唯一，追加它之后排序就有了确定的全序。
        wrapper.orderByDesc(Ticket::getCreatedAt).orderByDesc(Ticket::getId);

        Page<Ticket> page = new Page<>(query.getPageNum(), query.getPageSize());
        IPage<Ticket> result = ticketMapper.selectPage(page, wrapper);
        // 用批量版本转换：先一次性查出整页需要的关联数据，再逐行填充。
        // 单个 toVO 对每行要查 4 次（类型/部门/创建人/审批人），20 行的页面就是 80 条 SQL。
        return mapPage(result);
    }

    @Override
    public long countForExport(TicketQuery query) {
        LoginUser current = SecurityUtils.getLoginUser();
        Long total = ticketMapper.selectCount(buildFilter(query, current));
        return total == null ? 0L : total;
    }

    /**
     * 导出按 <b>id 倒序</b> 游标翻页，与列表页的 {@code created_at DESC, id DESC} 有意保持"近似但不必严格一致"。
     *
     * <p><b>为什么游标只用 id 一列，而不用 (created_at, id) 复合列</b>：
     * 游标法的正确性依赖"排序键唯一且单调"，这样 {@code WHERE 排序键 < 上批末值} 才既不漏也不重。
     * created_at 大量重复（实测库里 54 行的 created_at 只有 17 个不同取值），
     * 拿它做游标就必须写成 {@code created_at < ? OR (created_at = ? AND id < ?)}，
     * 而且 ticket 表在 created_at 上没有索引，每一批都要对整个符合条件的集合做 filesort，
     * 批数一多就退化成 O(n²)。id 是自增主键，唯一、单调、天然有聚簇索引，
     * 用它做游标每批都是主键区间扫描，代价恒定。</p>
     *
     * <p>代价是：若多实例部署且服务器时钟不同步，id 顺序可能与 created_at 顺序略有出入，
     * 导出的行序和屏幕上看到的可能不完全相同。对"导出清单"这个用途而言，这是可接受的取舍。</p>
     */
    @Override
    public List<TicketVO> exportBatch(TicketQuery query, Long lastId, int batchSize) {
        LoginUser current = SecurityUtils.getLoginUser();
        LambdaQueryWrapper<Ticket> wrapper = buildFilter(query, current);
        if (lastId != null) {
            wrapper.lt(Ticket::getId, lastId);
        }
        wrapper.orderByDesc(Ticket::getId);
        // batchSize 是服务端常量（不是请求参数），不存在注入面。
        // 这里必须用 last() 追加 LIMIT：若改用 Page 对象，分页插件会额外跑一次
        // COUNT 查询，而我们在 countForExport 里已经统计过了。
        wrapper.last("LIMIT " + batchSize);

        return toVOList(ticketMapper.selectList(wrapper));
    }

    @Override
    public TicketVO detail(Long ticketId) {
        Ticket ticket = requireTicket(ticketId);
        requireViewPermission(ticket);
        return toVO(ticket);
    }

    /**
     * 只做可见性校验，不组装详情。
     *
     * <p>评论、后续可能的附件/操作日志都要挂在"能看这张工单"这个前提上。
     * 与其让每个调用方各自复制 {@code requireViewPermission} 的逻辑，
     * 不如把校验本身暴露出去；规则仍然只有 {@link #requireViewPermission} 一份实现。</p>
     */
    @Override
    public void requireVisible(Long ticketId) {
        requireViewPermission(requireTicket(ticketId));
    }

    /**
     * 取工单 + 验可见性。
     *
     * <p>抽出来的动机很具体：附件列表要展示每一行"你能不能删"，
     * 而这取决于工单状态。若不提供本方法，调用方只能 {@code detail(ticketId)} 再取 status，
     * 而 detail 会顺带把所有名字映射、部门、类型都查一遍 —— 为了一个 status 字段
     * 付出一次完整详情组装的代价，纯属浪费。</p>
     */
    @Override
    public Ticket getVisibleTicket(Long ticketId) {
        Ticket ticket = requireTicket(ticketId);
        requireViewPermission(ticket);
        return ticket;
    }

    /**
     * 取工单 + 校验"当前人可以往这张单上增删附件"。
     *
     * <p>校验顺序刻意是"先粗后细"：可见性 -> 状态 -> 参与人身份。
     * 顺序换过来（先判断"我是不是参与人"）会造成信息泄露：
     * 对一个无权查看这张单的人，错误提示会从"无权查看"变成"你不是参与人"，
     * 后者等于确认了"这张单确实存在、而且有个当前待办人"。</p>
     *
     * <p><b>为什么参与人是"创建人 + 当前待办人"两方</b>：审批过程中补材料是双向的 ——
     * 申请人补发票，审批人回传一份签批扫描件。少了任何一方，另一方就只能走线下渠道，
     * 而线下流转的材料不会留在工单里，复盘时等于没有。</p>
     */
    @Override
    public Ticket requireAttachable(Long ticketId) {
        Ticket ticket = getVisibleTicket(ticketId);
        TicketStatus status = parseStatus(ticket.getStatus());
        if (!status.isAttachable()) {
            throw new BusinessException("工单" + status.getLabel() + "，不能增删附件");
        }
        LoginUser current = SecurityUtils.getLoginUser();
        Long uid = current.getUserId();
        if (!ticket.getCreatorId().equals(uid) && !uid.equals(ticket.getCurrentApproverId())) {
            // 走到这里说明我能看到这张单（例如我审批过、或者是管理员），
            // 但我既不是申请人也不是当前待办人 —— 此时不该再往里加东西
            throw new BusinessException(ResultCode.FORBIDDEN,
                    "只有工单创建人和当前审批人可以增删附件");
        }
        return ticket;
    }

    /**
     * 取当前登录人的显示名。
     *
     * <p>通知里的"{谁}提交了工单"需要一个稳定的名字。优先用 sys_user.real_name，
     * 查不到（用户被删或数据异常）时回落到登录名，最差也不会返回 null 让文案变成
     * "null 提交了工单"。</p>
     */
    private String displayName(LoginUser current) {
        SysUser user = userService.getById(current.getUserId());
        return user != null && StringUtils.hasText(user.getRealName())
                ? user.getRealName()
                : current.getUsername();
    }

    @Override
    public List<ApprovalRecord> history(Long ticketId) {
        Ticket ticket = requireTicket(ticketId);
        requireViewPermission(ticket);
        // 按主键（插入顺序）排序，不能按 step 排。
        // 反例：一张单被驳回后重新提交，轨迹是 0:SUBMIT / 1:REJECT / 0:SUBMIT。
        // 若先按 step 升序，两条 step=0 会被排到一起，显示成
        // "提交、提交、驳回" —— 轨迹与实际发生顺序不符，复盘时会误判。
        // 主键自增，天然等于发生顺序，且同一事务内多条记录的先后也准确。
        return approvalRecordMapper.selectList(new LambdaQueryWrapper<ApprovalRecord>()
                .eq(ApprovalRecord::getTicketId, ticketId)
                .orderByAsc(ApprovalRecord::getId));
    }

    // ==================================================================
    //  内部方法
    // ==================================================================

    /**
     * 构造"筛选条件"部分（不含排序、不含分页）。
     *
     * <p>抽成独立方法是为了让列表查询与导出共用同一份条件构造逻辑。
     * 若导出另写一份，两边迟早会分叉 —— 例如列表加了"只看某状态"的过滤，
     * 导出忘了加，用户就会觉得"我明明筛选了，导出来却是全部"，
     * 而且这类不一致没有任何编译期或测试能自动发现。</p>
     *
     * <p>权限过滤（{@link #applyScope}）也在其中，因此导出的数据范围
     * 与用户在列表里能看到的完全一致，不可能靠导出接口越权拿到别人的单。</p>
     */
    private LambdaQueryWrapper<Ticket> buildFilter(TicketQuery query, LoginUser current) {
        LambdaQueryWrapper<Ticket> wrapper = new LambdaQueryWrapper<>();
        applyScope(wrapper, query.getScope(), current);

        if (StringUtils.hasText(query.getStatus())) {
            wrapper.eq(Ticket::getStatus, requireValidStatus(query.getStatus()));
        }
        if (query.getTypeId() != null) {
            wrapper.eq(Ticket::getTypeId, query.getTypeId());
        }
        if (StringUtils.hasText(query.getKeyword())) {
            String kw = query.getKeyword().trim();
            wrapper.and(w -> w.like(Ticket::getTitle, kw).or().like(Ticket::getTicketNo, kw));
        }
        return wrapper;
    }

    /**
     * 按查询范围过滤。
     * <ul>
     *   <li>mine —— 我发起的</li>
     *   <li>todo —— 我的待办（待我审批且仍在审批中）</li>
     *   <li>done —— 我处理过的</li>
     *   <li>all  —— 管理员可见全部，普通用户只能看与自己相关的</li>
     * </ul>
     */
    private void applyScope(LambdaQueryWrapper<Ticket> wrapper, String scope, LoginUser current) {
        String s = StringUtils.hasText(scope) ? scope.trim().toLowerCase(Locale.ROOT) : SCOPE_MINE;
        if (!VALID_SCOPES.contains(s)) {
            // 静默回落到 default 会让"scope 拼错"表现为"返回了一堆意料之外的单据"，
            // 这类问题最难排查，宁可直接报错
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "无效的 scope: " + scope + "，可选值为 " + String.join(" / ", VALID_SCOPES));
        }
        switch (s) {
            case SCOPE_MINE -> wrapper.eq(Ticket::getCreatorId, current.getUserId());
            case SCOPE_TODO -> wrapper
                    .eq(Ticket::getCurrentApproverId, current.getUserId())
                    .eq(Ticket::getStatus, TicketStatus.PENDING.name());
            case SCOPE_DONE -> {
                // "我审批过的单"。这里刻意用子查询，而不是"先把全部 ticket_id 查出来再拼 IN"。
                // 后者有两个会随数据量恶化的问题：该审批人的所有审批记录先被读进 JVM 内存，
                // 再拼成一个可能上千个元素的 IN 列表发给数据库。审批记录只增不减，
                // 一个在岗三年的主管很容易攒到上万条。
                //
                // inSql 直接拼接字符串，看起来像注入风险，但两个被拼接的值都不是用户输入：
                // userId 来自已验签的 JWT 且类型是 Long，action 是枚举常量名。
                // 换句话说，这里没有任何一个字节来自请求参数。
                wrapper.inSql(Ticket::getId,
                        "SELECT ticket_id FROM approval_record WHERE approver_id = " + current.getUserId()
                                + " AND action <> '" + ApprovalAction.SUBMIT.name() + "'");
            }
            default -> {
                if (!RoleCode.ADMIN.name().equals(current.getRoleCode())) {
                    // 注意 current_approver_id 只在 PENDING 期间才有"待办人"含义。
                    // 若不附加状态条件，已办结的工单会因其残留的 approver_id 继续出现在
                    // 原审批人的列表里，表现为"办过的单一直挂在待办附近"。
                    Long uid = current.getUserId();
                    wrapper.and(w -> w.eq(Ticket::getCreatorId, uid)
                            .or(o -> o.eq(Ticket::getCurrentApproverId, uid)
                                    .eq(Ticket::getStatus, TicketStatus.PENDING.name())));
                }
            }
        }
    }

    /**
     * 工单可见性规则。满足以下任一条件即可查看详情与审批轨迹：
     * <ul>
     *   <li>创建人本人</li>
     *   <li>管理员</li>
     *   <li><b>当前待办人</b> —— 待办人必须能打开单子，否则点击待办直接 403</li>
     *   <li>参与过该工单审批的人（可回看自己经手的单）</li>
     * </ul>
     */
    private void requireViewPermission(Ticket ticket) {
        LoginUser current = SecurityUtils.getLoginUser();
        Long uid = current.getUserId();

        if (ticket.getCreatorId().equals(uid)
                || RoleCode.ADMIN.name().equals(current.getRoleCode())
                || uid.equals(ticket.getCurrentApproverId())
                || hasApprovalRecord(ticket.getId(), uid)) {
            return;
        }
        throw new BusinessException(ResultCode.FORBIDDEN, "无权查看该工单");
    }

    private boolean hasApprovalRecord(Long ticketId, Long userId) {
        return approvalRecordMapper.exists(new LambdaQueryWrapper<ApprovalRecord>()
                .eq(ApprovalRecord::getTicketId, ticketId)
                .eq(ApprovalRecord::getApproverId, userId));
    }

    /**
     * 校验操作人就是工单创建人。
     *
     * <p>把"比对创建人 + 抛 403"抽成一句，是因为 update / delete / cancel / submit / withdraw
     * 五个入口都需要它。这种全校验最容易在新增入口时被漏掉 —— 漏了就是越权改别人的单。</p>
     */
    private void requireCreator(Ticket ticket, LoginUser current, String message) {
        if (!ticket.getCreatorId().equals(current.getUserId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, message);
        }
    }

    /**
     * 取一个启用中的工单类型。
     *
     * <p>停用的类型不允许再被使用，但已经用它建好的老工单还要能正常审批完 ——
     * 所以这里只在"新建 / 提交 / 修改"时校验，审批流转时不看 enabled。</p>
     */
    private TicketType requireEnabledType(Long typeId) {
        TicketType type = typeId == null ? null : ticketTypeMapper.selectById(typeId);
        if (type == null || type.getEnabled() == null || type.getEnabled() != 1) {
            throw new BusinessException("工单类型不存在或已停用");
        }
        return type;
    }

    /** 只校验存在性，不要求启用。用于"流程进行中"的场景 */
    private TicketType requireType(Long typeId) {
        TicketType type = typeId == null ? null : ticketTypeMapper.selectById(typeId);
        if (type == null) {
            throw new BusinessException("工单类型不存在或已删除，请联系管理员");
        }
        return type;
    }

    private void writeRecord(Ticket ticket, Integer step, LoginUser operator,
                             ApprovalAction action, String comment) {
        ApprovalRecord record = new ApprovalRecord();
        record.setTicketId(ticket.getId());
        record.setStep(step);
        record.setApproverId(operator.getUserId());
        // 姓名快照：用户改名后历史记录仍显示当时的名字
        SysUser user = userService.getById(operator.getUserId());
        record.setApproverName(user == null ? operator.getUsername() : user.getRealName());
        record.setAction(action.name());
        record.setComment(comment);
        approvalRecordMapper.insert(record);
    }

    /** 只读场景取工单，不加锁 */
    private Ticket requireTicket(Long ticketId) {
        Ticket ticket = ticketId == null ? null : ticketMapper.selectById(ticketId);
        if (ticket == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "工单不存在");
        }
        return ticket;
    }

    /**
     * 写场景取工单，加行锁串行化同一张单的并发操作。
     *
     * <p>必须配合事务使用：锁随事务提交或回滚释放。若在事务外调用，
     * SELECT ... FOR UPDATE 会因 autocommit 立即释放锁，等于没加。
     * 本类所有写方法都标了 @Transactional，满足该前提。</p>
     */
    private Ticket requireTicketForUpdate(Long ticketId) {
        Ticket ticket = ticketId == null ? null : ticketMapper.selectByIdForUpdate(ticketId);
        if (ticket == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "工单不存在");
        }
        return ticket;
    }

    private TicketStatus parseStatus(String status) {
        if (!StringUtils.hasText(status)) {
            throw new BusinessException("工单状态为空，数据异常，请联系管理员");
        }
        try {
            return TicketStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException("工单状态异常: " + status);
        }
    }

    /**
     * 校验并归一化状态过滤值。大小写不敏感，与 scope 的处理保持一致 ——
     * 两处一个敏感一个不敏感会让调用方反复踩坑。
     */
    private String requireValidStatus(String status) {
        try {
            return TicketStatus.valueOf(status.trim().toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "无效的工单状态: " + status + "，可选值为 " + String.join(" / ", STATUS_NAMES));
        }
    }

    private void requireValidPriority(String priority) {
        try {
            Priority.valueOf(priority);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("无效的优先级: " + priority);
        }
    }

    private ApprovalAction parseApprovalAction(String action) {
        if (!StringUtils.hasText(action)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "审批动作不能为空");
        }
        ApprovalAction result;
        try {
            result = ApprovalAction.valueOf(action.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException("不支持的审批动作: " + action);
        }
        if (result != ApprovalAction.APPROVE && result != ApprovalAction.REJECT) {
            throw new BusinessException("审批动作只能是 APPROVE 或 REJECT");
        }
        return result;
    }

    /**
     * 归一化审批意见：去空白，全空白视为没填。
     *
     * <p>不这么做的话，前端传一个空格的 comment 就能绕过"驳回必须填理由"的校验，
     * 而库里会存下一条看起来有、实际没内容的意见。</p>
     */
    private String trimToNull(String comment) {
        if (comment == null) {
            return null;
        }
        String trimmed = comment.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > COMMENT_MAX_LENGTH) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "审批意见长度不能超过" + COMMENT_MAX_LENGTH);
        }
        return trimmed;
    }

    /**
     * 生成单号，格式 TK20260918-0001，取当天序号 +1。
     *
     * <p><b>这里修的是一个真实故障，不是理论风险。</b>原实现是
     * {@code SELECT MAX(ticket_no) ... + 1}，外面套一把 JVM 内的锁。它隐含两个前提，
     * 而这两个前提在并发下都不成立：</p>
     * <ol>
     *   <li>"读到最大值"与"INSERT 落库"之间没有别人插入。JVM 锁在方法返回时就释放了，
     *       而 INSERT 要等所在事务提交才真正写进库 —— 锁根本没覆盖到关键区间。</li>
     *   <li>只有一个 JVM。多实例部署时每个实例各有一把自己的锁，互相看不见。</li>
     * </ol>
     * <p>后果是多个请求算出同一个序号，第二个插入撞上唯一索引 uk_ticket_no 报
     * DuplicateKeyException，接口直接 500。实测：20 个并发创建请求，只有 3 个成功，
     * 其余 17 个全部 500。</p>
     *
     * <p><b>现在把序号交给数据库。</b>ticket_no_seq 表每天一行，
     * 用 {@code INSERT ... ON DUPLICATE KEY UPDATE current_val = current_val + 1} 自增。
     * 该语句会对这一行加排他锁，且该锁<b>一直持有到事务提交</b> —— 锁的存活期
     * 第一次与"INSERT 落库"对齐了，并发请求因此被串行化，各自拿到不同序号。
     * 锁在数据库而不在 JVM，多实例部署同样正确。</p>
     *
     * <p><b>为什么随后的 SELECT 能读到正确的值</b>：同一事务用的是同一条数据库连接，
     * 读得到自己未提交的修改。因此本方法<b>必须在事务中调用</b>（create 上已标
     * {@code @Transactional}）；若在事务外调用，锁会立即释放，两个并发请求可能读到同一个值，
     * 又回到原来的故障。</p>
     *
     * <p><b>代价</b>：同一瞬间的工单创建被串行化，一次只处理一个。
     * 工单创建是低频操作，这点排队时间远小于"撞唯一索引失败"的代价。
     * 若某天创建量真的上来了，正确做法是改号段模式（一次取 100 个号在内存里分），
     * 而不是把锁改回 JVM 内。</p>
     */
    private String generateTicketNo() {
        LocalDate today = LocalDate.now();
        String prefix = "TK" + today.format(NO_DATE_FMT);

        ticketNoSeqMapper.takeNextSeq(today);
        Integer seq = ticketNoSeqMapper.currentSeq(today);
        if (seq == null) {
            // 走到这里说明刚 upsert 完却读不到，只可能是 schema 没同步（表被改名/删掉）。
            // 与其拼出一个可能重复的单号，不如直接失败并说清原因。
            throw new BusinessException("单号序列读取失败，请确认 ticket_no_seq 表已创建（见 sql/schema.sql）");
        }

        // 序号超过 4 位时 %04d 不会截断，会自然变长（10000 -> "10000"）。
        // 单号列宽 VARCHAR(32)，当天订单量要到千万级才可能撑破，无需额外处理。
        return prefix + "-" + String.format("%04d", seq);
    }

    /**
     * 单张工单转 VO。
     *
     * <p>只用于详情这类"就一条"的场景。列表请走 {@link #mapPage}，
     * 否则每行 4 次关联查询（类型、部门、创建人、当前审批人），
     * 20 行的页面就要发 80 条 SQL —— 单看功能没问题，数据量一上来就崩。</p>
     */
    private TicketVO toVO(Ticket ticket) {
        // 复用批量实现：单条也是"长度为 1 的批量"，避免两套转换逻辑各自演进后字段漏填。
        return toVOList(List.of(ticket)).get(0);
    }

    /**
     * 分页结果字段映射，关联名称一次性批量补齐。
     *
     * <p><b>为什么必须在这里做而不是逐行 toVO</b>：MyBatis-Plus 的 {@code IPage.convert}
     * 是逐元素回调，回调里再查库就是典型的 N+1。分页接口是列表页，行数由用户决定，
     * N+1 的代价随页大小线性增长，是性能问题的重灾区。</p>
     */
    private IPage<TicketVO> mapPage(IPage<Ticket> result) {
        List<TicketVO> vos = toVOList(result.getRecords());
        Page<TicketVO> converted = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        converted.setRecords(vos);
        return converted;
    }

    /**
     * 批量转 VO：先把整页涉及的关联数据各查一次，再在内存里填充。
     *
     * <p>查询次数从 4N 降到最多 4：类型、部门、用户（创建人 + 审批人合并查一次）。</p>
     */
    private List<TicketVO> toVOList(List<Ticket> tickets) {
        if (tickets == null || tickets.isEmpty()) {
            return List.of();
        }

        // 收集这一页所有需要的外部主键。用 Set 去重：
        // 一页里多数工单的部门和类型往往是重复的，去重后 IN 列表更短。
        Set<Long> typeIds = new HashSet<>();
        Set<Long> departmentIds = new HashSet<>();
        Set<Long> userIds = new HashSet<>();
        for (Ticket t : tickets) {
            addIfNotNull(typeIds, t.getTypeId());
            addIfNotNull(departmentIds, t.getDepartmentId());
            addIfNotNull(userIds, t.getCreatorId());
            addIfNotNull(userIds, t.getCurrentApproverId());
        }

        // 空集合必须挡在门外：selectBatchIds 收到空集合会生成 "WHERE id IN ()"，
        // MySQL 直接语法报错。这一页若所有工单都没有部门（department_id 全为 null），
        // 就会踩到 —— 属于那种"上线很久才偶然触发"的坑。
        Map<Long, String> typeNames = typeIds.isEmpty() ? Map.of() : toNameMap(
                ticketTypeMapper.selectBatchIds(typeIds), TicketType::getId, TicketType::getName);
        Map<Long, String> deptNames = departmentIds.isEmpty() ? Map.of() : toNameMap(
                departmentMapper.selectBatchIds(departmentIds), Department::getId, Department::getName);
        Map<Long, String> userNames = toNameMap(
                userService.listByIds(userIds), SysUser::getId, SysUser::getRealName);

        List<TicketVO> result = new ArrayList<>(tickets.size());
        for (Ticket ticket : tickets) {
            result.add(toVO(ticket, typeNames, deptNames, userNames));
        }
        return result;
    }

    private void addIfNotNull(Set<Long> target, Long value) {
        if (value != null) {
            target.add(value);
        }
    }

    /** 把实体列表转成 id -> 名称 的映射，取不到的键自然缺席 */
    private <T> Map<Long, String> toNameMap(List<T> entities,
                                            java.util.function.Function<T, Long> idGetter,
                                            java.util.function.Function<T, String> nameGetter) {
        Map<Long, String> map = new HashMap<>();
        for (T entity : entities) {
            Long id = idGetter.apply(entity);
            if (id != null) {
                map.put(id, nameGetter.apply(entity));
            }
        }
        return map;
    }

    /** 从映射里取名称；查不到就是 null，不抛异常，避免脏外键把列表接口打挂 */
    private String nameOf(Map<Long, String> map, Long id) {
        return id == null ? null : map.get(id);
    }

    /**
     * 实体转 VO（关联名称由调用方预先批量查好）。
     */
    private TicketVO toVO(Ticket ticket,
                          Map<Long, String> typeNames,
                          Map<Long, String> deptNames,
                          Map<Long, String> userNames) {
        TicketVO vo = new TicketVO();
        vo.setId(ticket.getId());
        vo.setTicketNo(ticket.getTicketNo());
        vo.setTitle(ticket.getTitle());
        vo.setContent(ticket.getContent());
        vo.setTypeId(ticket.getTypeId());
        vo.setTypeName(nameOf(typeNames, ticket.getTypeId()));
        vo.setPriority(ticket.getPriority());
        vo.setStatus(ticket.getStatus());
        vo.setCreatorId(ticket.getCreatorId());
        vo.setCreatorName(nameOf(userNames, ticket.getCreatorId()));
        vo.setDepartmentId(ticket.getDepartmentId());
        vo.setDepartmentName(nameOf(deptNames, ticket.getDepartmentId()));
        vo.setCurrentStep(ticket.getCurrentStep());
        vo.setTotalStep(ticket.getTotalStep());
        vo.setCurrentApproverId(ticket.getCurrentApproverId());
        vo.setCurrentApproverName(nameOf(userNames, ticket.getCurrentApproverId()));
        vo.setSubmittedAt(ticket.getSubmittedAt());
        vo.setFinishedAt(ticket.getFinishedAt());
        vo.setCreatedAt(ticket.getCreatedAt());

        // 枚举展示名，取不到就原样返回，不让脏数据把接口打挂
        vo.setStatusLabel(safeLabel(ticket.getStatus()));
        vo.setPriorityLabel(safeLabel(ticket.getPriority()));

        applyAllowedActions(vo, ticket);
        return vo;
    }

    /**
     * 把状态机允许的动作标记写进 VO，让前端不必自己重写一套状态判断。
     *
     * <p>状态值来自数据库，理论上是干净的；但若遇到脏值（例如人工改库写了个拼错的
     * 状态），{@code parseStatus} 会抛异常，列表接口整体 500 —— 一条脏数据打挂一整页。
     * 因此这里走宽松版解析 {@link #parseStatusOrNull}：状态解析不了就全部标 false（按钮都不给点），
     * 让运维能从"所有按钮都没了"这个现象反查到脏数据，而不是整页打不开。</p>
     */
    private void applyAllowedActions(TicketVO vo, Ticket ticket) {
        TicketStatus status = parseStatusOrNull(ticket.getStatus());
        if (status == null) {
            log.warn("工单 {} 的状态 {} 无法识别，所有操作按钮将不可用",
                    ticket.getTicketNo(), ticket.getStatus());
            vo.setEditable(false);
            vo.setDeletable(false);
            vo.setSubmittable(false);
            vo.setWithdrawable(false);
            vo.setCancelable(false);
            vo.setApprovable(false);
            vo.setCurrentApprover(false);
            return;
        }

        vo.setEditable(status.isEditable());
        vo.setDeletable(status.isDeletable());
        vo.setSubmittable(status.isSubmittable());
        vo.setWithdrawable(status.isWithdrawable());
        vo.setCancelable(status.isCancelable());
        vo.setApprovable(status.isApprovable());

        // 待办人判断要与"能审批"分开：只有两者同时为真，前端才该显示审批按钮
        LoginUser current = SecurityUtils.getLoginUserOrNull();
        vo.setCurrentApprover(current != null
                && current.getUserId().equals(ticket.getCurrentApproverId()));
    }

    /** 宽松版 parseStatus：解析不了返回 null 而不是抛异常，供只读展示场景使用 */
    private TicketStatus parseStatusOrNull(String status) {
        if (!StringUtils.hasText(status)) {
            return null;
        }
        try {
            return TicketStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 枚举值转中文展示名；遇到未知值原样返回，避免脏数据导致接口异常。
     *
     * <p><b>为什么遍历枚举而不写 switch 列举字面量</b>：枚举值名称会被复制到这里，
     * 新增状态时编译器不会提醒你漏了一处，表现为"新状态的工单在列表里显示英文原名"。
     * 遍历 values() 则天然覆盖全部常量，无需维护。</p>
     */
    private String safeLabel(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        for (TicketStatus status : TicketStatus.values()) {
            if (status.name().equals(value)) {
                return status.getLabel();
            }
        }
        for (Priority priority : Priority.values()) {
            if (priority.name().equals(value)) {
                return priority.getLabel();
            }
        }
        return value;
    }
}
