package com.vendorflow.shared.ratelimit;

import com.vendorflow.shared.error.Problems;
import com.vendorflow.shared.security.ProblemJsonWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UrlPathHelper;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Applies per-IP limits to the abuse-prone public endpoints before any authentication/CSRF work is done (so a flood
 * costs us as little as possible). Responds 429 problem+json with Retry-After.
 *
 * <p>Client address: the servlet remote address. With {@code server.forward-headers-strategy=native} Tomcat's
 * RemoteIpValve has already replaced it with the real client IP, honouring X-Forwarded-For only from trusted proxies
 * (docs/SECURITY.md section 9); this class must never read X-Forwarded-For itself.
 *
 * <p>Path matching is done on the decoded, normalized path ({@link #normalize}), never on the raw request URI:
 * {@code /api/v1/auth/%6cogin} routes to the login handler, so it must be counted as login.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    /** POST path (normalized, lower case) -> rule name (see RateLimitProperties for the limits). */
    private static final Map<String, String> RULES = Map.of(
            "/api/v1/auth/login", "login",
            "/api/v1/auth/signup", "signup",
            "/api/v1/auth/password-reset/request", "password-reset-request",
            "/api/v1/invitations/lookup", "invitation",
            "/api/v1/invitations/accept", "invitation",
            "/api/v1/auth/resend-verification", "resend-verification");

    /** POST paths with a variable segment (normalized, lower case): pattern -> rule name. */
    private static final Map<Pattern, String> PATTERN_RULES = Map.of(
            Pattern.compile("/api/v1/vendors/[^/]+/documents"), "document-upload");

    private final RateLimiter limiter;
    private final ProblemJsonWriter writer;

    public RateLimitFilter(RateLimiter limiter, ProblemJsonWriter writer) {
        this.limiter = limiter;
        this.writer = writer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String rule = HttpMethod.POST.matches(request.getMethod()) ? ruleFor(normalize(request)) : null;
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

    private static String ruleFor(String path) {
        String rule = RULES.get(path);
        if (rule != null) {
            return rule;
        }
        for (Map.Entry<Pattern, String> entry : PATTERN_RULES.entrySet()) {
            if (entry.getKey().matcher(path).matches()) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * Path within the application, percent-decoded once (as the router does), with ";params" and duplicate slashes
     * removed (UrlPathHelper), dot segments resolved, trailing slashes dropped and lower-cased. Routing is
     * case-sensitive and does not match a trailing slash, so most variants never reach a handler; we count them
     * anyway so no variant can be used to probe around the budget.
     */
    static String normalize(HttpServletRequest request) {
        String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        path = StringUtils.cleanPath(path).toLowerCase(Locale.ROOT);
        int end = path.length();
        while (end > 1 && path.charAt(end - 1) == '/') {
            end--;
        }
        return path.substring(0, end);
    }
}
