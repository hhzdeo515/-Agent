package com.guangxuan.audit.common.api;

/**
 * 当前请求的 traceId 持有者。
 *
 * <p>traceId 会写入 {@code audit_log.trace_id}，用于把"用户看到的一次操作"与
 * "数据库里的多条状态变更记录"关联起来（AGENTS.md 第 12 条的审计要求）。
 *
 * <p>此处刻意不依赖 Spring，使 gw-domain 也能读取（领域层要写 Review Record）。
 */
public final class TraceIdHolder {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private TraceIdHolder() {
    }

    public static void set(String traceId) {
        CURRENT.set(traceId);
    }

    public static String current() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
