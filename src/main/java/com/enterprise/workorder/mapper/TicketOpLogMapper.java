package com.enterprise.workorder.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.enterprise.workorder.entity.TicketOpLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 工单操作日志 Mapper。
 *
 * <p>不需要自定义 SQL：详情页要的是"这张单的全部日志，按发生顺序"，
 * Service 层用 LambdaQueryWrapper 就能表达，写成注解 SQL 反而多一处维护点。</p>
 */
@Mapper
public interface TicketOpLogMapper extends BaseMapper<TicketOpLog> {
}