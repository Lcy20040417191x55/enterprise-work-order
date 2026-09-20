package com.enterprise.workorder.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JWT 配置项，对应 application.yml 中的 app.jwt.*
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.jwt")
public class JwtProperties {

    /** HS256 密钥，长度需 >= 32 字节 */
    private String secret;

    private Integer expireHours = 24;

    private String header = "Authorization";

    private String prefix = "Bearer ";
}