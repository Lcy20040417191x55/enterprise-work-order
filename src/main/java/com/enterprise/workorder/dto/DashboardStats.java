package com.enterprise.workorder.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 看板汇总统计的数据库投影。
 *
 * <p><b>为什么用一个大 SQL 一次取回，而不是拆成 4~5 次 selectCount</b>：
 * 看板每次进首页都会加载，聚合查询拆开就是 N 次网络往返 + N 次 SQL 解析。
 * 这些子查询本身都是走索引的主键/二级索引扫描，合成一条语句后总代价基本等于
 * 最慢的那个子查询，而往返次数从 5 次变成 1 次。</p>
 *
 * <p>字段名与 SQL 里的列别名一一对应，靠 MyBatis 的 map-underscore-to-camel-case
 * 自动填充（别名写成驼峰即可直接映射）。</p>
 */
@Data
public class DashboardStats {

    /** 我发起的总数 */
    private Long myTotal;

    /** 待我审批的数量 */
    private Long myTodo;

    /** 我发起的、审批中的数量 */
    private Long myPending;

    /** 我发起的、已通过的数量 */
    private Long myApproved;

    /**
     * 我手上最早一张待办的提交时间，用于算"最久等了几天"。
     * 没有待办时为 null —— 用 0 表示"没有"和"等了 0 天"会混淆，
     * 语义上必须区分开。
     */
    private LocalDateTime earliestTodoSubmittedAt;
}
