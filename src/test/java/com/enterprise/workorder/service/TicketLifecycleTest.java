package com.enterprise.workorder.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.enterprise.workorder.common.BusinessException;
import com.enterprise.workorder.common.ResultCode;
import com.enterprise.workorder.dto.TicketCreateRequest;
import com.enterprise.workorder.dto.TicketQuery;
import com.enterprise.workorder.dto.TicketUpdateRequest;
import com.enterprise.workorder.dto.TicketVO;
import com.enterprise.workorder.entity.ApprovalRecord;
import com.enterprise.workorder.entity.Ticket;
import com.enterprise.workorder.enums.ApprovalAction;
import com.enterprise.workorder.enums.TicketStatus;
import com.enterprise.workorder.mapper.TicketMapper;
import com.enterprise.workorder.support.AuthTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 工单生命周期测试：修改、删除、作废、重新提交，以及轨迹顺序与动作标记。
 *
 * <p><b>为什么单开一个文件而不是塞进 TicketStateMachineTest</b>：那个文件聚焦
 * "提交 -> 审批"的主流程；本文件聚焦"申请人自己手上的动作"（改 / 删 / 撤 / 废）与
 * 列表展示层。两者关注点不同，混在一起后单文件会超过千行，改一处测试要通读全文。</p>
 *
 * <p>连真实 MySQL，靠 @Transactional 回滚，不污染开发数据。</p>
 */
@SpringBootTest
@Transactional
@DisplayName("工单生命周期")
class TicketLifecycleTest {

    private static final long UID_ADMIN = 1L;
    private static final long UID_ZHANGSAN = 2L;
    private static final long UID_LISI = 3L;
    private static final long UID_WANGWU = 4L;

    /** 请假申请：DEPT_LEADER,ADMIN —— 两级 */
    private static final long TYPE_LEAVE = 1L;
    /** IT报修：DEPT_LEADER —— 一级 */
    private static final long TYPE_IT_REPAIR = 3L;

    @Autowired
    private TicketService ticketService;

    @Autowired
    private TicketMapper ticketMapper;

    @AfterEach
    void tearDown() {
        AuthTestSupport.logout();
    }

    // ==================================================================
    //  修改
    // ==================================================================

    @Nested
    @DisplayName("修改内容")
    class Update {

        @Test
        @DisplayName("草稿可改")
        void draftIsEditable() {
            Ticket ticket = createDraft(TYPE_LEAVE);

            TicketVO vo = ticketService.update(ticket.getId(), updateRequest("新标题", TYPE_LEAVE));

            assertThat(vo.getTitle()).isEqualTo("新标题");
            assertThat(reload(ticket.getId()).getTitle()).isEqualTo("新标题");
        }

        @Test
        @DisplayName("被驳回后可改 —— 否则驳回这个动作毫无意义")
        void rejectedIsEditable() {
            Ticket ticket = submit(TYPE_LEAVE);
            rejectAsLisi(ticket.getId());

            // 驳回是李四做的动作，改内容必须切回创建人张三
            loginAs(UID_ZHANGSAN);
            ticketService.update(ticket.getId(), updateRequest("按意见改好", TYPE_LEAVE));

            assertThat(reload(ticket.getId()).getTitle()).isEqualTo("按意见改好");
        }

        @Test
        @DisplayName("已撤回后可改 —— 撤回的本意就是「我要改改再交」")
        void withdrawnIsEditable() {
            Ticket ticket = submit(TYPE_LEAVE);
            withdraw(ticket.getId());

            ticketService.update(ticket.getId(), updateRequest("撤回后改标题", TYPE_LEAVE));

            assertThat(reload(ticket.getId()).getTitle()).isEqualTo("撤回后改标题");
        }

