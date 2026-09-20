package com.enterprise.workorder.security;

import com.enterprise.workorder.common.Result;
import com.enterprise.workorder.common.ResultCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 已登录但权限不足时的统一响应：403 + JSON。
 *
 * <p>与 RestAuthenticationEntryPoint 同理，发生在过滤器链内，
 * 需要在此手动把响应体写成与业务接口一致的 Result 结构。
 * 在 Service 层主动抛出的 BusinessException(FORBIDDEN) 仍由
 * GlobalExceptionHandler 处理，两条路径返回的 JSON 结构保持一致。</p>
 */
@Component
@RequiredArgsConstructor
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(),
                Result.error(ResultCode.FORBIDDEN, "权限不足，无法执行该操作"));
    }
}