package com.vendorflow.identity.application;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Absolute session lifetime. The idle timeout (spring.session.timeout) is sliding, so a stolen or forgotten session
 * that keeps being used would live forever; this caps it: a session older than
 * {@code app.security.session-absolute-timeout} (default 7 days) since the login/signup/invitation-accept that
 * authenticated it is invalidated, and the user must log in again.
 *
 * <p>Order -101: after Spring Session's filter (so {@code getSession} is the Spring Session) and BEFORE the Spring
 * Security chain (-100). Invalidating here means Security loads no context from the session, so the request is
 * anonymous and protected endpoints answer 401 through the normal entry point, while public endpoints (login,
 * signup) keep working for a user whose old cookie just expired.
 */
@Component
@Order(-101)
public class SessionLifetimeFilter extends OncePerRequestFilter {

    /** Session attribute: epoch millis of authentication. Set by {@link SessionService#start}. */
    public static final String AUTHENTICATED_AT = "VF_AUTHENTICATED_AT";

    private static final Logger log = LoggerFactory.getLogger(SessionLifetimeFilter.class);

    private final Clock clock;
    private final Duration maxAge;

    public SessionLifetimeFilter(Clock clock,
            @Value("${app.security.session-absolute-timeout:7d}") Duration maxAge) {
        this.clock = clock;
        this.maxAge = maxAge;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session != null && isExpired(session)) {
            log.info("Session invalidated: absolute lifetime exceeded");
            session.invalidate();
        }
        chain.doFilter(request, response);
    }

    private boolean isExpired(HttpSession session) {
        Object authenticatedAt = session.getAttribute(AUTHENTICATED_AT);
        if (authenticatedAt instanceof Long millis) {
            return clock.millis() - millis > maxAge.toMillis();
        }
        // An authenticated session without a timestamp is unexpected (every login path sets it): fail closed.
        return session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY) != null;
    }
}
