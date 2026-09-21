package com.enterprise.workorder.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.enterprise.workorder.entity.TicketAttachment;
import org.apache.ibatis.annotations.Mapper;

/**
 * 工单附件。
 *
 * <p>刻意没有任何自定义 SQL：附件的查询形状是"按 ticket_id 取全部、按 id 正序"，
 * BaseMapper 的 selectList + LambdaQueryWrapper 已经覆盖，再写 XML 只是换个地方重复它。</p>
 */
@Mapper
public interface TicketAttachmentMapper extends BaseMapper<TicketAttachment> {
}
