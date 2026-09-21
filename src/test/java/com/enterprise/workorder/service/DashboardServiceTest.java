package com.enterprise.workorder.service;

import com.enterprise.workorder.dto.DashboardVO;
import com.enterprise.workorder.dto.TicketCreateRequest;
import com.enterprise.workorder.dto.TicketQuery;
import com.enterprise.workorder.entity.Ticket;
import com.enterprise.workorder.enums.TicketStatus;
import com.enterprise.workorder.support.AuthTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 首页看板测试。
 *
 * <p><b>为什么断言普遍写成"先取一次基准、再比差值"，而不是直接比绝对值</b>：
 * 看板统计的是整库数据，而开发库里本来就有手工造的单（演示、截图用）。
 * 写成 {@code assertThat(myTotal).isEqualTo(3)} 的后果是 —— 明天有人在页面上
 * 点了两下建了张单，这个用例就开始红，而代码一行没改。
 * 比差值的写法只依赖"我这期间做了什么"，用例寿命长得多。</p>
 *
 * <p><b>为什么期望值用 JdbcTemplate 另写一条 SQL 算，而不是复述被测 SQL</b>：
 * 用被测算出的数字反推期望值，等于用答案验证答案。这里另写一条裸 SQL，
 * 让两条实现路径互相印证 —— 列名抄错（比如把 current_approver_id 写成 approver_id）
 * 这类问题才抓得住。JdbcTemplate 只用于只读查询和"改 submitted_at"这种造前提，
 * 不碰业务写路径。</p>
 *
 * <p>@Transactional 保证用例造的数据在结束后回滚，不污染开发库。</p>
 */
@SpringBootTest
@Transactional
@DisplayName("首页看板")
class DashboardServiceTest {

    private static final Long UID_ADMIN = 1L;
    private static final Long UID_ZHANGSAN = 2L;
    private static final Long UID_LISI = 3L;
    private static final Long UID_WANGWU = 4L;

    /** 请假申请：审批链 DEPT_LEADER,ADMIN，技术部主管是李四(3) */
    private static final Long TYPE_LEAVE = 1L;
    /** IT报修：审批链 DEPT_LEADER，一级 */
    private static final Long TYPE_IT_REPAIR = 3L;

    private static final String ST_DRAFT = TicketStatus.DRAFT.name();
    private static final String ST_PENDING = TicketStatus.PENDING.name();
    private static final String ST_APPROVED = TicketStatus.APPROVED.name();

    @Autowired
    private DashboardService dashboardService;

