package com.guangxuan.audit.common.error;

/**
 * 领域异常：由领域守卫抛出，代表"按业务规则该操作不被允许"。
 *
 * <p>与 {@link IllegalArgumentException} 之类的技术异常区分开，便于全局异常处理器
 * 返回明确的业务错误码，而不是笼统的 500。
 */
public class DomainException extends RuntimeException {

    private final ErrorCode errorCode;

    public DomainException(ErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
    }

    public DomainException(ErrorCode errorCode, String detail) {
        super(detail);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
