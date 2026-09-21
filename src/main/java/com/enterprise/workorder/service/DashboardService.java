package com.enterprise.workorder.service;

import com.enterprise.workorder.dto.DashboardVO;

/**
 * 首页数据看板。
 *
 * <p>所有统计口径都跟随当前登录人对工单的可见范围：普通员工只看到与自己相关的，
 * 管理员看到全局。看板不做单独的权限体系，否则用户会看到"看板和列表对不上"。</p>
 */
public interface DashboardService {

    DashboardVO overview();
}
