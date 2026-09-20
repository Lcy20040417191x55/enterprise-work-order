package com.enterprise.workorder.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

/**
 * JWT 签发与解析。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtUtil {

    private static final String CLAIM_USER_ID = "uid";
    private static final String CLAIM_USERNAME = "username";
    private static final String CLAIM_ROLE = "role";

    private final JwtProperties properties;

    private SecretKey key() {
        return Keys.hmacShaKeyFor(properties.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 签发令牌。角色写入 claim，供后续鉴权判断，避免每次请求都查库。
     */
    public String generateToken(Long userId, String username, String roleCode) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + properties.getExpireHours() * 3600_000L);

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claims(Map.of(
                        CLAIM_USER_ID, userId,
                        CLAIM_USERNAME, username,
                        CLAIM_ROLE, roleCode))
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key())
                .compact();
    }

    /**
     * 解析令牌。签名错误或已过期统一返回 null，由调用方决定如何响应。
     */
    public Claims parseToken(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(key())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("JWT 解析失败: {}", e.getMessage());
            return null;
        }
    }

    public Long getUserId(Claims claims) {
        return claims.get(CLAIM_USER_ID, Number.class).longValue();
    }

    public String getUsername(Claims claims) {
        return claims.get(CLAIM_USERNAME, String.class);
    }

    public String getRole(Claims claims) {
        return claims.get(CLAIM_ROLE, String.class);
    }
}