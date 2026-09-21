package com.enterprise.workorder.dto;

import lombok.Data;

import java.util.List;

/**
 * 首页数据看板。
 *
 * <p><b>为什么数字分三块而不是平铺一层</b>：看板的数字含义不同 ——
 * {@code summary} 是"与我有关"的待办压力，{@code statusDistribution} 是
 * "我可见范围"的结构分布，{@code trend} 是走势。混在一起前端只能靠字段名猜语义，
 * 用嵌套对象把语义写在结构里，前端也更容易按卡片拆分。</p>
 */
@Data
public class DashboardVO {

    private Summary summary;

    /** 各状态工单数量，按状态枚举顺序返回（不是按数量排序，位置稳定才便于对比趋势） */
    private List<StatusCount> statusDistribution;

    /** 最近 7 天每天的新建工单数 */
    private List<TrendPoint> trend;

    @Data
    public static class Summary {

        /** 我发起的总数 */
        private Long myTotal;

        /** 我的待办数量 */
        private Long myTodo;

        /** 我发起的、审批中的数量 */
        private Long myPending;

        /** 我发起的、已通过的数量 */
        private Long myApproved;

        /** 我的未读通知数 */
        private Long unreadNotifications;

        /** 当前待办中最久未处理的天数，没有待办时为 0 */
        private Long longestWaitingDays;
    }

    @Data
    public static class StatusCount {

        private String status;

        private String statusLabel;

        private Long count;
    }

    @Data
    public static class TrendPoint {

        /** yyyy-MM-dd */
        private String date;

        private Long count;
    }
}
