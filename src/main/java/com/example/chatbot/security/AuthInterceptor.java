package com.example.chatbot.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    public static final String AUTH_USER_ID_ATTR = "authUserId";
    public static final String AUTH_USER_ROLE_ATTR = "authUserRole";

    private final JwtTokenProvider jwtTokenProvider;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"success\":false,\"error\":\"未登录或登录已过期\"}");
            return false;
        }

        String token = authorization.substring(7).trim();
        try {
            Long userId = jwtTokenProvider.parseUserId(token);
            String role = jwtTokenProvider.parseRole(token);
            request.setAttribute(AUTH_USER_ID_ATTR, userId);
            request.setAttribute(AUTH_USER_ROLE_ATTR, role);

            if (handler instanceof HandlerMethod hm) {
                RequireRole annotation = findRoleAnnotation(hm);
                if (annotation != null && !annotation.value().equals(role)) {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"success\":false,\"error\":\"权限不足，需要 " + annotation.value() + " 角色\"}");
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"success\":false,\"error\":\"登录状态无效，请重新登录\"}");
            return false;
        }
    }

    private RequireRole findRoleAnnotation(HandlerMethod hm) {
        RequireRole methodAnnotation = hm.getMethodAnnotation(RequireRole.class);
        if (methodAnnotation != null) {
            return methodAnnotation;
        }
        return hm.getBeanType().getAnnotation(RequireRole.class);
    }
}
