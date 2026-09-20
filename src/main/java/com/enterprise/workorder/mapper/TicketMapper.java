package com.enterprise.workorder.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.enterprise.workorder.entity.Ticket;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TicketMapper extends BaseMapper<Ticket> {

    // 注：这里原本有一个 selectMaxTicketNo(prefix)，用于 "取最大单号 +1" 生成单号。
    // 它已随单号生成方案一起废弃 —— 那种做法在并发下会算出重复序号。
    // 现在的单号由 TicketNoSeqMapper 通过 ticket_no_seq 表发放，见 TicketServiceImpl#generateTicketNo。

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
     *
     * <p><b>为什么不用乐观锁 version 字段</b>：审批链解析涉及部门主管、管理员账号等
     * 外部查询，失败重试的代价高于直接阻塞。工单是低频写、粒度细的场景，行锁更划算。</p>
     */
    @Select("SELECT * FROM ticket WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    Ticket selectByIdForUpdate(@Param("id") Long id);
}