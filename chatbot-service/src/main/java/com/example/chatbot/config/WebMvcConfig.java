package com.example.chatbot.config;

import com.example.chatbot.security.AuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Auth interceptor for API endpoints
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/auth/login",
                        "/api/auth/register",
                        "/api/auth/refresh",
                        "/api/auth/send-code",
                        "/api/auth/forgot-password",
                        "/api/auth/reset-password",
                        "/api/chat/health"
                );

        // Security headers interceptor (CSP, X-Frame-Options, etc.)
        registry.addInterceptor(new SecurityHeadersInterceptor())
                .addPathPatterns("/**");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .setCachePeriod(3600);
    }

    /**
     * 安全响应头拦截器，添加 CSP 和 X-Frame-Options 等头部。
     */
    private static class SecurityHeadersInterceptor implements HandlerInterceptor {

        private static final Set<String> EXCLUDED_PATHS = new HashSet<>(Arrays.asList(
                "/api/auth/login",
                "/api/auth/register",
                "/api/chat/health",
                "/favicon.ico",
                "/static/"
        ));

        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
            String path = request.getRequestURI();
            if (EXCLUDED_PATHS.stream().anyMatch(path::startsWith)) {
                return true;
            }

            response.setHeader("Content-Security-Policy", cspValue());
            response.setHeader("X-Frame-Options", "DENY");
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("X-XSS-Protection", "1; mode=block");
            return true;
        }

        private String cspValue() {
            // 说明：策略中同时包含 nonce 与 'unsafe-inline' 时，浏览器会忽略 'unsafe-inline'，
            // 导致内联脚本必须携带与响应头完全一致的 nonce。而 nonce 每次请求都变化，
            // 一旦经过 Cloudflare/浏览器缓存（缓存的 HTML 里是旧 nonce，新响应头是新 nonce），
            // 所有内联脚本都会被拦截（switchRole is not defined）。
            // 这里移除 nonce，仅依赖 'unsafe-inline'，内联脚本在任何缓存场景下都能执行。
            return "default-src 'self'; " +
                    "script-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net https://cdnjs.cloudflare.com https://static.cloudflareinsights.com; " +
                    "script-src-elem 'self' 'unsafe-inline' https://cdn.jsdelivr.net https://cdnjs.cloudflare.com https://static.cloudflareinsights.com; " +
                    "script-src-attr 'unsafe-inline'; " +
                    "style-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net https://cdnjs.cloudflare.com https://gs.jurieo.com; " +
                    "img-src 'self' data: blob:; " +
                    "font-src 'self' data: https://cdn.jsdelivr.net https://cdnjs.cloudflare.com https://gs.jurieo.com; " +
                    "connect-src 'self' https://cdn.jsdelivr.net https://cdnjs.cloudflare.com https://static.cloudflareinsights.com; " +
                    "frame-ancestors 'none'; " +
                    "base-uri 'self'; " +
                    "form-action 'self'";
        }
    }
}
