package com.anniversary.video.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class RateLimitConfig {

    /** IP당 1분에 최대 5회 주문 생성 허용 */
    private static final int MAX_REQUESTS = 5;
    private static final long WINDOW_MS = 60_000L;

    private final ConcurrentHashMap<String, RateInfo> ratemap = new ConcurrentHashMap<>();

    @Bean
    public FilterRegistrationBean<Filter> rateLimitFilter() {
        FilterRegistrationBean<Filter> reg = new FilterRegistrationBean<>();
        reg.setFilter(new Filter() {
            @Override
            public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
                    throws IOException, ServletException {
                HttpServletRequest httpReq = (HttpServletRequest) req;
                String path = httpReq.getRequestURI();
                String method = httpReq.getMethod();

                // 주문 생성 POST만 제한
                if ("POST".equals(method) && "/api/orders".equals(path)) {
                    String ip = getClientIp(httpReq);
                    if (!allowRequest(ip)) {
                        HttpServletResponse httpRes = (HttpServletResponse) res;
                        httpRes.setStatus(429);
                        httpRes.setContentType("application/json;charset=UTF-8");
                        httpRes.getWriter().write(
                                "{\"message\":\"요청이 너무 많습니다. 잠시 후 다시 시도해주세요.\"}");
                        return;
                    }
                }
                chain.doFilter(req, res);
            }
        });
        reg.addUrlPatterns("/api/orders");
        reg.setOrder(1);
        return reg;
    }

    private boolean allowRequest(String ip) {
        long now = System.currentTimeMillis();
        ratemap.compute(ip, (k, info) -> {
            if (info == null || now - info.windowStart > WINDOW_MS) {
                return new RateInfo(now, new AtomicInteger(1));
            }
            info.count.incrementAndGet();
            return info;
        });
        // 오래된 엔트리 정리 (1000개 넘으면)
        if (ratemap.size() > 1000) {
            ratemap.entrySet().removeIf(e -> now - e.getValue().windowStart > WINDOW_MS * 2);
        }
        return ratemap.get(ip).count.get() <= MAX_REQUESTS;
    }

    private String getClientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }

    private static class RateInfo {
        final long windowStart;
        final AtomicInteger count;
        RateInfo(long windowStart, AtomicInteger count) {
            this.windowStart = windowStart;
            this.count = count;
        }
    }
}
