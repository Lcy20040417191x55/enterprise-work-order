package com.enterprise.workorder.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;

/**
 * 工单单号序列。表 ticket_no_seq 每天一行（seq_date 主键），current_val 记录当天已发放的最大序号。
 *
 * <p><b>为什么不继承 BaseMapper</b>：这张表没有 id 主键、没有 deleted、没有 created_at/updated_at，
 * 它与 BaseEntity 的假设全然不符。硬套 BaseMapper 只会把逻辑删除、自动填充这些默认行为
 * 引进来，反而要再一个个关掉。这里只有两条语句，直接写清楚更省事。</p>
 *
 * <p><b>为什么是两个方法而不是一个</b>：MySQL 的 {@code LAST_INSERT_ID(expr)} 技巧虽然能在
 * 一条语句里同时“自增并返回新值”，但它依赖连接级的会话状态。MyBatis 在事务内复用同一连接，
 * 分割成两条语句读到的就是同一个事务的未提交值，语义同样正确，且不必依赖冷门技巧 ——
 * 对学习者来说，“写清楚”比“省一条 SQL”重要。代价仅是一次极轻的主键查询。</p>
 */
@Mapper
public interface TicketNoSeqMapper {

    /**
     * 把当天的序号 +1。当天没有记录时插入初始值 1。
     *
     * <p><b>为什么用 UPSERT 而不是“先 SELECT 再 UPDATE/INSERT”</b>：那是两步读改写，
     * 与本次要修的缺陷是同一类问题 —— 并发下两个请求都读到“当天还没有记录”，
     * 然后都去 INSERT，其中一个撞主键。{@code INSERT ... ON DUPLICATE KEY UPDATE}
     * 由数据库一条语句原子完成，不存在这个窗口。</p>
     *
     * <p>该语句对 seq_date 这一行加排他锁，并持有到事务提交。
     * 因此同一瞬间的并发创建会被串行化，各自拿到不同序号。</p>
     *
     * <p><b>为什么标注是 {@code @Insert} 而不是 {@code @Update}</b>（这里踩过坑，值得记下）：
     * 这条语句实质是 upsert，写成哪个注解都能执行，但两个注解会让 MyBatis 给出不同的
     * {@code SqlCommandType}（前者 INSERT，后者 UPDATE），而 MyBatis-Plus 的
     * {@code BlockAttackInnerInterceptor}（防全表更新/删除插件）只拦截 UPDATE 与 DELETE。
     *
     * <p>一旦标成 {@code @Update}，该插件就会拿 JSqlParser 解析这条 SQL；它按语句类型分发，
     * 看到这是 INSERT 语句就去调基类的 {@code processInsert}，而插件本身并没有重写该方法，
     * 基类实现直接 {@code throw new UnsupportedOperationException()}。
     * 最终表现为创建工单接口大面积 500，报错却是
     * “Error updating database. Cause: java.lang.UnsupportedOperationException”，
     * 既没提插件、也没提 SQL —— 排查时很容易误判成文本块语法或日期类型的 TypeHandler 问题。
     * 标成 {@code @Insert} 后该语句不再进入插件解析路径，问题从根上消失。</p>
     */
    @Insert("""
            INSERT INTO ticket_no_seq (seq_date, current_val) VALUES (#{seqDate}, 1)
            ON DUPLICATE KEY UPDATE current_val = current_val + 1
            """)
    int takeNextSeq(@Param("seqDate") LocalDate seqDate);

    /** 读取当天已发放的序号。与 {@link #takeNextSeq} 同事务，读得到自己未提交的修改 */
    @Select("SELECT current_val FROM ticket_no_seq WHERE seq_date = #{seqDate}")
    Integer currentSeq(@Param("seqDate") LocalDate seqDate);
}