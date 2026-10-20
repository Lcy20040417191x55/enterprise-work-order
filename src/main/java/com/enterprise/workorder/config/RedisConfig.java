package com.enterprise.workorder.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 基础配置。
 *
 * <p>使用 StringRedisTemplate 而不是 RedisTemplate<Object,Object>：缓存内容明确是 JSON
 * 字符串，避免 JDK 序列化产生不可读数据，也避免不同服务之间因 serialVersionUID
 * 不一致而无法读取旧缓存。</p>
 */
@Configuration
public class RedisConfig {

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
}
