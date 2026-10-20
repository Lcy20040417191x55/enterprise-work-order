package com.enterprise.workorder.cache;

import com.enterprise.workorder.entity.TicketType;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 启用工单类型的 Redis 缓存。
 *
 * <p><b>为什么缓存这一项</b>：新建工单、智能助手、工单类型下拉框都会反复查询启用类型，
 * 但管理员修改类型的频率很低。它是典型的读多写少数据，适合缓存。</p>
 *
 * <p><b>为什么这里捕获 Redis 异常</b>：工单类型的真实数据仍在 MySQL，Redis 只是加速层。
 * Redis 重启或网络异常时，读操作回退数据库、写操作跳过缓存，不应让用户无法新建工单。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TicketTypeRedisCache {

    public static final String KEY_ENABLED_TYPES = "enterprise-work-order:ticket-types:enabled";
    private static final Duration TTL = Duration.ofMinutes(30);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /** Redis 命中返回缓存；未命中或 Redis 不可用返回 null */
    public List<TicketType> getEnabled() {
        try {
            String json = redisTemplate.opsForValue().get(KEY_ENABLED_TYPES);
            if (json == null || json.isBlank()) {
                return null;
            }
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            log.warn("读取 Redis 工单类型缓存失败，将回退 MySQL: {}", e.getMessage());
            return null;
        }
    }

    /** 写缓存失败只记录日志，不影响主流程 */
    public void putEnabled(List<TicketType> types) {
        try {
            String json = objectMapper.writeValueAsString(types);
            redisTemplate.opsForValue().set(KEY_ENABLED_TYPES, json, TTL);
        } catch (Exception e) {
            log.warn("写入 Redis 工单类型缓存失败: {}", e.getMessage());
        }
    }

    /** 类型新增、修改、删除后清除旧缓存，下一次查询从 MySQL 重建 */
    public void evictEnabled() {
        try {
            redisTemplate.delete(KEY_ENABLED_TYPES);
        } catch (Exception e) {
            log.warn("清理 Redis 工单类型缓存失败: {}", e.getMessage());
        }
    }
}
