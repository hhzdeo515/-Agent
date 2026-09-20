package com.guangxuan.audit.boot.web;

import com.guangxuan.audit.domain.security.Actor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * 把 {@link Actor} 作为 Controller 方法参数注入。
 *
 * <p>这样每个接口都显式声明"谁在执行这个动作"，避免从 SecurityContext 里
 * 到处静态取值——后者容易在异步线程或内部调用中拿不到主体而静默降级。
 */
@Component
public class ActorArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return Actor.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        Object actor = request == null ? null : request.getAttribute(RequestContextFilter.ATTR_ACTOR);
        return actor != null ? actor : Actor.system();
    }
}
