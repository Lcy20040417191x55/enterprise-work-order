package com.enterprise.workorder.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.enterprise.workorder.dto.DashboardStats;
import com.enterprise.workorder.dto.DashboardVO;
import com.enterprise.workorder.entity.Ticket;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface TicketMapper extends BaseMapper<Ticket> {

    /**
     * 按主键查询并加行锁（SELECT ... FOR UPDATE）。仅用于会修改工单的写操作。
     *
     * <p><b>为什么必须加锁</b>：审批、提交、撤回都是"读出当前状态 -> 校验 -> 写回新状态"
     * 的典型读改写。若两个请求并发到达，二者会读到同一份状态快照，各自都校验通过、
     * 各自都写回，于是同一次审批被记录两次、状态机被破坏。
     * 唯一索引挡不住这种错误，因为它不是插入冲突，而是两次合法的 UPDATE。</p>
     *
     * <p>加行锁后同一张工单的写操作被串行化：后到的请求会阻塞到前一个事务提交，
     * 此时它读到的是已经更新过的状态，状态校验自然失败并返回业务错误。</p>
     *
     * <p><b>锁粒度</b>：只锁单行，不同工单之间互不影响。调用方必须处于事务中，
     * 否则锁在语句结束后立即释放，起不到任何作用。</p>
     */
    @Select("SELECT * FROM ticket WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    Ticket selectByIdForUpdate(@Param("id") Long id);

    // ==================================================================
    //  看板聚合
    //
    //  下面三个方法共用同一套"可见范围"谓词，必须与
    //  TicketServiceImpl#applyScope 的 all 分支保持一字不差：
    //    管理员          -> 不加限制
    //    普通用户/审批人 -> creator_id = 我
    //                       OR (current_approver_id = 我 AND status = 'PENDING')
    //
    //  刻意在这三个 SQL 里重复这段谓词，而没有抽成 <sql> 片段或公共方法：
    //  @Select 注解里的 <script> 无法跨方法引用片段，抽出来要么改成 XML mapper、
    //  要么用 @SelectProvider 拼字符串 —— 后者的可读性比重复两行更差。
    //  代价是改可见性规则时要同时改这三处，因此在 applyScope 与这里都留了交叉引用注释。
    // ==================================================================

    /**
     * 看板汇总数字。
     *
     * <p>四个子查询各自独立、互不依赖，数据库可以在一条语句里完成；
     * 相比四次 selectCount，省掉三次网络往返与三次语句解析。</p>
     */
    @Select("""
            SELECT
              (SELECT COUNT(*) FROM ticket
                WHERE deleted = 0 AND creator_id = #{uid})                       AS myTotal,
              (SELECT COUNT(*) FROM ticket
                WHERE deleted = 0 AND status = 'PENDING'
                  AND current_approver_id = #{uid})                              AS myTodo,
              (SELECT COUNT(*) FROM ticket
                WHERE deleted = 0 AND creator_id = #{uid} AND status = 'PENDING') AS myPending,
              (SELECT COUNT(*) FROM ticket
                WHERE deleted = 0 AND creator_id = #{uid} AND status = 'APPROVED') AS myApproved,
              (SELECT MIN(submitted_at) FROM ticket
                WHERE deleted = 0 AND status = 'PENDING'
                  AND current_approver_id = #{uid})                              AS earliestTodoSubmittedAt
            """)
    DashboardStats selectStats(@Param("uid") Long uid);

    /**
     * 按状态分组统计。
     *
     * <p>SQL 里不写 ORDER BY：状态的展示顺序由 Service 按枚举顺序重排，
     * 让数据库的返回顺序决定页面顺序，等于把页面布局交给执行计划 ——
     * 换个索引、数据量变了，柱状图的顺序就跟着变。</p>
     */
    @Select("""
            <script>
            SELECT status AS status, COUNT(*) AS count FROM ticket
            WHERE deleted = 0
            <if test="admin == 0">
              AND (creator_id = #{uid}
                   OR (current_approver_id = #{uid} AND status = 'PENDING'))
            </if>
            GROUP BY status
            </script>
            """)
    List<DashboardVO.StatusCount> countGroupByStatus(@Param("uid") Long uid,
                                                     @Param("admin") int admin);

    /**
     * 按天分组统计新建工单数。
     *
     * <p>用 {@code DATE_FORMAT} 而不是 {@code DATE(created_at)}，是为了让结果直接是
     * 字符串（yyyy-MM-dd）。若返回 Date 类型，MyBatis 映射到 String 字段时依赖
     * 隐式 toString，时区配置一变就出现差一天的问题。</p>
     *
     * <p>SQL 只返回"有数据的那些天"，缺口由 Service 补齐 —— 补零必须放在 Java 侧，
     * 因为数据库无法凭空造出没有记录的那几天。</p>
     */
    @Select("""
            <script>
            SELECT DATE_FORMAT(created_at, '%Y-%m-%d') AS date, COUNT(*) AS count FROM ticket
            WHERE deleted = 0 AND created_at &gt;= #{from}
            <if test="admin == 0">
              AND (creator_id = #{uid}
                   OR (current_approver_id = #{uid} AND status = 'PENDING'))
            </if>
            GROUP BY DATE_FORMAT(created_at, '%Y-%m-%d')
            </script>
            """)
    List<DashboardVO.TrendPoint> countGroupByDay(@Param("uid") Long uid,
                                                 @Param("admin") int admin,
                                                 @Param("from") LocalDateTime from);
}
