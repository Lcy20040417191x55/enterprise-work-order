package com.enterprise.workorder.common;

import lombok.Getter;

/**
 * 业务异常。由 GlobalExceptionHandler 统一转换为 Result。
 */
@Getter
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(String message) {
        super(message);
        this.code = ResultCode.BIZ_ERROR;
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }
}