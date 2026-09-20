package com.guangxuan.audit.boot.security;

import com.guangxuan.audit.boot.web.RequestContextFilter;
import com.guangxuan.audit.common.security.PermCode;
import com.guangxuan.audit.domain.security.Actor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * {@code @PreAuthorize("@perm.has('risk.close')")} 的判定组件。
 *
 * <p><b>这只是接口层的第一道</b>。真正的授权是三层的（AGENTS.md 第 8 条）：
 * <ol>
 *   <li>本类：接口层快速拒绝；</li>
 *   <li>领域守卫（{@code Actor.requirePermission / requireLegal}）：内部调用、
 *       定时任务、回调也要经过，因此这里是不可绕过的那一层；</li>
 *   <li>数据库触发器：挡住"绕过应用层直接改库"。</li>
 * </ol>
 *
 * <p>这里对 AI 主体额外做一次 {@link PermCode#NEVER_FOR_AI} 拦截：
 * 即使有人误给 AI 服务账号配了权限点，也不会变成安全事故。
 */
@Component("perm")
public class PermissionChecker {

    public boolean has(String code) {
        Actor actor = currentActor();
        if (actor == null) {
            return false;
        }
        if (actor.isAi() && PermCode.NEVER_FOR_AI.contains(code)) {
            return false;
        }
        return actor.hasPermission(code);
    }

    /** 同时具备全部权限点 */
    public boolean hasAll(String... codes) {
        for (String c : codes) {
            if (!has(c)) {
                return false;
            }
        }
        return true;
    }

    /** 具备任意一个权限点 */
    public boolean hasAny(String... codes) {
        for (String c : codes) {
            if (has(c)) {
                return true;
            }
        }
        return false;
    }

    private Actor currentActor() {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return null;
        }
        HttpServletRequest request = attrs.getRequest();
        Object actor = request.getAttribute(RequestContextFilter.ATTR_ACTOR);
        return actor instanceof Actor a ? a : null;
    }
}