        @Test
        @DisplayName("审批中不可改：审批人正在看的内容不能被悄悄替换")
        void pendingIsNotEditable() {
            Ticket ticket = submit(TYPE_LEAVE);

            assertThatThrownBy(() -> ticketService.update(
                    ticket.getId(), updateRequest("偷偷改掉", TYPE_LEAVE)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("不能修改内容");
        }

        @Test
        @DisplayName("已通过不可改")
        void approvedIsNotEditable() {
            Ticket ticket = submit(TYPE_IT_REPAIR);
            loginAs(UID_LISI);
            ticketService.approve(ticket.getId(), ApprovalAction.APPROVE.name(), "同意");

            loginAs(UID_ZHANGSAN);
            assertThatThrownBy(() -> ticketService.update(
                    ticket.getId(), updateRequest("已通过还想改", TYPE_IT_REPAIR)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("不能修改内容");
        }

        @Test
        @DisplayName("已作废不可改")
        void closedIsNotEditable() {
            loginAs(UID_ZHANGSAN);
            Ticket ticket = createDraft(TYPE_LEAVE);
            ticketService.cancel(ticket.getId(), "不办了");

            assertThatThrownBy(() -> ticketService.update(
                    ticket.getId(), updateRequest("作废后还想改", TYPE_LEAVE)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("不能修改内容");
        }

        @Test
        @DisplayName("非创建人不得修改，且必须是 403 而非普通业务错误")
        void othersCannotUpdate() {
            Ticket ticket = createDraft(TYPE_LEAVE);

            loginAs(UID_WANGWU);
            assertThatThrownBy(() -> ticketService.update(
                    ticket.getId(), updateRequest("越权改", TYPE_LEAVE)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getCode())
                            .isEqualTo(ResultCode.FORBIDDEN));
        }

        @Test
        @DisplayName("修改时不清空级次与办结时间：申请人要能看到上一轮卡在哪一级")
        void updateKeepsStepAndFinishedAt() {
            Ticket ticket = submit(TYPE_LEAVE);
            rejectAsLisi(ticket.getId());
            Integer stepBefore = reload(ticket.getId()).getCurrentStep();

            loginAs(UID_ZHANGSAN);
            ticketService.update(ticket.getId(), updateRequest("改完再交", TYPE_LEAVE));

            Ticket after = reload(ticket.getId());
            assertThat(after.getCurrentStep()).isEqualTo(stepBefore);
            assertThat(after.getFinishedAt()).isNotNull();
            assertThat(after.getStatus()).isEqualTo(TicketStatus.REJECTED.name());
        }
    }

    // ==================================================================
    //  删除
    // ==================================================================

    @Nested
    @DisplayName("删除草稿")
    class Delete {

        @Test
        @DisplayName("草稿可删，且是逻辑删除 —— 记录仍留在库里可追溯")
        void draftIsDeletable() {
            Ticket ticket = createDraft(TYPE_LEAVE);

            loginAs(UID_ZHANGSAN);
            ticketService.delete(ticket.getId());

            // MyBatis-Plus 的逻辑删除会把 selectById 过滤掉，直接查表才能看到 deleted 标记
            assertThat(ticketMapper.selectById(ticket.getId())).isNull();
            assertThat(countDeletedRow(ticket.getId())).isEqualTo(1);
        }

        @Test
        @DisplayName("已提交的单不可删：进过审批流就必须留痕")
        void submittedIsNotDeletable() {
            Ticket ticket = submit(TYPE_LEAVE);

            assertThatThrownBy(() -> ticketService.delete(ticket.getId()))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("不能删除");
        }

        @Test
        @DisplayName("已驳回的单不可删")
        void rejectedIsNotDeletable() {
            Ticket ticket = submit(TYPE_LEAVE);
            rejectAsLisi(ticket.getId());

            loginAs(UID_ZHANGSAN);
            assertThatThrownBy(() -> ticketService.delete(ticket.getId()))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("不能删除");
        }

        @Test
        @DisplayName("非创建人不得删除他人草稿")
        void othersCannotDelete() {
            Ticket ticket = createDraft(TYPE_LEAVE);

            loginAs(UID_LISI);
            assertThatThrownBy(() -> ticketService.delete(ticket.getId()))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getCode())
                            .isEqualTo(ResultCode.FORBIDDEN));
        }
    }

    // ==================================================================
    //  作废
    // ==================================================================

    @Nested
    @DisplayName("作废")
    class Cancel {

        @Test
        @DisplayName("草稿可作废")
        void draftIsCancelable() {
            Ticket ticket = createDraft(TYPE_LEAVE);

            loginAs(UID_ZHANGSAN);
            ticketService.cancel(ticket.getId(), "不办了");

            assertThat(reload(ticket.getId()).getStatus()).isEqualTo(TicketStatus.CLOSED.name());
        }

        @Test
        @DisplayName("已驳回可作废")
        void rejectedIsCancelable() {
            Ticket ticket = submit(TYPE_LEAVE);
            rejectAsLisi(ticket.getId());

            loginAs(UID_ZHANGSAN);
            ticketService.cancel(ticket.getId(), "不再申请");

            assertThat(reload(ticket.getId()).getStatus()).isEqualTo(TicketStatus.CLOSED.name());
        }

        @Test
        @DisplayName("已撤回可作废 —— 撤回后不想改了，要能收尾")
        void withdrawnIsCancelable() {
            Ticket ticket = submit(TYPE_LEAVE);
            withdraw(ticket.getId());

            loginAs(UID_ZHANGSAN);
            ticketService.cancel(ticket.getId(), "不申请了");

            assertThat(reload(ticket.getId()).getStatus()).isEqualTo(TicketStatus.CLOSED.name());
        }

        @Test
        @DisplayName("审批中不可作废，应引导去用撤回 —— 两个入口职责不能重叠")
        void pendingIsNotCancelable() {
            Ticket ticket = submit(TYPE_LEAVE);

            assertThatThrownBy(() -> ticketService.cancel(ticket.getId(), "想作废"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("请使用撤回");
        }

        @Test
        @DisplayName("已通过不可作废：批过的事不能靠作废抹掉")
        void approvedIsNotCancelable() {
            Ticket ticket = submit(TYPE_IT_REPAIR);
            loginAs(UID_LISI);
            ticketService.approve(ticket.getId(), ApprovalAction.APPROVE.name(), "同意");

            loginAs(UID_ZHANGSAN);
            assertThatThrownBy(() -> ticketService.cancel(ticket.getId(), "想作废"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("不能作废");
        }

        @Test
        @DisplayName("作废后待办人必须被清空，不能残留在原审批人的待办里")
        void cancelClearsApprover() {
            Ticket ticket = submit(TYPE_LEAVE);
            withdraw(ticket.getId());

            loginAs(UID_ZHANGSAN);
            ticketService.cancel(ticket.getId(), "收尾");

            Ticket after = reload(ticket.getId());
            assertThat(after.getCurrentApproverId()).isNull();
            assertThat(after.getFinishedAt()).isNotNull();
        }
    }

    // ==================================================================
    //  撤回后重新提交
    // ==================================================================

    @Nested
    @DisplayName("撤回后重新提交")
    class ResubmitAfterWithdraw {

        @Test
        @DisplayName("已撤回的单可以重新提交，级次重置为 1 并重新派单")
        void withdrawnCanBeResubmitted() {
            Ticket ticket = submit(TYPE_LEAVE);
            withdraw(ticket.getId());
            assertThat(reload(ticket.getId()).getFinishedAt()).isNotNull();

            loginAs(UID_ZHANGSAN);
            ticketService.submit(ticket.getId());

            Ticket after = reload(ticket.getId());
            assertThat(after.getStatus()).isEqualTo(TicketStatus.PENDING.name());
            assertThat(after.getCurrentStep()).isEqualTo(1);
            assertThat(after.getTotalStep()).isEqualTo(2);
            assertThat(after.getCurrentApproverId()).isEqualTo(UID_LISI);
            // finishedAt 必须清回 null，否则单据会同时表现为"审批中"且"已办结"
            assertThat(after.getFinishedAt()).isNull();
        }

        @Test
        @DisplayName("已通过的单不能重新提交")
        void approvedCannotBeResubmitted() {
            Ticket ticket = submit(TYPE_IT_REPAIR);
            loginAs(UID_LISI);
            ticketService.approve(ticket.getId(), ApprovalAction.APPROVE.name(), "同意");

            loginAs(UID_ZHANGSAN);
            assertThatThrownBy(() -> ticketService.submit(ticket.getId()))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("不能提交");
        }

        @Test
        @DisplayName("已作废的单不能重新提交")
        void closedCannotBeResubmitted() {
            loginAs(UID_ZHANGSAN);
            Ticket ticket = createDraft(TYPE_LEAVE);
            ticketService.cancel(ticket.getId(), "不办了");

            assertThatThrownBy(() -> ticketService.submit(ticket.getId()))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("不能提交");
        }
    }

    // ==================================================================
    //  审批轨迹顺序
    // ==================================================================

    @Nested
    @DisplayName("审批轨迹")
    class History {

        /**
         * 这一组来自一次真实缺陷：轨迹原先按 step 升序排序，
         * 而"驳回后重新提交"会产生两条 step=0 的记录，于是
         * SUBMIT / REJECT / SUBMIT 被显示成 SUBMIT / SUBMIT / REJECT ——
         * 申请人看到的先后顺序与实际发生顺序不符，复盘时会误判。
         * 改为按主键（自增，天然等于发生顺序）排序后修复。
         */
        @Test
        @DisplayName("驳回后重新提交，轨迹必须按真实发生顺序返回，不能把两次提交排到一起")
        void shouldOrderByRealOccurrence() {
            Ticket ticket = submit(TYPE_LEAVE);
            rejectAsLisi(ticket.getId());

            loginAs(UID_ZHANGSAN);
            ticketService.submit(ticket.getId());

            List<ApprovalRecord> records = ticketService.history(ticket.getId());

            assertThat(records).extracting(ApprovalRecord::getAction)
                    .containsExactly("SUBMIT", "REJECT", "SUBMIT");
            assertThat(records).extracting(ApprovalRecord::getStep)
                    .containsExactly(0, 1, 0);
            // 主键必须严格递增，这是"顺序正确"的底层保证
            assertThat(records).extracting(ApprovalRecord::getId).isSorted();
        }

        @Test
        @DisplayName("撤回后重新提交，撤回记录应夹在两次提交之间")
        void shouldKeepWithdrawInPlace() {
            Ticket ticket = submit(TYPE_LEAVE);
            withdraw(ticket.getId());

            loginAs(UID_ZHANGSAN);
            ticketService.submit(ticket.getId());

            assertThat(ticketService.history(ticket.getId()))
                    .extracting(ApprovalRecord::getAction)
                    .containsExactly("SUBMIT", "WITHDRAW", "SUBMIT");
        }

        @Test
        @DisplayName("每一级审批记录保存当时的级次，不能整体错位一级")
        void shouldRecordStepPerLevel() {
            Ticket ticket = submit(TYPE_LEAVE);

            loginAs(UID_LISI);
            ticketService.approve(ticket.getId(), ApprovalAction.APPROVE.name(), "一级同意");
            loginAs(UID_ADMIN);
            ticketService.approve(ticket.getId(), ApprovalAction.APPROVE.name(), "二级同意");

            assertThat(ticketService.history(ticket.getId()))
                    .extracting(ApprovalRecord::getAction, ApprovalRecord::getStep)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("SUBMIT", 0),
                            org.assertj.core.groups.Tuple.tuple("APPROVE", 1),
                            org.assertj.core.groups.Tuple.tuple("APPROVE", 2));
        }
    }

    // ==================================================================
    //  列表与详情的动作标记
    // ==================================================================

    @Nested
    @DisplayName("动作标记")
    class AllowedActions {

        /**
         * 这些布尔值的意义在于：状态规则只在后端定义一次，
         * 前端不再自己写一套 status === "DRAFT" 的判断，否则两边规则会各自演进、
         * 表现为"功能做了但按钮不显示"。
         */
        @Test
        @DisplayName("草稿：可改可删可交可废，不可审不可撤")
        void draftFlags() {
            Ticket ticket = createDraft(TYPE_LEAVE);
            loginAs(UID_ZHANGSAN);

            TicketVO vo = ticketService.detail(ticket.getId());

            assertThat(vo.getEditable()).isTrue();
            assertThat(vo.getDeletable()).isTrue();
            assertThat(vo.getSubmittable()).isTrue();
            assertThat(vo.getCancelable()).isTrue();
            assertThat(vo.getApprovable()).isFalse();
            assertThat(vo.getWithdrawable()).isFalse();
            assertThat(vo.getCurrentApprover()).isFalse();
        }

        @Test
        @DisplayName("审批中：待办人看到 approvable=true 且 currentApprover=true")
        void pendingFlagsForCurrentApprover() {
            Ticket ticket = submit(TYPE_LEAVE);
            // 提交人是张三，此刻的待办人是李四 —— 必须切到李四才是在看"我的待办"
            loginAs(UID_LISI);

            TicketVO vo = ticketService.detail(ticket.getId());

            assertThat(vo.getApprovable()).isTrue();
            assertThat(vo.getCurrentApprover()).isTrue();
            // withdrawable 只看状态，不看"你是不是创建人" —— PENDING 就是可撤回。
            // "只有创建人能撤回" 由 Service 的 requireCreator 单独把关。
            // 这类标记表达的是"状态层面允不允许"，不是"你一定能操作成功"。
            assertThat(vo.getWithdrawable()).isTrue();
        }

        @Test
        @DisplayName("approvable 为真不代表我一定能审：李四的待办单切到王五视角就不该是待办")
        void approvableIsNotAPermissionGrant() {
            Ticket ticket = submit(TYPE_LEAVE);

            // 王五同为审批人角色，但李四已经接管这张单，轮不到他
            loginAs(UID_ADMIN);
            TicketVO adminView = ticketService.detail(ticket.getId());
            assertThat(adminView.getApprovable()).isTrue();
            assertThat(adminView.getCurrentApprover()).isFalse();

            // 用管理员账号试审会被"您不是该工单的当前审批人"挡下
            assertThatThrownBy(() -> ticketService.approve(
                    ticket.getId(), ApprovalAction.APPROVE.name(), "越权审批"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("不是该工单的当前审批人");
        }

        /**
         * approvable 与 currentApprover 必须分开看：前者回答"这个状态能不能审"，
         * 后者回答"该不该显示给我"。只看 approvable 会让所有审批人都在自己的列表里
         * 看到一张不属于自己的单的审批按钮。
         *
         * <p>这里用管理员作为观察者 —— 无关的普通用户压根没有查看权，
         * 会在权限校验处就被 403 拦下，根本走不到动作标记这一步。</p>
         */
        @Test
        @DisplayName("审批中：有查看权但非待办人时，approvable 为真而 currentApprover 为假")
        void pendingFlagsForUnrelatedApprover() {
            Ticket ticket = submit(TYPE_LEAVE);

            loginAs(UID_ADMIN);
            TicketVO vo = ticketService.detail(ticket.getId());

            assertThat(vo.getApprovable()).isTrue();
            assertThat(vo.getCurrentApprover()).isFalse();
        }

        @Test
        @DisplayName("无关人员连详情都看不到，谈不上看到审批按钮")
        void unrelatedUserCannotSeePendingTicket() {
            Ticket ticket = submit(TYPE_LEAVE);

            loginAs(UID_WANGWU);
            assertThatThrownBy(() -> ticketService.detail(ticket.getId()))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getCode())
                            .isEqualTo(ResultCode.FORBIDDEN));
        }

        @Test
        @DisplayName("已撤回：可改可交可废，不可删不可审")
        void withdrawnFlags() {
            Ticket ticket = submit(TYPE_LEAVE);
            withdraw(ticket.getId());

            loginAs(UID_ZHANGSAN);
            TicketVO vo = ticketService.detail(ticket.getId());

            assertThat(vo.getEditable()).isTrue();
            assertThat(vo.getSubmittable()).isTrue();
            assertThat(vo.getCancelable()).isTrue();
            assertThat(vo.getDeletable()).isFalse();
            assertThat(vo.getApprovable()).isFalse();
        }

        @Test
        @DisplayName("列表里每一行都要带上动作标记，前端不能只靠详情页拿到")
        void listRowsCarryFlags() {
            Ticket ticket = createDraft(TYPE_LEAVE);
            loginAs(UID_ZHANGSAN);

            TicketVO row = pageOf(UID_ZHANGSAN).getRecords().stream()
                    .filter(r -> r.getId().equals(ticket.getId()))
                    .findFirst()
                    .orElseThrow();

            assertThat(row.getEditable()).isTrue();
            assertThat(row.getDeletable()).isTrue();
            assertThat(row.getStatusLabel()).isEqualTo("草稿");
        }

        @Test
        @DisplayName("状态为脏值时不该打挂整个列表，只把按钮全部标为不可用")
        void dirtyStatusShouldNotBreakList() {
            Ticket ticket = createDraft(TYPE_LEAVE);
            // 绕过 Service 直接改库，模拟人工误操作写了个拼错的状态
            jdbcUpdateStatus(ticket.getId(), "PENDDING");

            loginAs(UID_ZHANGSAN);
            TicketVO row = pageOf(UID_ZHANGSAN).getRecords().stream()
                    .filter(r -> r.getId().equals(ticket.getId()))
                    .findFirst()
                    .orElseThrow();

            assertThat(row.getEditable()).isFalse();
            assertThat(row.getDeletable()).isFalse();
            assertThat(row.getSubmittable()).isFalse();
            assertThat(row.getApprovable()).isFalse();
            assertThat(row.getCurrentApprover()).isFalse();
            // 脏值原样回显，便于运维定位
            assertThat(row.getStatus()).isEqualTo("PENDDING");
            assertThat(row.getStatusLabel()).isEqualTo("PENDDING");
        }
    }

    // ==================================================================
    //  列表的批量名称填充
    // ==================================================================

    @Nested
    @DisplayName("列表名称填充")
    class ListNameFilling {

        @Test
        @DisplayName("类型名、部门名、创建人名、待办人名都要填上")
        void shouldFillAllNames() {
            Ticket ticket = submit(TYPE_LEAVE);

            loginAs(UID_ZHANGSAN);
            TicketVO row = findRow(pageOf(UID_ZHANGSAN), ticket.getId());

            assertThat(row.getTypeName()).isEqualTo("请假申请");
            assertThat(row.getDepartmentName()).isEqualTo("技术部");
            assertThat(row.getCreatorName()).isEqualTo("张三");
            assertThat(row.getCurrentApproverName()).isEqualTo("李四");
        }

        @Test
        @DisplayName("没有待办人时审批人名为 null，不能报错")
        void shouldTolerateNullApprover() {
            loginAs(UID_ZHANGSAN);
            Ticket ticket = createDraft(TYPE_LEAVE);

            TicketVO row = findRow(pageOf(UID_ZHANGSAN), ticket.getId());

            assertThat(row.getCurrentApproverId()).isNull();
            assertThat(row.getCurrentApproverName()).isNull();
        }

        /**
         * 这一条防的是那种"上线很久才偶然触发"的坑：selectBatchIds 收到空集合会生成
         * {@code WHERE id IN ()}，MySQL 直接语法报错，整个列表页 500。
         *
         * <p><b>为什么只测部门这一路</b>：type_id、creator_id 在表结构上是 NOT NULL，
         * 真实数据里不可能整列都是 NULL，对它们的空集合判断属于防御性代码。
         * department_id 可为空，是这一分支唯一可达的入口 —— 先用 keyword 把这一页
         * 缩到只剩这一条，departmentIds 才会真的成为空集。</p>
         */
        @Test
        @DisplayName("整页都没有关联部门时不能生成 WHERE id IN () 这种非法 SQL")
        void shouldHandleEmptyDepartmentIds() {
            loginAs(UID_ZHANGSAN);
            Ticket ticket = createDraft(TYPE_LEAVE);
            jdbcExecute("UPDATE ticket SET department_id = NULL, current_approver_id = NULL"
                    + " WHERE id = " + ticket.getId());

            TicketQuery query = new TicketQuery();
            query.setScope("mine");
            query.setPageSize(20);
            // 按单号精确匹配，保证这一页只有这一条
            query.setKeyword(ticket.getTicketNo());
            IPage<TicketVO> page = ticketService.page(query);

            TicketVO row = findRow(page, ticket.getId());
            assertThat(row.getDepartmentName()).isNull();
            // 类型名仍要正常填充，证明空集分支没有把整段批量查询一起跳过
            assertThat(row.getTypeName()).isEqualTo("请假申请");
        }
    }

    // ==================================================================
    //  测试辅助
    // ==================================================================

    private TicketVO findRow(IPage<TicketVO> page, Long ticketId) {
        return page.getRecords().stream()
                .filter(r -> r.getId().equals(ticketId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("列表里没有工单 " + ticketId));
    }

    private IPage<TicketVO> pageOf(long userId) {
        AuthTestSupport.loginAs(userId);
        TicketQuery query = new TicketQuery();
        // scope=all 能覆盖到所有与本用户相关的单，避免用例因可见范围差异而找不到行
        query.setScope("all");
        query.setPageSize(200);
        return ticketService.page(query);
    }

    private Ticket createDraft(Long typeId) {
        AuthTestSupport.loginAs(UID_ZHANGSAN);
        TicketCreateRequest request = new TicketCreateRequest();
        request.setTitle("生命周期测试工单");
        request.setContent("由 TicketLifecycleTest 创建，事务结束后回滚");
        request.setTypeId(typeId);
        request.setPriority("NORMAL");
        return ticketService.create(request);
    }

    /** 以张三身份创建并提交 */
    private Ticket submit(Long typeId) {
        Ticket ticket = createDraft(typeId);
        ticketService.submit(ticket.getId());
        return ticket;
    }

    private void withdraw(Long ticketId) {
        AuthTestSupport.loginAs(UID_ZHANGSAN);
        ticketService.withdraw(ticketId);
    }

    private void rejectAsLisi(Long ticketId) {
        AuthTestSupport.loginAs(UID_LISI);
        ticketService.approve(ticketId, ApprovalAction.REJECT.name(), "不同意");
    }

    private TicketUpdateRequest updateRequest(String title, Long typeId) {
        TicketUpdateRequest request = new TicketUpdateRequest();
        request.setTitle(title);
        request.setContent("内容");
        request.setTypeId(typeId);
        // priority 传 null 表示"这一项不动"，与 TicketUpdateRequest 的语义一致
        return request;
    }

    private Ticket reload(Long ticketId) {
        return ticketMapper.selectById(ticketId);
    }

    private void loginAs(long userId) {
        AuthTestSupport.loginAs(userId);
    }

    /** 直接查表统计逻辑删除标记，绕开 MyBatis-Plus 的自动过滤 */
    private int countDeletedRow(Long ticketId) {
        return jdbcQueryInt("SELECT deleted FROM ticket WHERE id = " + ticketId);
    }

    private void jdbcUpdateStatus(Long ticketId, String status) {
        jdbcExecute("UPDATE ticket SET status = '" + status + "' WHERE id = " + ticketId);
    }

    private void jdbcExecute(String sql) {
        jdbcTemplate.execute(sql);
    }

    private int jdbcQueryInt(String sql) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }

    /**
     * 用 JdbcTemplate 直连，绕过 MyBatis-Plus。
     * 有些断言必须看到被 ORM 隐藏的事实 —— 例如"逻辑删除后记录其实还在库里"、
     * "手工改库写了个脏状态值"，这些都没法通过 Mapper 做到。
     */
    @Autowired
    private JdbcTemplate jdbcTemplate;
}
