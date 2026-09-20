package com.enterprise.workorder.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.enterprise.workorder.common.BusinessException;
import com.enterprise.workorder.common.ResultCode;
import com.enterprise.workorder.dto.TicketCreateRequest;
import com.enterprise.workorder.dto.TicketQuery;
import com.enterprise.workorder.dto.TicketVO;
import com.enterprise.workorder.entity.ApprovalRecord;
import com.enterprise.workorder.entity.Ticket;
import com.enterprise.workorder.enums.ApprovalAction;
import com.enterprise.workorder.enums.RoleCode;
import com.enterprise.workorder.enums.TicketStatus;
import com.enterprise.workorder.mapper.ApprovalRecordMapper;
import com.enterprise.workorder.mapper.TicketMapper;
import com.enterprise.workorder.security.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 工单状态机行为测试。
 *
 * <p><b>为什么用真实数据库而不是 Mock Mapper</b>：
 * 本项目已出现过一类只有真实持久化层才能暴露的缺陷 —— 例如
 * {@code ticket.setCurrentApproverId(null)} 之后 MyBatis-Plus 默认
 * 更新策略 NOT_NULL 会把该字段从 UPDATE 语句中剔除，调用方毫无察觉，
 * 数据库里仍残留旧审批人。若把 Mapper Mock 掉，这类缺陷永远不会被发现。
 * 因此本测试连真实 MySQL，靠 @Transactional 在每个用例结束后回滚，不污染开发数据。</p>
 *
 * <p>用例中反复出现的 4 个账号来自 sql/schema.sql 的初始化数据，
 * 与生产数据结构一致，不做额外造数。</p>
 */
@SpringBootTest
@Transactional
@DisplayName("工单状态机")
class TicketStateMachineTest {

    // ---------- 初始化数据中的账号 ----------
    private static final Long UID_ADMIN = 1L;
    private static final Long UID_ZHANGSAN = 2L;
    private static final Long UID_LISI = 3L;
    private static final Long UID_WANGWU = 4L;

    /** 请假申请：审批链 DEPT_LEADER,ADMIN —— 两级 */
    private static final Long TYPE_LEAVE = 1L;
    /** IT报修：审批链 DEPT_LEADER —— 一级 */
    private static final Long TYPE_IT_REPAIR = 3L;

    @Autowired
    private TicketService ticketService;

    @Autowired
    private TicketMapper ticketMapper;

    @Autowired
    private ApprovalRecordMapper approvalRecordMapper;

    /** 直接发 SQL，仅用于制造测试前提（如强行把 created_at 改成同一秒） */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ==================================================================
    //  创建
    // ==================================================================

    @Nested
    @DisplayName("创建工单")
    class Create {

        @Test
        @DisplayName("创建的工单应为草稿状态，级次为 0，且单号格式正确")
        void shouldCreateDraftTicket() {
            loginAs(UidOf.ZHANGSAN);

            Ticket ticket = createDraft(TYPE_LEAVE);

            assertThat(ticket.getId()).isNotNull();
            assertThat(ticket.getStatus()).isEqualTo(TicketStatus.DRAFT.name());
            assertThat(ticket.getCurrentStep()).isZero();
            assertThat(ticket.getTotalStep()).isZero();
            assertThat(ticket.getCurrentApproverId()).isNull();
            assertThat(ticket.getCreatorId()).isEqualTo(UID_ZHANGSAN);
            // 单号形如 TK20260919-0001
            assertThat(ticket.getTicketNo()).matches("TK\\d{8}-\\d{4}");
        }

        @Test
        @DisplayName("部门应取自创建人所属部门，而非前端传入")
        void shouldTakeDepartmentFromCreator() {
            loginAs(UidOf.ZHANGSAN);

            Ticket ticket = createDraft(TYPE_LEAVE);

            // 张三属于技术部(id=2)
            assertThat(ticket.getDepartmentId()).isEqualTo(2L);
        }

