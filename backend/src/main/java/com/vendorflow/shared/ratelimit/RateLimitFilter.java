package com.vendorflow.shared.ratelimit;

import com.vendorflow.shared.error.Problems;
import com.vendorflow.shared.security.ProblemJsonWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Applies per-IP limits to the abuse-prone public endpoints before any authentication/CSRF work is done (so a flood
 * costs us as little as possible). Responds 429 problem+json with Retry-After. The client address is the servlet
 * remote address: behind a reverse proxy that must be the real client (see the risk note in the Phase 1 report).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    /** POST path -> rule name (see RateLimitProperties for the limits). */
    private static final Map<String, String> RULES = Map.of(
            "/api/v1/auth/login", "login",
            "/api/v1/auth/signup", "signup",
            "/api/v1/auth/password-reset/request", "password-reset-request",
            "/api/v1/invitations/lookup", "invitation",
            "/api/v1/invitations/accept", "invitation",
            "/api/v1/auth/resend-verification", "resend-verification");

    private final RateLimiter limiter;
    private final ProblemJsonWriter writer;

    public RateLimitFilter(RateLimiter limiter, ProblemJsonWriter writer) {
        this.limiter = limiter;
        this.writer = writer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String rule = HttpMethod.POST.matches(request.getMethod()) ? RULES.get(request.getRequestURI()) : null;
        if (rule != null) {
            RateLimiter.Decision decision = limiter.tryAcquire(rule, request.getRemoteAddr());
            if (!decision.allowed()) {
                log.warn("Rate limit exceeded: rule={}", rule);
                response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
                writer.write(response, Problems.tooManyRequests(), request.getRequestURI());
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
