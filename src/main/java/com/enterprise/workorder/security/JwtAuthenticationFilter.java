package com.enterprise.workorder.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JWT 认证过滤器：从请求头取出令牌，解析通过后把登录身份写入 SecurityContext。
 *
 * <p><b>执行位置</b>：由 SecurityConfig 通过 addFilterBefore(...) 插入 Spring Security
 * 过滤器链，位于 UsernamePasswordAuthenticationFilter 之前，因而也在 AuthorizationFilter
 * 之前。这样后续的授权判断才能读到本过滤器写入的身份。</p>
 *
 * <p><b>为什么不加 @Component</b>：Spring Boot 会把容器里所有 Filter 类型的 Bean
 * 自动注册到 Servlet 过滤器链上。若本类仍是 Bean，就会出现两份实例；而 Servlet 链的
 * 执行时机在 AuthorizationFilter 之后，授权环节将读不到身份，导致所有接口 401。
 * 因此改由 SecurityConfig 手动 new 出来，只保留安全链中的这一份。</p>
 *
 * <p><b>失败时不抛异常</b>：令牌缺失、伪造或过期一律静默放行，交由后置的授权环节
 * 统一返回 401，避免在过滤器里分散处理错误响应。</p>
 */
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final JwtProperties properties;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {

        String token = resolveToken(request);
        if (token != null) {
            Claims claims = jwtUtil.parseToken(token);
            if (claims != null) {
                // JWT 里只带了 uid / username / role，因此 realName 与 departmentId 为 null。
                // 需要这两项的业务请查库，不要依赖此处。
                LoginUser loginUser = new LoginUser(
                        jwtUtil.getUserId(claims),
                        jwtUtil.getUsername(claims),
                        null,
                        jwtUtil.getRole(claims),
                        null);

                var authentication = new UsernamePasswordAuthenticationToken(
                        loginUser, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + loginUser.getRoleCode())));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        }

        filterChain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(properties.getHeader());
        String prefix = properties.getPrefix();
        if (header != null && header.startsWith(prefix)) {
            return header.substring(prefix.length());
        }
        return null;
    }
}