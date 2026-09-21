package com.enterprise.workorder.config;

import com.enterprise.workorder.security.JwtAuthenticationFilter;
import com.enterprise.workorder.security.JwtProperties;
import com.enterprise.workorder.security.JwtUtil;
import com.enterprise.workorder.security.RestAccessDeniedHandler;
import com.enterprise.workorder.security.RestAuthenticationEntryPoint;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Spring Security 配置：无状态 JWT 鉴权。
 *
 * <p><b>过滤器顺序是这里最关键的一点。</b>Spring Security 内部各过滤器的先后决定了
 * "先认证、后授权"这一前提能否成立：</p>
 * <pre>
 *  ... -> JwtAuthenticationFilter -> AuthorizationFilter -> DispatcherServlet
 *            (写入身份)                  (检查身份)
 * </pre>
 * <p>本类用 addFilterBefore 把 JWT 过滤器插到 UsernamePasswordAuthenticationFilter 之前，
 * 从而保证它一定早于负责授权的 AuthorizationFilter 执行。
 * 若只依赖 @Component 让 Spring Boot 自动注册，它会落在 Servlet 过滤器链上，
 * 执行时机反而晚于 AuthorizationFilter，届时授权环节读不到身份，所有接口都会 401。</p>
 *
 * <p><b>会话策略</b>：STATELESS，即不创建 HttpSession。登录态完全由客户端携带的
 * JWT 承载，服务端不保存任何会话，天然支持多实例部署。</p>
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtUtil jwtUtil;
    private final JwtProperties jwtProperties;
    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // 前后端分离 + 无状态令牌，不存在 CSRF 利用场景，故关闭
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 认证/授权失败时统一返回 JSON，而不是 Spring Security 默认的 HTML 错误页
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)   // 未登录 -> 401
                        .accessDeniedHandler(accessDeniedHandler))            // 权限不足 -> 403
                .authorizeHttpRequests(auth -> auth
                        // 跨域预检请求不带令牌，必须放行，否则浏览器拿不到 CORS 响应头
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // 登录接口本身当然不能要求登录
                        .requestMatchers("/api/auth/login").permitAll()
                        // 接口文档与健康检查
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/api/health").permitAll()

                        // ---- 角色级授权 ----
                        // 原则：授权规则写在配置里，不写在 Controller 的 if 里。
                        // 配置是"进入业务代码之前"的一道闸门，角色不符时不会产生任何副作用；
                        // 而 Controller 里的判断已经在事务和业务逻辑之内了，防不住误写。

                        // 审批动作只对 ADMIN / APPROVER 开放。Service 层还会再校验
                        // "是否为本单当前待办人"，两道校验职责不同、都需要。
                        .requestMatchers(HttpMethod.POST, "/api/tickets/*/approve")
                        .hasAnyRole("ADMIN", "APPROVER")

                        // 工单类型是流程配置：查询人人可用（创建工单要选类型），
                        // 但增删改限管理员。改错审批链会影响所有人的单据流转。
                        .requestMatchers(HttpMethod.POST, "/api/ticket-types").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/ticket-types/*").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/ticket-types/*").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/ticket-types/all").hasRole("ADMIN")

                        // 用户与部门管理是管理员专属的配置操作
                        .requestMatchers("/api/users/**").hasRole("ADMIN")
                        .requestMatchers("/api/departments/**").hasRole("ADMIN")

                        // 删除工单是不可逆动作（虽是逻辑删除，但列表、详情、轨迹均不可见），
                        // 仅创建人本人可做，由 Service 校验；这里不再叠加角色限制，
                        // 因为普通员工也需要能删掉自己刚建错的草稿。

                        // 除上述规则外，其余接口只要登录即可访问
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthenticationFilter(), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * 手动构造过滤器实例，而不是注入 Bean。
     * 原因见类注释：容器中的 Filter Bean 会被 Spring Boot 额外注册到 Servlet 链上，
     * 造成同一请求被处理两次，且其中一次的时机是错误的。
     */
    private JwtAuthenticationFilter jwtAuthenticationFilter() {
        return new JwtAuthenticationFilter(jwtUtil, jwtProperties);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
