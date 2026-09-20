package com.guangxuan.audit.domain.security;

import com.guangxuan.audit.common.enums.ActorType;
import com.guangxuan.audit.common.error.DomainException;
import com.guangxuan.audit.common.error.ErrorCode;
import com.guangxuan.audit.common.security.PermCode;

import java.util.Collections;
import java.util.Set;

/**
 * 操作主体：谁在发起这次动作。
 *
 * <p>「AI 不得替代法务」这条产品边界（AGENTS.md 第 1、8 条）就是靠这个抽象落地的：
 * 领域守卫检查 {@link #type()} 与 {@link #hasPermission(String)}，
 * 使 AI 编排链路无论从哪个入口进来，都做不了确认风险、判误判、签名、关闭、批准。
 *
 * <p>AI 任务使用独立的服务账号，该账号在数据库中不持有 {@link PermCode#NEVER_FOR_AI} 中
 * 的任何权限点——所以这不是"代码里拦一下"，而是从凭据层面就做不到。
 *
 * @param type        主体类型
 * @param userId      人工主体必填；AI / SYSTEM 为空
 * @param displayName 展示名，仅用于审计展示
 * @param modelId     AI 主体必填；记录产出判断的模型，供审计复现（ADR D-10）
 * @param permissions 权限点集合；AI / SYSTEM 通常为空
 */
public record Actor(
        ActorType type,
        Long userId,
        String displayName,
        String modelId,
        Set<String> permissions) {

    public Actor {
        permissions = permissions == null ? Collections.emptySet() : Set.copyOf(permissions);
    }

    // ── 工厂方法 ─────────────────────────────────────────────────

    public static Actor ai(String modelId) {
        return new Actor(ActorType.AI, null, "AI (" + modelId + ")", modelId, Collections.emptySet());
    }

    public static Actor system() {
        return new Actor(ActorType.SYSTEM, null, "系统", null, Collections.emptySet());
    }

    public static Actor human(Long userId, String displayName, Set<String> permissions) {
        return new Actor(ActorType.HUMAN, userId, displayName, null, permissions);
    }

    // ── 判定 ─────────────────────────────────────────────────────

    public boolean isAi() {
        return type == ActorType.AI;
    }

    public boolean isHuman() {
        return type == ActorType.HUMAN;
    }

    public boolean hasPermission(String code) {
        return permissions.contains(code);
    }

    /** 是否具备法务身份：以"持有签名权"为判据，避免再维护一套角色判断 */
    public boolean isLegal() {
        return hasPermission(PermCode.RISK_SIGN);
    }

    // ── 守卫（失败即抛领域异常） ────────────────────────────────────

    /**
     * 要求具备某权限点。
     *
     * <p>注意：这里刻意对 AI 一律拒绝，即使有人误给 AI 账号配了权限点。
     * 双保险——权限点配置错误不应变成安全事故。
     */
    public void requirePermission(String code) {
        if (isAi() && PermCode.NEVER_FOR_AI.contains(code)) {
            throw new DomainException(ErrorCode.FORBIDDEN,
                    "AI 无权执行该操作：" + code);
        }
        if (!hasPermission(code)) {
            throw new DomainException(ErrorCode.FORBIDDEN,
                    "缺少权限：" + code);
        }
    }

    /** 要求是人工主体（用于签名、批准、关闭等只能由人完成的动作） */
    public void requireHuman(String what) {
        if (!isHuman()) {
            throw new DomainException(ErrorCode.FORBIDDEN,
                    what + " 必须由人工执行，当前主体：" + type);
        }
    }

    /** 要求是法务（用于终审、签名、关闭风险、批准物料） */
    public void requireLegal(String what) {
        requireHuman(what);
        if (!isLegal()) {
            throw new DomainException(ErrorCode.SIGNER_NOT_PRIVILEGED,
                    what + " 必须由具备法务签名权限的人员执行");
        }
    }

    /** 审计展示用标识，写入 review_record.actor_type / actor_id / ai_model_id */
    public String auditLabel() {
        return switch (type) {
            case AI -> "AI:" + modelId;
            case HUMAN -> "USER:" + userId;
            case SYSTEM -> "SYSTEM";
        };
    }
}
