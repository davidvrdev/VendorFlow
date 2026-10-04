package com.vendorflow.identity.application;

import com.vendorflow.organization.application.ActiveOrganizationSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;

/**
 * Creates the authenticated server-side session after a successful login/signup.
 *
 * <p>The principal is the user id as a String, so {@code Authentication#getName()} is the user id and Spring Session
 * indexes the session by it (principal_name column): all sessions of a user can be found and revoked later.
 * No roles are stored in the session: authorization data comes from the database on every request.
 */
@Service
public class SessionService {

    private final SecurityContextRepository securityContextRepository;
    private final CookieCsrfTokenRepository csrfTokenRepository;
    private final FindByIndexNameSessionRepository<? extends Session> sessionRepository;
    private final Clock clock;
    private final SecurityContextHolderStrategy holder = SecurityContextHolder.getContextHolderStrategy();

    public SessionService(SecurityContextRepository securityContextRepository,
            CookieCsrfTokenRepository csrfTokenRepository,
            FindByIndexNameSessionRepository<? extends Session> sessionRepository, Clock clock) {
        this.clock = clock;
        this.sessionRepository = sessionRepository;
        this.securityContextRepository = securityContextRepository;
        this.csrfTokenRepository = csrfTokenRepository;
    }

    public void start(UUID userId, UUID activeOrganizationId, HttpServletRequest request,
            HttpServletResponse response) {
        // Session fixation: if the client already presented a session, give it a NEW id (Spring Session JDBC
        // supports changeSessionId). A client without a session simply gets a fresh one below.
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(userId.toString(), null,
                AuthorityUtils.createAuthorityList("ROLE_USER"));
        SecurityContext context = holder.createEmptyContext();
        context.setAuthentication(authentication);
        holder.setContext(context);
        securityContextRepository.saveContext(context, request, response); // creates the session if needed

        HttpSession session = request.getSession(true);
        // Start of the absolute session lifetime (SessionLifetimeFilter).
        session.setAttribute(SessionLifetimeFilter.AUTHENTICATED_AT, clock.millis());
        if (activeOrganizationId != null) {
            ActiveOrganizationSession.set(session, activeOrganizationId);
        } else {
            ActiveOrganizationSession.clear(session);
        }
        // Rotate the CSRF token on authentication (what Spring's CsrfAuthenticationStrategy does for form login).
        // The new XSRF-TOKEN cookie is on THIS response; clients must re-read the cookie after login/signup.
        csrfTokenRepository.saveToken(csrfTokenRepository.generateToken(request), request, response);
    }

    /**
     * Revokes every server-side session of a user (password reset). Sessions are indexed by principal name, which is
     * the user id. Joins the caller's transaction when there is one, so the password change and the revocation
     * commit together.
     */
    public void endAllForUser(UUID userId) {
        sessionRepository.findByPrincipalName(userId.toString()).keySet().forEach(sessionRepository::deleteById);
    }

    /** Revokes every session of the user EXCEPT the given one (password change: the actor stays signed in). */
    public void endAllExcept(UUID userId, String keepSessionId) {
        sessionRepository.findByPrincipalName(userId.toString()).keySet().stream()
                .filter(id -> !id.equals(keepSessionId)).forEach(sessionRepository::deleteById);
    }

    /** New session id and CSRF token for the current session (privilege change), same as after login. */
    public void rotate(HttpServletRequest request, HttpServletResponse response) {
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        csrfTokenRepository.saveToken(csrfTokenRepository.generateToken(request), request, response);
    }

    public void end(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        holder.clearContext();
    }
}
