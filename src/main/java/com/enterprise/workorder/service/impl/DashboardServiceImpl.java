package com.enterprise.workorder.service.impl;

import com.enterprise.workorder.dto.DashboardStats;
import com.enterprise.workorder.dto.DashboardVO;
import com.enterprise.workorder.enums.RoleCode;
import com.enterprise.workorder.enums.TicketStatus;
import com.enterprise.workorder.mapper.TicketMapper;
import com.enterprise.workorder.security.LoginUser;
import com.enterprise.workorder.security.SecurityUtils;
import com.enterprise.workorder.service.DashboardService;
import com.enterprise.workorder.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 看板实现。
 *
 * <p><b>三条设计约定</b></p>
 * <ol>
 *   <li>统计口径 = 列表口径。管理员看全局，其余人看"我发起的 + 待我审批的"，
 *       与 {@code TicketServiceImpl#applyScope} 的 all 分支一致。
 *       违反这一条的后果是用户会问"为什么看板写着 12 张、列表只有 8 张"，
 *       而这类问题极难解释清楚。</li>
 *   <li>无数据的日期要补零。趋势图只有 3 天有数据时画出来是 3 个点，
 *       视觉上完全看不出"这两天没提交"，补成 7 个点才叫趋势。</li>
 *   <li>状态分布按枚举顺序重排。数据库的 GROUP BY 顺序不保证稳定，
 *       图表颜色与状态的对应关系会随之漂移。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardServiceImpl implements DashboardService {

    /** 趋势图天数。写成常量是为了让"SQL 取数范围"与"补零天数"共用同一个值 */
    private static final int TREND_DAYS = 7;

    private final TicketMapper ticketMapper;
    private final NotificationService notificationService;

    @Override
    public DashboardVO overview() {
        LoginUser current = SecurityUtils.getLoginUser();
        Long uid = current.getUserId();
        // 传给 SQL 的 0/1 标记。用 int 而不是 boolean，是因为 @Select 注解里
        // <if test="admin == 0"> 对 Boolean 的 OGNL 比较在部分版本上行为不一致，
        // 传 int 最没有歧义。
        int admin = RoleCode.ADMIN.name().equals(current.getRoleCode()) ? 1 : 0;

        DashboardVO vo = new DashboardVO();
        vo.setSummary(buildSummary(uid));
        vo.setStatusDistribution(buildStatusDistribution(uid, admin));
        vo.setTrend(buildTrend(uid, admin));
        return vo;
    }

    /**
     * 汇总卡片。
     *
     * <p>{@code earliestTodoSubmittedAt} 为 null 时等待天数给 0 而不是 -1：
     * 前端只需要"没有积压"这个语义，用 0 显示成"0 天"比显示成负数更容易理解。</p>
     */
    private DashboardVO.Summary buildSummary(Long uid) {
        DashboardStats stats = ticketMapper.selectStats(uid);

        DashboardVO.Summary summary = new DashboardVO.Summary();
        // 聚合查询返回 null 只会出现在"表里一行数据都没有"的极端情况，
        // 而看板不该因为库是空的就报错，所以每个字段都做兜底。
        summary.setMyTotal(nvl(stats == null ? null : stats.getMyTotal()));
        summary.setMyTodo(nvl(stats == null ? null : stats.getMyTodo()));
        summary.setMyPending(nvl(stats == null ? null : stats.getMyPending()));
        summary.setMyApproved(nvl(stats == null ? null : stats.getMyApproved()));
        summary.setUnreadNotifications(notificationService.unreadCount());

        LocalDateTime earliest = stats == null ? null : stats.getEarliestTodoSubmittedAt();
        if (earliest == null) {
            summary.setLongestWaitingDays(0L);
        } else {
            // 向上取整到"天"：上午提交、下午查看应当显示 1 天而不是 0 天 ——
            // 0 天看起来像"刚提交的"，会让积压的真实状况被低估。
            long hours = ChronoUnit.HOURS.between(earliest, LocalDateTime.now());
            summary.setLongestWaitingDays(hours <= 0 ? 0L : (hours + 23) / 24);
        }
        return summary;
    }

    /**
     * 状态分布。
     *
     * <p>先把 SQL 结果放进 Map，再按 {@link TicketStatus} 的枚举顺序遍历输出，
     * 这样即使某个状态在库里一条记录都没有，它也会以 count=0 出现在结果里 ——
     * 图表上"这个状态是 0"和"这个状态没出现在图例里"是两种完全不同的信息。</p>
     */
    private List<DashboardVO.StatusCount> buildStatusDistribution(Long uid, int admin) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (DashboardVO.StatusCount row : ticketMapper.countGroupByStatus(uid, admin)) {
            counts.put(row.getStatus(), nvl(row.getCount()));
        }

        List<DashboardVO.StatusCount> result = new ArrayList<>();
        for (TicketStatus status : TicketStatus.values()) {
            DashboardVO.StatusCount item = new DashboardVO.StatusCount();
            item.setStatus(status.name());
            item.setStatusLabel(status.getLabel());
            item.setCount(counts.getOrDefault(status.name(), 0L));
            result.add(item);
        }
        return result;
    }

    /**
     * 近 7 天趋势，缺失的日期补 0。
     *
     * <p>补零的另一个作用是让"今天"一定出现在图上。若今天还没人提交工单，
     * SQL 不会返回今天这一行，图表就会停在昨天 —— 用户会以为数据没刷新。</p>
     */
    private List<DashboardVO.TrendPoint> buildTrend(Long uid, int admin) {
        LocalDate today = LocalDate.now();
        // 含今天共 7 天，所以起点是 today - 6
        LocalDate from = today.minusDays(TREND_DAYS - 1L);

        Map<String, Long> counts = new LinkedHashMap<>();
        for (DashboardVO.TrendPoint row : ticketMapper.countGroupByDay(uid, admin, from.atStartOfDay())) {
            counts.put(row.getDate(), nvl(row.getCount()));
        }

        List<DashboardVO.TrendPoint> result = new ArrayList<>(TREND_DAYS);
        for (int i = 0; i < TREND_DAYS; i++) {
            LocalDate day = from.plusDays(i);
            String key = day.toString();
            DashboardVO.TrendPoint point = new DashboardVO.TrendPoint();
            point.setDate(key);
            point.setCount(counts.getOrDefault(key, 0L));
            result.add(point);
        }
        return result;
    }

    private Long nvl(Long value) {
        return value == null ? 0L : value;
    }
}