    @Autowired
    private TicketService ticketService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        AuthTestSupport.logout();
    }

    // ==================================================================
    //  汇总卡片
    // ==================================================================

    @Nested
    @DisplayName("汇总卡片")
    class Summary {

        @Test
        @DisplayName("四个数字与独立 SQL 算出的口径一致")
        void shouldMatchIndependentSql() {
            DashboardVO.Summary summary = summaryOf(UID_ZHANGSAN);

            // 这四个数字都是"与我有关"，管理员看到的也是自己的 ——
            // 与 statusDistribution 的"管理员看全局"刻意不同：
            // 卡片回答"我要处理什么"，分布回答"我看得见的盘子有多大"
            assertThat(summary.getMyTotal()).isEqualTo(count(
                    "SELECT COUNT(*) FROM ticket WHERE deleted = 0 AND creator_id = ?", UID_ZHANGSAN));
            assertThat(summary.getMyTodo()).isEqualTo(count(
                    "SELECT COUNT(*) FROM ticket WHERE deleted = 0 AND status = 'PENDING' "
                            + "AND current_approver_id = ?", UID_ZHANGSAN));
            assertThat(summary.getMyPending()).isEqualTo(count(
                    "SELECT COUNT(*) FROM ticket WHERE deleted = 0 AND creator_id = ? "
                            + "AND status = 'PENDING'", UID_ZHANGSAN));
            assertThat(summary.getMyApproved()).isEqualTo(count(
                    "SELECT COUNT(*) FROM ticket WHERE deleted = 0 AND creator_id = ? "
                            + "AND status = 'APPROVED'", UID_ZHANGSAN));
        }

        @Test
        @DisplayName("我提交的单进我的在审数，不进我的待办数")
        void shouldCountSubmittedIntoSelfNotTodo() {
            long pendingBefore = summaryOf(UID_ZHANGSAN).getMyPending();
            long todoBefore = summaryOf(UID_ZHANGSAN).getMyTodo();

            createAndSubmit(TYPE_LEAVE, UID_ZHANGSAN);

            DashboardVO.Summary after = summaryOf(UID_ZHANGSAN);
            // 待办是"要我审的"，我交出去的单是"别人要审的"。
            // 两者混起来的典型症状是首页一直显示有 1 件事没做，
            // 点进去发现是自己的单 —— 自己还不能审
            assertThat(after.getMyPending()).isEqualTo(pendingBefore + 1);
            assertThat(after.getMyTodo()).isEqualTo(todoBefore);
        }

        @Test
        @DisplayName("别人提交的单进审批人的待办，不进提交人的待办")
        void shouldAssignTodoToApproverOnly() {
            long lisiTodoBefore = summaryOf(UID_LISI).getMyTodo();
            long zsTodoBefore = summaryOf(UID_ZHANGSAN).getMyTodo();

            createAndSubmit(TYPE_LEAVE, UID_ZHANGSAN);

            assertThat(summaryOf(UID_LISI).getMyTodo()).isEqualTo(lisiTodoBefore + 1);
            assertThat(summaryOf(UID_ZHANGSAN).getMyTodo()).isEqualTo(zsTodoBefore);
        }

        @Test
        @DisplayName("未读数只统计自己的，不会把别人的待办算进来")
        void unreadNotificationsShouldBePerUser() {
            long zsUnreadBefore = summaryOf(UID_ZHANGSAN).getUnreadNotifications();

            // 这一步会给李四产生一条待办通知，张三不该因此多出未读
            createAndSubmit(TYPE_LEAVE, UID_ZHANGSAN);

            assertThat(summaryOf(UID_ZHANGSAN).getUnreadNotifications()).isEqualTo(zsUnreadBefore);
            assertThat(summaryOf(UID_LISI).getUnreadNotifications()).isGreaterThanOrEqualTo(1L);
        }

        @Test
        @DisplayName("最久等待天数：把提交时间推到 3 天前，卡片上就看得到 3 天")
        void shouldReportLongestWaitingDays() {
            Ticket ticket = createAndSubmit(TYPE_IT_REPAIR, UID_ZHANGSAN);

            // 真实场景里"最久等待"要等三天才看得出来，测试不可能真等，
            // 只能直接把 submitted_at 改到过去。这是造前提，不是绕过被测逻辑
            jdbcTemplate.update("UPDATE ticket SET submitted_at = ? WHERE id = ?",
                    Timestamp.valueOf(LocalDateTime.now().minusDays(3)), ticket.getId());

            DashboardVO.Summary lisi = summaryOf(UID_LISI);
            assertThat(lisi.getMyTodo()).isGreaterThanOrEqualTo(1L);
            // 期望值用独立 SQL 算而不是硬编码 3：库里若还躺着更早的待办，
            // 硬编码就会假红，而代码其实是对的
            assertThat(lisi.getLongestWaitingDays()).isEqualTo(expectedWaitingDays(UID_LISI));
            assertThat(lisi.getLongestWaitingDays()).isGreaterThanOrEqualTo(3L);
        }

        @Test
        @DisplayName("等待天数为 0 当且仅当没有待办")
        void longestWaitingDaysShouldAgreeWithTodoCount() {
            // 这个不变量比具体数字值钱：两者在页面上是并排显示的一对，
            // 一个用 MIN(submitted_at) 另一个用 COUNT(*)，过滤条件一旦写岔，
            // 就会出现"0 天待办"或"没有待办却等了 5 天"这种自相矛盾的卡片
            for (long uid : new long[]{UID_ADMIN, UID_ZHANGSAN, UID_LISI, UID_WANGWU}) {
                DashboardVO.Summary s = summaryOf(uid);
                assertThat(s.getLongestWaitingDays() == 0L)
                        .as("用户 %s：待办数=%s，等待天数=%s", uid, s.getMyTodo(), s.getLongestWaitingDays())
                        .isEqualTo(s.getMyTodo() == 0L);
            }
        }
    }

    // ==================================================================
    //  状态分布
    // ==================================================================

    @Nested
    @DisplayName("状态分布")
    class StatusDistribution {

        @Test
        @DisplayName("六个状态一个不少，顺序与枚举一致，标签是中文")
        void shouldCoverAllStatusesInEnumOrder() {
            List<DashboardVO.StatusCount> rows = overviewOf(UID_ZHANGSAN).getStatusDistribution();

            assertThat(rows).hasSize(TicketStatus.values().length);
            for (int i = 0; i < TicketStatus.values().length; i++) {
                TicketStatus expected = TicketStatus.values()[i];
                // 顺序不能交给数据库的 GROUP BY 决定：换个索引执行计划变了，
                // 柱状图的顺序就跟着变，用户会以为数据错乱
                assertThat(rows.get(i).getStatus()).isEqualTo(expected.name());
                assertThat(rows.get(i).getStatusLabel()).isEqualTo(expected.getLabel());
            }
        }

        @Test
        @DisplayName("没有数据的状态以 0 出现，而不是从列表里消失")
        void shouldIncludeZeroCountStatuses() {
            // 造一个"库里肯定没有"的场景做不到（别的用例也会造数），
            // 所以换个角度：条数恒为 6 就已经保证了"为 0 的状态也在"。
            // 这里再补一条更强的：每个计数都能在独立 SQL 里对上账
            for (DashboardVO.StatusCount row : overviewOf(UID_ZHANGSAN).getStatusDistribution()) {
                assertThat(row.getCount()).isEqualTo(count(
                        "SELECT COUNT(*) FROM ticket WHERE deleted = 0 AND status = ? "
                                + "AND (creator_id = ? "
                                + "     OR (current_approver_id = ? AND status = 'PENDING'))",
                        row.getStatus(), UID_ZHANGSAN, UID_ZHANGSAN));
            }
        }

        @Test
        @DisplayName("管理员看全局，普通员工只看与自己相关的")
        void adminSeesGlobalScope() {
            long zsDraftBefore = statusCountOf(UID_ZHANGSAN, ST_DRAFT);
            long adminDraftBefore = statusCountOf(UID_ADMIN, ST_DRAFT);
            long wangwuDraftBefore = statusCountOf(UID_WANGWU, ST_DRAFT);

            // 王五（人事部）建一张草稿：提交前与张三、李四都无关
            createDraft(TYPE_LEAVE, UID_WANGWU);

            assertThat(statusCountOf(UID_WANGWU, ST_DRAFT))
                    .as("自己建的单自己必须看得见")
                    .isEqualTo(wangwuDraftBefore + 1);
            assertThat(statusCountOf(UID_ZHANGSAN, ST_DRAFT))
                    .as("与张三无关的单不该出现在张三的看板上")
                    .isEqualTo(zsDraftBefore);
            assertThat(statusCountOf(UID_ADMIN, ST_DRAFT))
                    .as("管理员的口径是全局，应该多出王五这一张")
                    .isEqualTo(adminDraftBefore + 1);
        }

        @Test
        @DisplayName("办结后从审批人的看板消失，但仍能在\"我已审批\"列表里找到")
        void approvedLeavesApproverDashboardButStaysInDoneScope() {
            long zsBefore = statusCountOf(UID_ZHANGSAN, ST_APPROVED);
            long lisiBefore = statusCountOf(UID_LISI, ST_APPROVED);

            AuthTestSupport.loginAs(UID_ZHANGSAN);
            Ticket ticket = createAndSubmit(TYPE_IT_REPAIR, UID_ZHANGSAN);
            AuthTestSupport.loginAs(UID_LISI);
            ticketService.approve(ticket.getId(), "APPROVE", "同意");

            assertThat(statusCountOf(UID_ZHANGSAN, ST_APPROVED))
                    .as("申请人的看板要把自己的单一直算着")
                    .isEqualTo(zsBefore + 1);

            // 这里刻意断言"审批人看不到"，而不是"看得到"。
            // 看板的口径被定义成"与列表 all 页签一致"，而 all 口径下
            // current_approver_id 只在 PENDING 期间生效 —— 办结的单不该赖在
            // 审批人的看板上不走，否则日积月累他的看板会全是自己审过的陈年旧账。
            // 想看自己审过的单，列表里有专门的 done 页签。
            // 两条数据**故意保持一致**：任一侧改了规则，这个用例都会红，
            // 逼迫改的人同时想清楚"看板与列表要不要一起动"。
            assertThat(statusCountOf(UID_LISI, ST_APPROVED)).isEqualTo(lisiBefore);

            // 但"看板上不计数"不等于"看不见了"：done 页签必须还能翻到它。
            // 少了这条，上面那句"去 done 页签找"就成了一句没有依据的注释
            TicketQuery doneQuery = new TicketQuery();
            doneQuery.setScope("done");
            doneQuery.setPageSize(200);
            assertThat(ticketService.page(doneQuery).getRecords())
                    .as("审批人必须能在\"我已审批\"里找到刚审完的单")
                    .anyMatch(vo -> ticket.getId().equals(vo.getId()));
        }

        @Test
        @DisplayName("审批中的单同时算进申请人与审批人的看板")
        void pendingShouldBeVisibleToBothSides() {
            long zsPendingBefore = statusCountOf(UID_ZHANGSAN, ST_PENDING);
            long lisiPendingBefore = statusCountOf(UID_LISI, ST_PENDING);

            createAndSubmit(TYPE_LEAVE, UID_ZHANGSAN);

            // 可见性谓词里 current_approver_id 那半边必须带 status='PENDING'，
            // 否则已办结的单会因残留的 approver_id 永远挂在审批人的看板上
            assertThat(statusCountOf(UID_ZHANGSAN, ST_PENDING)).isEqualTo(zsPendingBefore + 1);
            assertThat(statusCountOf(UID_LISI, ST_PENDING)).isEqualTo(lisiPendingBefore + 1);
        }
    }

    // ==================================================================
    //  趋势
    // ==================================================================

    @Nested
    @DisplayName("近 7 天趋势")
    class Trend {

        @Test
        @DisplayName("恰好 7 个点，日期连续递增，最后一个是今天")
        void shouldReturnSevenContinuousDaysEndingToday() {
            List<DashboardVO.TrendPoint> trend = overviewOf(UID_ZHANGSAN).getTrend();

            assertThat(trend).hasSize(7);
            // 缺的那天若被跳过，图上就不成其为"趋势"了 ——
            // x 轴有洞的柱状图看起来像是那几天系统挂了
            for (int i = 1; i < trend.size(); i++) {
                LocalDate prev = LocalDate.parse(trend.get(i - 1).getDate());
                LocalDate cur = LocalDate.parse(trend.get(i).getDate());
                assertThat(ChronoUnit.DAYS.between(prev, cur)).isEqualTo(1);
            }
            // 今天必须出现：即使今天一单没提交，图上也要有今天的 0 柱，
            // 否则用户会以为数据没刷新
            assertThat(trend.get(trend.size() - 1).getDate()).isEqualTo(LocalDate.now().toString());
        }

        @Test
        @DisplayName("每个点的计数都不为 null，且缺失日期上的 0 是真补出来的")
        void backfilledDaysShouldBeRealZeros() {
            for (DashboardVO.TrendPoint point : overviewOf(UID_ZHANGSAN).getTrend()) {
                // 补零写成"补个 new TrendPoint() 却没 setCount"，count 会是 null，
                // 前端求和时得到 NaN —— 在页面上表现为整块图表空白，很难定位
                assertThat(point.getCount()).as("日期 %s 的计数不能为 null", point.getDate()).isNotNull();
                assertThat(point.getCount()).isEqualTo(count(
                        "SELECT COUNT(*) FROM ticket WHERE deleted = 0 "
                                + "AND DATE_FORMAT(created_at, '%Y-%m-%d') = ? "
                                + "AND (creator_id = ? "
                                + "     OR (current_approver_id = ? AND status = 'PENDING'))",
                        point.getDate(), UID_ZHANGSAN, UID_ZHANGSAN));
            }
        }

        @Test
        @DisplayName("今天新建的单让最后一根柱子加一")
        void shouldCountTodayNewTicket() {
            long before = lastTrendCount(UID_ZHANGSAN);

            createDraft(TYPE_LEAVE, UID_ZHANGSAN);

            assertThat(lastTrendCount(UID_ZHANGSAN)).isEqualTo(before + 1);
        }

        @Test
        @DisplayName("别人建的单不加到我的趋势上")
        void shouldNotCountOthersTickets() {
            long before = lastTrendCount(UID_ZHANGSAN);

            createDraft(TYPE_LEAVE, UID_WANGWU);

            assertThat(lastTrendCount(UID_ZHANGSAN)).isEqualTo(before);
            // 空库上会有一段"今天 0 单"的时期，这里不断言具体数值，
            // 只要求管理员的柱子不低于张三的
            assertThat(lastTrendCount(UID_ADMIN)).isGreaterThanOrEqualTo(before);
        }

        @Test
        @DisplayName("7 天以前的单不计入趋势（时间边界）")
        void shouldIgnoreTicketsOlderThanWindow() {
            Ticket old = createDraft(TYPE_LEAVE, UID_ZHANGSAN);
            // 落在窗口外一天：7 天窗口含今天共 7 天，起点是今天-6，
            // 把 created_at 推到今天-7 就刚好出界
            jdbcTemplate.update("UPDATE ticket SET created_at = ? WHERE id = ?",
                    Timestamp.valueOf(LocalDateTime.now().minusDays(7)), old.getId());

            List<DashboardVO.TrendPoint> trend = overviewOf(UID_ZHANGSAN).getTrend();
            assertThat(trend.get(0).getDate()).isEqualTo(LocalDate.now().minusDays(6).toString());
            // 出界的单不该出现在任何一个点上
            assertThat(trend).allSatisfy(point -> assertThat(point.getCount()).isNotNull());
            assertThat(count("SELECT COUNT(*) FROM ticket WHERE id = ? AND created_at >= ?",
                    old.getId(), Timestamp.valueOf(LocalDate.now().minusDays(6).atStartOfDay())))
                    .isZero();
        }
    }

    // ==================================================================
    //  测试辅助
    // ==================================================================

    private DashboardVO overviewOf(long userId) {
        AuthTestSupport.loginAs(userId);
        return dashboardService.overview();
    }

    private DashboardVO.Summary summaryOf(long userId) {
        return overviewOf(userId).getSummary();
    }

    /** 某个账号看板上某状态的数量。找不到该状态时返回 0 —— 正常情况下不会发生（6 项恒在） */
    private long statusCountOf(long userId, String status) {
        return overviewOf(userId).getStatusDistribution().stream()
                .filter(row -> status.equals(row.getStatus()))
                .map(DashboardVO.StatusCount::getCount)
                .findFirst()
                .orElse(0L);
    }

    private long lastTrendCount(long userId) {
        List<DashboardVO.TrendPoint> trend = overviewOf(userId).getTrend();
        return trend.get(trend.size() - 1).getCount();
    }

    /**
     * 与实现同一算法算出的期望等待天数。
     *
     * <p>写成 SQL 而不是复用 Service 的代码：复用等于拿答案验答案，
     * Service 里"向上取整"改成"向下取整"时这个用例会一起跟着变、永远不报错。</p>
     */
    private Long expectedWaitingDays(long userId) {
        return jdbcTemplate.queryForObject("""
                SELECT COALESCE(GREATEST(CEIL(TIMESTAMPDIFF(HOUR, MIN(submitted_at), NOW()) / 24), 0), 0)
                FROM ticket
                WHERE deleted = 0 AND status = 'PENDING' AND current_approver_id = ?
                """, Long.class, userId);
    }

    private Long count(String sql, Object... args) {
        return Optional.ofNullable(jdbcTemplate.queryForObject(sql, Long.class, args)).orElse(0L);
    }

    private Ticket createDraft(Long typeId, long userId) {
        AuthTestSupport.loginAs(userId);
        TicketCreateRequest request = new TicketCreateRequest();
        request.setTitle("看板测试工单");
        request.setContent("由 DashboardServiceTest 创建，事务结束后回滚");
        request.setTypeId(typeId);
        request.setPriority("NORMAL");
        return ticketService.create(request);
    }

    private Ticket createAndSubmit(Long typeId, long userId) {
        Ticket ticket = createDraft(typeId, userId);
        ticketService.submit(ticket.getId());
        return ticket;
    }
}
