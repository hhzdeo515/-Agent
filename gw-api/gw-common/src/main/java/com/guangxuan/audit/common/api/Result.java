package com.guangxuan.audit.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.guangxuan.audit.common.error.ErrorCode;

/**
 * 统一响应包装（见 04 文档 §1.3）。
 *
 * @param code    错误码，成功为 0
 * @param message 提示信息
 * @param data    业务数据
 * @param traceId 链路追踪 ID，写入 audit_log.trace_id，便于事后还原操作过程
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Result<T>(int code, String message, T data, String traceId) {

    public static final int SUCCESS_CODE = 0;

    public static <T> Result<T> ok(T data) {
        return new Result<>(SUCCESS_CODE, "ok", data, TraceIdHolder.current());
    }

    public static Result<Void> ok() {
        return new Result<>(SUCCESS_CODE, "ok", null, TraceIdHolder.current());
    }

    public static <T> Result<T> fail(ErrorCode errorCode) {
        return new Result<>(errorCode.code(), errorCode.message(), null, TraceIdHolder.current());
    }

    public static <T> Result<T> fail(ErrorCode errorCode, String detail) {
        return new Result<>(errorCode.code(), detail, null, TraceIdHolder.current());
    }

    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(code, message, null, TraceIdHolder.current());
    }

    public boolean isSuccess() {
        return code == SUCCESS_CODE;
    }
}
