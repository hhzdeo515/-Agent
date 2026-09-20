package com.guangxuan.audit.boot.web;

import com.guangxuan.audit.common.api.Result;
import com.guangxuan.audit.common.api.TraceIdHolder;
import com.guangxuan.audit.common.error.DomainException;
import com.guangxuan.audit.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 全局异常处理。
 *
 * <p>把领域异常映射为明确的业务错误码，而不是笼统的 500——
 * 这样前端能区分"你无权做这个"与"系统坏了"，用户也能看懂发生了什么。
 *
 * <p>HTTP 状态码语义（04 文档 §1.2）：
 * <ul>
 *   <li>422：状态机拒绝（当前状态不允许该操作）——请求本身合法，是状态不对</li>
 *   <li>403：授权拒绝</li>
 *   <li>404：资源不存在或不可见（跨项目访问统一按不存在处理，避免探测）</li>
 *   <li>409：并发冲突</li>
 *   <li>400：参数问题（含复审范围传 FULL）</li>
 * </ul>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Spring Security 的拒绝（{@code @PreAuthorize} 未通过）必须映射为 403。
     *
     * <p>若不显式处理，它会被兜底的 Exception 分支吞成 500，
     * 前端就无法区分"你没权限"与"系统坏了"——用户会以为是故障而反复重试。
     */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<Result<Void>> handleAccessDenied(
            org.springframework.security.access.AccessDeniedException ex) {
        log.warn("接口层授权拒绝: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Result.fail(ErrorCode.FORBIDDEN));
    }

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<Result<Void>> handleDomain(DomainException ex) {
        ErrorCode code = ex.errorCode();
        HttpStatus status = mapStatus(code);
        // 领域异常是预期内的业务结果，用 warn 级别且不打堆栈，避免日志噪音掩盖真实故障
        log.warn("领域规则拒绝: code={} msg={}", code.code(), ex.getMessage());
        return ResponseEntity.status(status)
                .body(Result.fail(code.code(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest()
                .body(Result.fail(ErrorCode.VALIDATION_FAILED.code(), detail));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<Void>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest()
                .body(Result.fail(ErrorCode.BAD_REQUEST.code(), ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleOther(Exception ex) {
        // 系统异常必须打全堆栈，且不要把内部细节回传给前端
        log.error("系统异常: traceId={}", TraceIdHolder.current(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.fail(ErrorCode.INTERNAL_ERROR));
    }

    private HttpStatus mapStatus(ErrorCode code) {
        int c = code.code();
        return switch (c / 100) {
            case 400 -> HttpStatus.BAD_REQUEST;
            case 401 -> HttpStatus.UNAUTHORIZED;
            case 403 -> HttpStatus.FORBIDDEN;
            case 404 -> HttpStatus.NOT_FOUND;
            case 409 -> HttpStatus.CONFLICT;
            case 422 -> HttpStatus.UNPROCESSABLE_ENTITY;
            default -> switch (code) {
                case ILLEGAL_TRANSITION, REVIEW_SCOPE_FORBIDDEN, ILLEGAL_CASE_STATUS -> HttpStatus.UNPROCESSABLE_ENTITY;
                case BLOCKING_RISK_OPEN, FALSE_POSITIVE_REASON_REQUIRED -> HttpStatus.UNPROCESSABLE_ENTITY;
                case SIGNATURE_REQUIRED, SIGNER_NOT_PRIVILEGED, ALREADY_APPROVED -> HttpStatus.UNPROCESSABLE_ENTITY;
                default -> HttpStatus.INTERNAL_SERVER_ERROR;
            };
        };
    }
}
