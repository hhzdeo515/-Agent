package com.guangxuan.audit.boot.web;

import com.guangxuan.audit.common.api.TraceIdHolder;
import com.guangxuan.audit.common.enums.ActorType;
import com.guangxuan.audit.common.security.PermCode;
import com.guangxuan.audit.domain.security.Actor;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 请求上下文过滤器：注入 traceId 与操作主体。
 *
 * <p>traceId 会写入 {@code audit_log.trace_id} 与 {@code review_record.trace_id}，
 * 使"用户看到的一次操作"可以关联到"数据库里的多条状态变更记录"
 * （AGENTS.md 第 12 条的审计要求）。
 *
 * <p><b>⚠️ 当前的操作主体取自请求头，仅供本地联调</b>。
 * 生产环境必须改为从 JWT 解析，且权限点不得从 token 的 claim 直接取信
 * （04 文档 §1.8：身份与授权分离，权限每次从服务端加载）。
 *
 * <p>注意 bean 名必须显式指定：Spring Boot 的 WebMvcAutoConfiguration 已有一个名为
 * {@code requestContextFilter} 的 bean，同名会导致 BeanDefinitionOverrideException 而启动失败。
 */
@Slf4j
@Component(RequestContextFilter.BEAN_NAME)
@Order(1)
public class RequestContextFilter extends OncePerRequestFilter {

    public static final String BEAN_NAME = "gwRequestContextFilter";

    public static final String HEADER_TRACE_ID = "X-Trace-Id";
    public static final String HEADER_USER_ID = "X-Actor-User-Id";
    public static final String HEADER_PERMISSIONS = "X-Actor-Permissions";
    public static final String ATTR_ACTOR = "gw.actor";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String traceId = request.getHeader(HEADER_TRACE_ID);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        TraceIdHolder.set(traceId);
        MDC.put("traceId", traceId);
        response.setHeader(HEADER_TRACE_ID, traceId);

        try {
            request.setAttribute(ATTR_ACTOR, resolveActor(request));
            chain.doFilter(request, response);
        } finally {
            // 必须清理，否则线程池复用会导致 traceId 串到下一个请求
            MDC.remove("traceId");
            TraceIdHolder.clear();
        }
    }

    private Actor resolveActor(HttpServletRequest request) {
        String userId = request.getHeader(HEADER_USER_ID);
        String perms = request.getHeader(HEADER_PERMISSIONS);

        if (userId == null || userId.isBlank()) {
            // 未声明主体：视为系统动作，权限为空。
            // 这不是"匿名用户"，而是"没有身份"——因此任何需要权限的操作都会被守卫拒绝。
            return Actor.system();
        }
        Set<String> permissions = new HashSet<>();
        if (perms != null && !perms.isBlank()) {
            Arrays.stream(perms.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .forEach(permissions::add);
        }
        return Actor.human(Long.valueOf(userId), "user-" + userId, permissions);
    }

    /** 便捷方法：给 Controller 用的"法务全权限"集合，仅本地联调使用 */
    public static Set<String> allLegalPermissions() {
        return Set.of(
                PermCode.CASE_VIEW, PermCode.CASE_LIST, PermCode.CASE_CREATE, PermCode.CASE_EDIT,
                PermCode.CASE_UPLOAD, PermCode.CASE_PARSE_RETRY, PermCode.CASE_REQUIREMENT_CONFIRM,
                PermCode.CASE_START_INITIAL_REVIEW, PermCode.CASE_REJECT,
                PermCode.CASE_RESUME_REMEDIATION, PermCode.CASE_BACK_TO_FEEDBACK, PermCode.CASE_ARCHIVE,
                PermCode.RISK_VIEW, PermCode.RISK_CONFIRM, PermCode.RISK_FALSE_POSITIVE,
                PermCode.RISK_REQUEST_EVIDENCE, PermCode.RISK_TO_REMEDIATION,
                PermCode.RISK_REREVIEW_TRIGGER, PermCode.RISK_LEGAL_FINAL_REVIEW,
                PermCode.RISK_SIGN, PermCode.RISK_CLOSE,
                PermCode.VERSION_VIEW, PermCode.VERSION_UPLOAD, PermCode.VERSION_DIFF_VIEW,
                PermCode.MATERIAL_APPROVE, PermCode.MATERIAL_REVOKE_APPROVAL,
                PermCode.REPORT_VIEW, PermCode.REPORT_EXPORT);
    }

    /** 品牌/设计/业务的可写权限：刻意不含确认风险、关闭、批准 */
    public static Set<String> brandPermissions() {
        return Set.of(PermCode.CASE_VIEW, PermCode.CASE_LIST, PermCode.CASE_CREATE,
                PermCode.CASE_UPLOAD, PermCode.CASE_PARSE_RETRY, PermCode.RISK_VIEW,
                PermCode.VERSION_VIEW, PermCode.VERSION_UPLOAD, PermCode.VERSION_DIFF_VIEW,
                PermCode.REPORT_VIEW, PermCode.REPORT_EXPORT);
    }

    static {
        // 仅为让 ActorType 的 import 有意义（用于日志判别），无副作用
        assert ActorType.HUMAN != null;
    }
}
