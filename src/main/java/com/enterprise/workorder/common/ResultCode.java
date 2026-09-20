package com.enterprise.workorder.common;

/**
 * 业务错误码。
 */
public final class ResultCode {

    private ResultCode() {
    }

    public static final int SUCCESS = 200;
    public static final int BAD_REQUEST = 400;
    public static final int UNAUTHORIZED = 401;
    public static final int FORBIDDEN = 403;
    public static final int NOT_FOUND = 404;
    public static final int ERROR = 500;

    /** 业务校验失败，如状态不允许该操作 */
    public static final int BIZ_ERROR = 1001;
}