        @Test
        @DisplayName("类型不存在时应拒绝")
        void shouldRejectUnknownType() {
            loginAs(UidOf.ZHANGSAN);

            assertThatThrownBy(() -> createDraft(999999L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("工单类型不存在或已停用");
        }

        @Test
        @DisplayName("优先级非法时应拒绝")
        void shouldRejectInvalidPriority() {
            loginAs(UidOf.ZHANGSAN);

            TicketCreateRequest request = new TicketCreateRequest();
            request.setTitle("优先级测试");
            request.setTypeId(TYPE_LEAVE);
            request.setPriority("SUPER_URGENT");

            assertThatThrownBy(() -> ticketService.create(request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("无效的优先级");
        }
    }

    // ==================================================================
    //  提交
    // ==================================================================

    @Nested
    @DisplayName("提交工单")
    class Submit {

        @Test
        @DisplayName("提交后应自动派给部门主管，级次 1/2")
        void shouldDispatchToDepartmentLeader() {
            loginAs(UidOf.ZHANGSAN);
            Ticket ticket = createDraft(TYPE_LEAVE);

            ticketService.submit(ticket.getId());

            Ticket after = reload(ticket.getId());
            assertThat(after.getStatus()).isEqualTo(TicketStatus.PENDING.name());
            assertThat(after.getCurrentStep()).isEqualTo(1);
            assertThat(after.getTotalStep()).isEqualTo(2);
            // 技术部主管是李四
            assertThat(after.getCurrentApproverId()).isEqualTo(UID_LISI);
            assertThat(after.getSubmittedAt()).isNotNull();
        }

        @Test
        @DisplayName("单级审批链的总级次应为 1")
        void shouldResolveSingleLevelFlow() {
            loginAs(UidOf.ZHANGSAN);
            Ticket ticket = createDraft(TYPE_IT_REPAIR);

            ticketService.submit(ticket.getId());

            Ticket after = reload(ticket.getId());
            assertThat(after.getTotalStep()).isEqualTo(1);
            assertThat(after.getCurrentApproverId()).isEqualTo(UID_LISI);
        }

        @Test
        @DisplayName("提交他人草稿应被拒绝")
        void shouldRejectSubmittingOthersDraft() {
            loginAs(UidOf.ZHANGSAN);
            Ticket ticket = createDraft(TYPE_LEAVE);

            loginAs(UidOf.LISI);

            assertThatThrownBy(() -> ticketService.submit(ticket.getId()))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getCode())
                    .isEqualTo(ResultCode.FORBIDDEN);
        }

        @Test
        @DisplayName("已提交的工单不能重复提交")
        void shouldRejectDoubleSubmit() {
            loginAs(UidOf.ZHANGSAN);
            Ticket ticket = createDraft(TYPE_LEAVE);
            ticketService.submit(ticket.getId());

            assertThatThrownBy(() -> ticketService.submit(ticket.getId()))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("不能提交");
        }

        @Test
        @DisplayName("提交应留下 step=0 的 SUBMIT 轨迹")
        void shouldWriteSubmitRecordAtStepZero() {
            loginAs(UidOf.ZHANGSAN);
            Ticket ticket = createDraft(TYPE_LEAVE);

            ticketService.submit(ticket.getId());

            List<ApprovalRecord> records = recordsOf(ticket.getId());
            assertThat(records).hasSize(1);
            assertThat(records.get(0).getStep()).isZero();
            assertThat(records.get(0).getAction()).isEqualTo(ApprovalAction.SUBMIT.name());
        }
    }

    // ==================================================================
    //  审批
    // ==================================================================

    @Nested
    @DisplayName("审批流转")
    class Approve {

        @Test
        @DisplayName("一级通过后应流转到二级管理员，级次变为 2/2")
        void shouldMoveToNextStep() {
            Ticket ticket = submitAsZhangssan(TYPE_LEAVE);

            loginAs(UidOf.LISI);
            ticketService.approve(ticket.getId(), ApprovalAction.APPROVE.name(), "同意");

            Ticket after = reload(ticket.getId());
            assertThat(after.getStatus()).isEqualTo(TicketStatus.PENDING.name());
            assertThat(after.getCurrentStep()).isEqualTo(2);
            assertThat(after.getCurrentApproverId()).isEqualTo(UID_ADMIN);
        }

        @Test
        @DisplayName("终审通过后状态为已通过，且待办人字段必须被清空")
        void shouldApproveAndClearApproverOnFinalStep() {
            Ticket ticket = submitAsZhangssan(TYPE_LEAVE);
            loginAs(UidOf.LISI);
            ticketService.approve(ticket.getId(), ApprovalAction.APPROVE.name(), "同意");

            loginAs(UidOf.ADMIN);
            ticketService.approve(ticket.getId(), ApprovalAction.APPROVE.name(), "批准");

            Ticket after = reload(ticket.getId());
            assertThat(after.getStatus()).isEqualTo(TicketStatus.APPROVED.name());
            // 回归点：修复前此处会残留 admin 的 id，
            // 原因是 MyBatis-Plus 默认 NOT_NULL 策略丢弃了 null 赋值。
            assertThat(after.getCurrentApproverId()).isNull();
            assertThat(after.getFinishedAt()).isNotNull();
        }

        @Test
        @DisplayName("驳回后状态为已驳回，且待办人字段必须被清空")
        void shouldRejectAndClearApprover() {
            Ticket ticket = submitAsZhangssan(TYPE_LEAVE);

            loginAs(UidOf.LISI);
            ticketService.approve(ticket.getId(), ApprovalAction.REJECT.name(), "预算不足");

            Ticket after = reload(ticket.getId());
            assertThat(after.getStatus()).isEqualTo(TicketStatus.REJECTED.name());
            assertThat(after.getCurrentApproverId()).isNull();
            assertThat(after.getFinishedAt()).isNotNull();
        }

        @Test
        @DisplayName("非当前待办人不得审批")
        void shouldRejectNonCurrentApprover() {
            Ticket ticket = submitAsZhangssan(TYPE_LEAVE);

            // 王五是人事部主管，不是本单待办人
            loginAs(UidOf.WANGWU);

            assertThatThrownBy(() -> ticketService.approve(
                    ticket.getId(), ApprovalAction.APPROVE.name(), "越权"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("不是该工单的当前审批人");
        }

        @Test
        @DisplayName("已办结的工单不得再次审批")
        void shouldRejectApproveOnFinalState() {
            Ticket ticket = submitAsZhangssan(TYPE_IT_REPAIR);
            loginAs(UidOf.LISI);
            ticketService.approve(ticket.getId(), ApprovalAction.APPROVE.name(), "同意");

            // 此时已是 APPROVED
            assertThatThrownBy(() -> ticketService.approve(
                    ticket.getId(), ApprovalAction.APPROVE.name(), "再审一次"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("不能审批");
        }

        @Test
        @DisplayName("审批动作只接受 APPROVE / REJECT")
        void shouldRejectUnsupportedAction() {
            Ticket ticket = submitAsZhangssan(TYPE_LEAVE);
            loginAs(UidOf.LISI);

            assertThatThrownBy(() -> ticketService.approve(ticket.getId(), "SUB", null))
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> ticketService.approve(ticket.getId(), "APPROVE_XX", null))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("每一级审批记录的 step 必须与该级次一致，不能错位")
        void shouldRecordCorrectStepForEachLevel() {
            Ticket ticket = submitAsZhangssan(TYPE_LEAVE);
            loginAs(UidOf.LISI);
            ticketService.approve(ticket.getId(), ApprovalAction.APPROVE.name(), "一级同意");
            loginAs(UidOf.ADMIN);
            ticketService.approve(ticket.getId(), ApprovalAction.APPROVE.name(), "二级批准");

            List<ApprovalRecord> records = recordsOf(ticket.getId());

            // 回归点：currentStep 曾先于写记录被改成下一级，导致轨迹整体错位一级
            assertThat(records).extracting(ApprovalRecord::getStep)
                    .containsExactly(0, 1, 2);
            assertThat(records).extracting(ApprovalRecord::getAction)
                    .containsExactly("SUBMIT", "APPROVE", "APPROVE");
            assertThat(records).extracting(ApprovalRecord::getApproverId)
                    .containsExactly(UID_ZHANGSAN, UID_LISI, UID_ADMIN);
            assertThat(records).extracting(ApprovalRecord::getComment)
                    .containsExactly(null, "一级同意", "二级批准");
        }

        @Test
        @DisplayName("审批记录应保存操作人姓名快照")
        void shouldSnapshotApproverName() {
            Ticket ticket = submitAsZhangssan(TYPE_LEAVE);
            loginAs(UidOf.LISI);
            ticketService.approve(ticket.getId(), ApprovalAction.APPROVE.name(), "同意");

            List<ApprovalRecord> records = recordsOf(ticket.getId());

            assertThat(records).extracting(ApprovalRecord::getApproverName)
                    .containsExactly("张三", "李四");
        }
    }

    // ==================================================================
    //  撤回与重新提交
    // ==================================================================

    @Nested
    @DisplayName("撤回与重新提交")
    class WithdrawAndResubmit {

        @Test
        @DisplayName("撤回后状态为已撤回，待办人字段必须被清空")
        void shouldWithdrawAndClearApprover() {
            Ticket ticket = submitAsZhangssan(TYPE_LEAVE);

            loginAs(UidOf.ZHANGSAN);
            ticketService.withdraw(ticket.getId());

            Ticket after = reload(ticket.getId());
            assertThat(after.getStatus()).isEqualTo(TicketStatus.WITHDRAWN.name());
            assertThat(after.getCurrentApproverId()).isNull();
            assertThat(after.getFinishedAt()).isNotNull();
        }

        @Test
        @DisplayName("非创建人不得撤回")
        void shouldRejectWithdrawByOthers() {
            Ticket ticket = submitAsZhangssan(TYPE_LEAVE);

            loginAs(UidOf.LISI);

            assertThatThrownBy(() -> ticketService.withdraw(ticket.getId()))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("只能撤回自己创建的工单");
        }

        @Test
        @DisplayName("草稿状态不能撤回")
        void shouldRejectWithdrawOnDraft() {
            loginAs(UidOf.ZHANGSAN);
            Ticket ticket = createDraft(TYPE_LEAVE);

            assertThatThrownBy(() -> ticketService.withdraw(ticket.getId()))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("不能撤回");
        }

        @Test
        @DisplayName("被驳回后重新提交，级次应重置为 1 并重新派单")
        void shouldResetStepOnResubmit() {
            Ticket ticket = submitAsZhangssan(TYPE_LEAVE);
            loginAs(UidOf.LISI);
            ticketService.approve(ticket.getId(), ApprovalAction.REJECT.name(), "不通过");

            // 驳回会写办结时间，重新提交时必须把它清回 null，
            // 否则工单会同时表现为"审批中"且"已办结"。
            assertThat(reload(ticket.getId()).getFinishedAt()).isNotNull();

            loginAs(UidOf.ZHANGSAN);
            ticketService.submit(ticket.getId());

            Ticket after = reload(ticket.getId());
            assertThat(after.getStatus()).isEqualTo(TicketStatus.PENDING.name());
            assertThat(after.getCurrentStep()).isEqualTo(1);
            assertThat(after.getTotalStep()).isEqualTo(2);
            assertThat(after.getCurrentApproverId()).isEqualTo(UID_LISI);
            assertThat(after.getFinishedAt()).isNull();
        }

        @Test
        @DisplayName("已驳回的工单不能被审批人继续审批")
        void rejectedTicketShouldNotBeApprovable() {
            Ticket ticket = submitAsZhangssan(TYPE_LEAVE);
            loginAs(UidOf.LISI);
            ticketService.approve(ticket.getId(), ApprovalAction.REJECT.name(), "不通过");

            assertThatThrownBy(() -> ticketService.approve(
                    ticket.getId(), ApprovalAction.APPROVE.name(), "反悔"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("不能审批");
        }
    }

    // ==================================================================
    //  可见性（含越权访问回归）
    // ==================================================================

    @Nested
    @DisplayName("工单可见性")
    class Visibility {

        @Test
        @DisplayName("当前待办人必须能查看工单详情")
        void currentApproverShouldSeeDetail() {
            Ticket ticket = submitAsZhangssan(TYPE_LEAVE);

            loginAs(UidOf.LISI);

            // 回归点：修复前此调用抛 403「无权查看该工单」，
            // 导致审批人点开自己的待办直接被拦住。
            TicketVO vo = ticketService.detail(ticket.getId());
            assertThat(vo.getId()).isEqualTo(ticket.getId());
            assertThat(vo.getCurrentApproverName()).isEqualTo("李四");
        }

        @Test
        @DisplayName("创建人与管理员可查看详情")
        void creatorAndAdminShouldSeeDetail() {
            Ticket ticket = submitAsZhangssan(TYPE_LEAVE);

            loginAs(UidOf.ZHANGSAN);
            assertThat(ticketService.detail(ticket.getId()).getId()).isEqualTo(ticket.getId());

            loginAs(UidOf.ADMIN);
            assertThat(ticketService.detail(ticket.getId()).getId()).isEqualTo(ticket.getId());
        }

        @Test
        @DisplayName("无关人员不得查看详情")
        void unrelatedUserShouldNotSeeDetail() {
            Ticket ticket = submitAsZhangssan(TYPE_LEAVE);

            loginAs(UidOf.WANGWU);

            assertThatThrownBy(() -> ticketService.detail(ticket.getId()))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("无权查看该工单");
        }

        @Test
        @DisplayName("无关人员不得查看审批轨迹")
        void unrelatedUserShouldNotSeeHistory() {
            Ticket ticket = submitAsZhangssan(TYPE_LEAVE);

            loginAs(UidOf.WANGWU);

            // 回归点：修复前该接口没有任何权限校验，传入 ID 即可读取全部审批意见
            assertThatThrownBy(() -> ticketService.history(ticket.getId()))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("无权查看该工单");
        }

        @Test
        @DisplayName("参与过审批的人可以回看自己经手的工单")
        void previousApproverShouldSeeDetail() {
            Ticket ticket = submitAsZhangssan(TYPE_LEAVE);
            loginAs(UidOf.LISI);
            ticketService.approve(ticket.getId(), ApprovalAction.APPROVE.name(), "同意");

            // 李四此时已不是待办人（待办人已变成管理员），但因留有审批记录仍可查看
            assertThat(ticketService.detail(ticket.getId()).getId()).isEqualTo(ticket.getId());
        }

        @Test
        @DisplayName("不存在的工单返回 404 语义")
        void shouldReportNotFound() {
            loginAs(UidOf.ZHANGSAN);

            assertThatThrownBy(() -> ticketService.detail(999999L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getCode())
                    .isEqualTo(ResultCode.NOT_FOUND);
        }
    }

    // ==================================================================
    //  查询范围
    // ==================================================================

    @Nested
    @DisplayName("查询范围")
    class Scope {

        @Test
        @DisplayName("todo 只返回待我审批且仍在审批中的工单")
        void todoShouldOnlyReturnPendingAssignedToMe() {
            Ticket ticket = submitAsZhangssan(TYPE_LEAVE);

            loginAs(UidOf.LISI);
            assertThat(ticketIds(query("todo"))).contains(ticket.getId());

            // 李四审批后，单据转到管理员，李四的待办应不再包含它
            ticketService.approve(ticket.getId(), ApprovalAction.APPROVE.name(), "同意");
            assertThat(ticketIds(query("todo"))).doesNotContain(ticket.getId());

            loginAs(UidOf.ADMIN);
            assertThat(ticketIds(query("todo"))).contains(ticket.getId());
        }

        @Test
        @DisplayName("办结后不得再出现在任何人的待办里")
        void finishedTicketShouldNotAppearInTodo() {
            Ticket ticket = submitAsZhangssan(TYPE_IT_REPAIR);
            loginAs(UidOf.LISI);
            ticketService.approve(ticket.getId(), ApprovalAction.APPROVE.name(), "同意");

            // 回归点：修复前 current_approver_id 残留导致已办结单据持续出现在视野中
            assertThat(ticketIds(query("todo"))).doesNotContain(ticket.getId());
        }

        @Test
        @DisplayName("mine 只返回我发起的工单")
        void mineShouldReturnOwnTickets() {
            Ticket ticket = submitAsZhangssan(TYPE_LEAVE);

            loginAs(UidOf.ZHANGSAN);
            assertThat(ticketIds(query("mine"))).contains(ticket.getId());

            loginAs(UidOf.WANGWU);
            assertThat(ticketIds(query("mine"))).doesNotContain(ticket.getId());
        }

        @Test
        @DisplayName("all 范围下，已办结工单不得因残留的审批人ID而留在原审批人视野中")
        void finishedTicketWithResidualApproverShouldNotLeakIntoAllScope() {
            // 模拟历史脏数据：工单已办结，但 current_approver_id 仍指向原审批人。
            // 这正是 default 分支附加 PENDING 状态条件要防的场景 —— 生产库中曾出现过 3 条这样的记录。
            Ticket ticket = submitAsZhangssan(TYPE_IT_REPAIR);
            loginAs(UidOf.LISI);
            ticketService.approve(ticket.getId(), ApprovalAction.APPROVE.name(), "同意");
            assertThat(reload(ticket.getId()).getStatus()).isEqualTo(TicketStatus.APPROVED.name());

            // 绕过 Service 直接把脏值写回，只更新这一列，避免波及其他字段
            ticketMapper.update(null, new LambdaUpdateWrapper<Ticket>()
                    .eq(Ticket::getId, ticket.getId())
                    .set(Ticket::getCurrentApproverId, UID_LISI));

            // 注意：scope 缺省值是 "mine"（我发起的），不是 "all"。
            // 脏数据泄漏只可能发生在 default 分支（即 scope=all），因此必须显式传 all 才有意义。
            loginAs(UidOf.LISI);
            assertThat(ticketIds(query("all"))).doesNotContain(ticket.getId());
        }

        @Test
        @DisplayName("管理员在 all 范围下可见全部工单")
        void adminShouldSeeAllInAllScope() {
            Ticket ticket = submitAsZhangssan(TYPE_LEAVE);

            loginAs(UidOf.ADMIN);

            assertThat(ticketIds(query("all"))).contains(ticket.getId());
        }

        @Test
        @DisplayName("scope 缺省时等价于 mine，只返回我发起的工单")
        void blankScopeShouldFallBackToMine() {
            Ticket ticket = submitAsZhangssan(TYPE_LEAVE);

            loginAs(UidOf.ZHANGSAN);
            assertThat(ticketIds(query(null))).contains(ticket.getId());

            loginAs(UidOf.WANGWU);
            assertThat(ticketIds(query(null))).doesNotContain(ticket.getId());
        }

        @Test
        @DisplayName("scope 为空字符串时同样等价于 mine")
        void emptyScopeShouldFallBackToMine() {
            Ticket ticket = submitAsZhangssan(TYPE_LEAVE);

            loginAs(UidOf.WANGWU);
            assertThat(ticketIds(query(""))).doesNotContain(ticket.getId());
        }

        @Test
        @DisplayName("分页排序稳定：同一秒创建的工单不会因排序不定而重行或漏行")
        void pagingShouldBeStableWhenCreatedAtTies() {
            // 回归的缺陷：分页只 ORDER BY created_at DESC。
            // created_at 是 DATETIME（秒精度），实测库里 54 行只有 17 个不同时间戳，
            // 最大一组 7 行同秒。MySQL 对排序值相等的行不保证顺序，
            // 于是第 1 页与第 2 页可能各自返回同一行（重行），另一行两页都不出现（漏行）。
            //
            // 修复：排序末尾补上主键作为决胜局（tie-breaker）。
            // 主键唯一，所以排序结果适得到一个全序，分页就稳定了。
            //
            // 为了真正造出“同秒”，这里直接把几张工单的 created_at 改成同一个时间。
            // 靠循环里快速创建去碰同秒是不可靠的（跑在不同机器上结果不同）。
            loginAs(UidOf.ZHANGSAN);
            List<Long> ids = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                ids.add(createDraft(TYPE_LEAVE).getId());
            }
            jdbcTemplate.update("UPDATE ticket SET created_at = '2020-01-01 00:00:00' WHERE id IN ("
                    + ids.stream().map(String::valueOf).collect(Collectors.joining(",")) + ")");

            // 逐页翻完整个 mine 列表，收集每页的行 id。
            // 若排序不稳定，同一个 id 会在不同页重复出现，而另一些 id 永远不出现。
            List<Long> seen = new ArrayList<>();
            long pageNo = 1;
            while (true) {
                TicketQuery q = new TicketQuery();
                q.setScope("mine");
                q.setPageNum((int) pageNo);
                q.setPageSize(2);
                IPage<TicketVO> page = ticketService.page(q);
                seen.addAll(ticketIds(page));
                if (pageNo >= page.getPages()) {
                    break;
                }
                pageNo++;
            }

            assertThat(seen).doesNotHaveDuplicates();
            assertThat(seen).containsAll(ids);
        }

        @Test
        @DisplayName("done 的筛选下推到数据库，不把审批记录全拉进内存")
        void doneFilterShouldBePushedDownToDatabase() {
            // 回归的缺陷：scope=done 原本是"先把该审批人的全部审批记录查出来，
            // 在 JVM 里去重得到 id 列表，再拼成 IN (...) 发给数据库"。
            // 审批记录只增不减，一个在岗三年的主管很容易攒到上万条，
            // 这些行会全部被读进内存，再拼成一个可能上千元素的 IN 列表。
            //
            // 现在改为 inSql 子查询，过滤完全在数据库完成。
            // 本用例不断言 SQL 文本（那样会把测试绑死到实现细节），
            // 只验证行为：只返回"我审过的"、且不含我只提交没审的单。
            Ticket mine = submitAsZhangssan(TYPE_LEAVE);

            loginAs(UidOf.LISI);
            ticketService.approve(mine.getId(), ApprovalAction.APPROVE.name(), "同意");

            // 李四自己发起并提交一张（应出现在 mine，不应出现在 done）
            loginAs(UidOf.LISI);
            Ticket own = createDraft(TYPE_IT_REPAIR);
            ticketService.submit(own.getId());

            List<Long> done = ticketIds(query("done"));
            List<Long> mineList = ticketIds(query("mine"));

            assertThat(done).contains(mine.getId());
            assertThat(done).doesNotContain(own.getId());
            assertThat(mineList).contains(own.getId());
            assertThat(mineList).doesNotContain(mine.getId());
        }

        @Test
        @DisplayName("done 子查询走索引，不是全表扫描")
        void doneSubqueryShouldUseApproverIndex() {
            // 上一条测试只能证明"返回的行对不对"，证明不了"查得快不快"。
            // 而缺陷 L 的本质就是性能，两种写法的返回值一模一样。
            // 所以这里直接看数据库的执行计划，断言索引真的被用上了。
            //
            // 实测修复前：type=ALL、possible_keys=NULL、key=NULL（全表扫描）
            // 实测修复后：type=range、key=idx_approver（命中索引）
            //
            // 这条断言不受数据量影响：MySQL 选索引不是"跑分一会儿看看快不快"，
            // 而是依据统计信息直接决定执行计划。表里哪怕只有几行，
            // 只要索引存在且可用，计划里就会出现它。反之索引不存在时，
            // 无论多少行都只能是 ALL。
            String plan = jdbcTemplate.queryForObject(
                    "EXPLAIN SELECT ticket_id FROM approval_record "
                            + "WHERE approver_id = 2 AND action IN ('APPROVE','REJECT')",
                    (rs, rowNum) -> rs.getString("key"));

            assertThat(plan)
                    .as("未命中 idx_approver，说明该索引缺失或未生效（需执行 sql/migration-20260920-02.sql）")
                    .isEqualTo("idx_approver");
        }

        @Test
        @DisplayName("done 返回我审批过的工单")
        void doneShouldReturnProcessedTickets() {
            Ticket ticket = submitAsZhangssan(TYPE_LEAVE);
            loginAs(UidOf.LISI);
            ticketService.approve(ticket.getId(), ApprovalAction.APPROVE.name(), "同意");

            assertThat(ticketIds(query("done"))).contains(ticket.getId());
        }
    }

    // ==================================================================
    //  测试辅助
    // ==================================================================

    /** 集中管理账号常量，避免测试方法里散落魔法数字 */
    static final class UidOf {
        static final long ADMIN = UID_ADMIN;
        static final long ZHANGSAN = UID_ZHANGSAN;
        static final long LISI = UID_LISI;
        static final long WANGWU = UID_WANGWU;

        private UidOf() {
        }
    }

    /** 以张三身份创建并提交一张工单，返回提交后的实体 */
    private Ticket submitAsZhangssan(Long typeId) {
        loginAs(UidOf.ZHANGSAN);
        Ticket ticket = createDraft(typeId);
        ticketService.submit(ticket.getId());
        return ticket;
    }

    private Ticket createDraft(Long typeId) {
        TicketCreateRequest request = new TicketCreateRequest();
        request.setTitle("单元测试工单");
        request.setContent("由 TicketStateMachineTest 创建，事务结束后回滚");
        request.setTypeId(typeId);
        request.setPriority("NORMAL");
        return ticketService.create(request);
    }

    /** 从数据库重新读取，确保断言的是持久化结果而非内存对象 */
    private Ticket reload(Long ticketId) {
        return ticketMapper.selectById(ticketId);
    }

    private List<ApprovalRecord> recordsOf(Long ticketId) {
        return ticketService.history(ticketId);
    }

    private IPage<TicketVO> query(String scope) {
        TicketQuery q = new TicketQuery();
        q.setScope(scope);
        q.setPageSize(200);
        return ticketService.page(q);
    }

    private List<Long> ticketIds(IPage<TicketVO> page) {
        return page.getRecords().stream().map(TicketVO::getId).toList();
    }

    /**
     * 把当前登录人写入 SecurityContext，模拟 JWT 过滤器完成认证后的状态。
     * 直接操作 SecurityContextHolder 而不 Mock SecurityUtils，
     * 是为了让被测代码走与运行时完全相同的取值路径。
     */
    private void loginAs(long userId) {
        String username = switch ((int) userId) {
            case 1 -> "admin";
            case 2 -> "zhangsan";
            case 3 -> "lisi";
            default -> "wangwu";
        };
        String role = switch ((int) userId) {
            case 1 -> RoleCode.ADMIN.name();
            case 2 -> RoleCode.EMPLOYEE.name();
            default -> RoleCode.APPROVER.name();
        };

        LoginUser user = new LoginUser(userId, username, username, role, null);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
}