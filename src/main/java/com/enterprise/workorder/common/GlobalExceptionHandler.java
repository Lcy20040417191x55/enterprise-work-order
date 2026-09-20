package com.enterprise.workorder.common;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理。把各类异常收敛成统一的 Result，避免异常堆栈直接暴露给前端。
 *
 * <p><b>HTTP 状态码策略</b>：能对应到标准 HTTP 语义的错误码，直接写回真实状态码
 * （400/401/403/404/405/415/500），让前端、网关、监控都能按 HTTP 语义识别失败；
 * 只有纯业务语义的错误码（如 1001 状态不允许）才保持 HTTP 200 + body.code，
 * 因为它表示"请求本身被正常处理了，只是业务规则不允许"。</p>
 *
 * <p><b>为什么要显式处理框架级异常</b>：Spring MVC 自己的异常（路径变量类型不符、
 * 方法不支持、路由不存在、请求体不是合法 JSON）若无人接管，会落进兜底分支被当成
 * 500 系统异常。这些其实是客户端错误，正确状态码是 400/404/405/415。
 * 把 400 报成 500 会污染监控告警，也让前端无从区分"我传错了"和"服务坏了"。</p>
 *
 * <p>无论走哪条分支，响应体始终是 Result 结构，前端解析方式不变。</p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ==================================================================
    //  业务异常
    // ==================================================================

    /** 业务异常：可预期，记 warn 即可 */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusiness(BusinessException e) {
        log.warn("业务异常: code={} msg={}", e.getCode(), e.getMessage());
        return build(e.getCode(), e.getMessage());
    }

    // ==================================================================
    //  参数与请求体校验
    // ==================================================================

    /**
     * 参数校验失败。取第一条错误信息返回。
     *
     * <p>这里要区分两类失败，否则会把 Spring 的技术性文案直接抛给调用方：</p>
     * <ul>
     *   <li><b>约束校验失败</b>（@Min/@NotBlank）—— defaultMessage 是业务可读文案，直接用</li>
     *   <li><b>类型转换失败</b>（pageSize=abc）—— defaultMessage 是
     *       "Failed to convert property value of type 'java.lang.String'..."，
     *       对调用方毫无意义，改写成"参数 X 类型不正确"</li>
     * </ul>
     */
    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public ResponseEntity<Result<Void>> handleValidation(BindException e) {
        FieldError fieldError = e.getBindingResult().getFieldErrors().stream().findFirst().orElse(null);
        if (fieldError == null) {
            return build(ResultCode.BAD_REQUEST, "参数校验失败");
        }
        String message = isTypeMismatch(fieldError)
                ? "参数 " + fieldError.getField() + " 类型不正确"
                : fieldError.getDefaultMessage();
        log.warn("参数校验失败: field={} message={}", fieldError.getField(), message);
        return build(ResultCode.BAD_REQUEST, message);
    }

    /**
     * 判断字段错误是否由类型转换失败引起。
     *
     * <p>用 FieldError.isBindingFailure()，它在 Spring 6.x 就已存在。
     * 注意不要用 BindingResult.TYPE_MISMATCH_CODE —— 那个常量是 Spring 7.0 才加入的，
     * 本项目跑在 Spring 6.1.14 上，引用它会直接编译失败。</p>
     */
    private static boolean isTypeMismatch(FieldError fieldError) {
        return fieldError.isBindingFailure();
    }

    /**
     * 方法参数上的约束校验失败（@Validated + @Min/@Max 等）。
     * 与上面的 BindException 分开：那个针对对象绑定，这个针对零散参数。
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<Void>> handleConstraintViolation(ConstraintViolationException e) {
        String message = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining("；"));
        log.warn("参数约束校验失败: {}", message);
        return build(ResultCode.BAD_REQUEST, message.isEmpty() ? "参数校验失败" : message);
    }

    /** 请求体不是合法 JSON、或字段类型无法反序列化 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleNotReadable(HttpMessageNotReadableException e) {
        // 异常原文包含类名与字段路径，对使用者无用，只记日志
        log.warn("请求体解析失败: {}", e.getMessage());
        return build(HttpStatus.BAD_REQUEST, ResultCode.BAD_REQUEST, "请求体格式错误，请检查是否为合法 JSON");
    }

    /** 路径变量或查询参数类型不符，例如 /api/tickets/abc 而方法签名要 Long */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        String name = e.getName();
        String required = e.getRequiredType() == null ? "未知" : e.getRequiredType().getSimpleName();
        log.warn("参数类型不符: name={} requiredType={} value={}", name, required, e.getValue());
        return build(HttpStatus.BAD_REQUEST, ResultCode.BAD_REQUEST,
                "参数 " + name + " 类型不正确，应为 " + required);
    }

    /** 缺少必填的查询参数 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<Void>> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("缺少请求参数: {}", e.getParameterName());
        return build(HttpStatus.BAD_REQUEST, ResultCode.BAD_REQUEST,
                "缺少必填参数 " + e.getParameterName());
    }

    // ==================================================================
    //  路由与请求方式
    // ==================================================================

    /** 请求方式不支持，例如用 PUT 调只暴露了 GET 的接口 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("请求方式不支持: {} 支持的方式={}", e.getMethod(), e.getSupportedHttpMethods());
        return build(HttpStatus.METHOD_NOT_ALLOWED, ResultCode.BAD_REQUEST,
                "该接口不支持 " + e.getMethod() + " 请求");
    }

    /** 路由不存在。需配合 spring.mvc.throw-exception-if-no-handler-found=true 才会走到这里 */
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<Result<Void>> handleNoHandler(NoHandlerFoundException e) {
        log.warn("接口不存在: {} {}", e.getHttpMethod(), e.getRequestURL());
        return build(HttpStatus.NOT_FOUND, ResultCode.NOT_FOUND,
                "接口不存在: " + e.getHttpMethod() + " " + e.getRequestURL());
    }

    /** Content-Type 不支持 */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMediaType(HttpMediaTypeNotSupportedException e) {
        log.warn("Content-Type 不支持: {}", e.getContentType());
        return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ResultCode.BAD_REQUEST,
                "不支持的 Content-Type，请使用 application/json");
    }

    // ==================================================================
    //  安全
    // ==================================================================

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Result<Void>> handleAccessDenied(AccessDeniedException e) {
        log.warn("权限不足: {}", e.getMessage());
        return build(ResultCode.FORBIDDEN, "权限不足，无法执行该操作");
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Result<Void>> handleAuth(AuthenticationException e) {
        log.warn("认证失败: {}", e.getMessage());
        return build(ResultCode.UNAUTHORIZED, "登录状态无效，请重新登录");
    }

    // ==================================================================
    //  兜底
    // ==================================================================

    /** 兜底：非预期异常必须打印堆栈，否则线上无从排查 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception e) {
        log.error("系统异常", e);
        return build(ResultCode.ERROR, "系统繁忙，请稍后重试");
    }

    // ==================================================================
    //  内部
    // ==================================================================

    /**
     * 业务码 -> HTTP 状态码。命中标准码时返回真实 HTTP 状态，否则回落到 200。
     * 集中在一处，避免每个 handler 各写各的映射。
     */
    private static HttpStatus httpStatusOf(int code) {
        return switch (code) {
            case ResultCode.BAD_REQUEST -> HttpStatus.BAD_REQUEST;
            case ResultCode.UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case ResultCode.FORBIDDEN -> HttpStatus.FORBIDDEN;
            case ResultCode.NOT_FOUND -> HttpStatus.NOT_FOUND;
            case ResultCode.ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
            // 1001 等纯业务码没有对应的 HTTP 语义，保持 200
            default -> HttpStatus.OK;
        };
    }

    private static ResponseEntity<Result<Void>> build(int code, String message) {
        return build(httpStatusOf(code), code, message);
    }

    /** 显式指定 HTTP 状态时使用（如 405/415，没有对应的业务码） */
    private static ResponseEntity<Result<Void>> build(HttpStatus status, int code, String message) {
        return ResponseEntity.status(status).body(Result.error(code, message));
    }
}