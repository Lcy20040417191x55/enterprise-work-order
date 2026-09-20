package com.enterprise.workorder.security;

import com.enterprise.workorder.common.Result;
import com.enterprise.workorder.common.ResultCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 未登录（或令牌无效）时的统一响应：401 + JSON。
 *
 * <p>该逻辑运行在 Spring Security 过滤器链内部，尚未进入 DispatcherServlet，
 * 因此 GlobalExceptionHandler 的 @ExceptionHandler 不会生效，
 * 必须在 SecurityConfig 中显式注册本处理器，否则前端会收到一坨 HTML 错误页。</p>
 */
@Component
@RequiredArgsConstructor
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(),
                Result.error(ResultCode.UNAUTHORIZED, "未登录或登录已过期，请先登录"));
    }
}