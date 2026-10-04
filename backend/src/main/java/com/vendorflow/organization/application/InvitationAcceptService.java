package com.vendorflow.organization.application;

import com.vendorflow.audit.AuditService;
import com.vendorflow.identity.api.Me;
import com.vendorflow.identity.application.MeService;
import com.vendorflow.identity.application.SessionService;
import com.vendorflow.identity.application.TokenGenerator;
import com.vendorflow.identity.application.UserAccountService;
import com.vendorflow.organization.api.AcceptInvitationRequest;
import com.vendorflow.organization.domain.Invitation;
import com.vendorflow.shared.tenant.Role;
import com.vendorflow.organization.infrastructure.InvitationRepository;
import com.vendorflow.shared.error.ApiException;
import com.vendorflow.shared.error.FieldViolation;
import com.vendorflow.shared.error.NotFoundException;
import com.vendorflow.shared.error.RequestValidationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The two PUBLIC invitation endpoints. The token is the credential. Not @Transactional as a whole (like
 * AuthService): the session of a newly created account must only be started after the database work has committed.
 */
@Service
public class InvitationAcceptService {

    private static final Logger log = LoggerFactory.getLogger(InvitationAcceptService.class);

    public record Lookup(String organizationName, Role role, String email, boolean accountExists) {
    }

    private record Outcome(UUID userId, UUID organizationId, boolean newAccount) {
    }

    private final InvitationRepository invitations;
    private final MembershipService members;
    private final OrganizationService organizations;
    private final UserAccountService users;
    private final SessionService sessions;
    private final MeService meService;
    private final TokenGenerator tokens;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final Clock clock;

    public InvitationAcceptService(InvitationRepository invitations, MembershipService members,
            OrganizationService organizations, UserAccountService users, SessionService sessions,
            MeService meService, TokenGenerator tokens, AuditService audit,
            PlatformTransactionManager transactionManager, Clock clock) {
        this.invitations = invitations;
        this.members = members;
        this.organizations = organizations;
        this.users = users;
        this.sessions = sessions;
        this.meService = meService;
        this.tokens = tokens;
        this.audit = audit;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** @throws NotFoundException (404, same body) for unknown, expired, revoked and accepted tokens */
    public Lookup lookup(String rawToken) {
        return tx.execute(status -> {
            Invitation invitation = invitations.findByTokenHash(tokens.hash(rawToken))
                    .filter(i -> i.isPending(clock.instant()))
                    .orElseThrow(InvitationAcceptService::invalid);
            return new Lookup(organizations.nameOf(invitation.getOrganizationId()), invitation.getRole(),
                    invitation.getEmail(), users.findByEmail(invitation.getEmail()).isPresent());
        });
    }

    public Me accept(AcceptInvitationRequest request, HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        Optional<UUID> sessionUser = sessionUserId();
        Outcome outcome;
        try {
            outcome = tx.execute(status -> doAccept(request, sessionUser));
        } catch (DataIntegrityViolationException e) {
            if (sessionUser.isEmpty()) {
                // Somebody registered this email between our check and our insert: same answer as "account exists".
                throw signInToAccept();
            }
            throw e;
        }
        if (outcome.newAccount()) {
            sessions.start(outcome.userId(), outcome.organizationId(), httpRequest, httpResponse);
        } else {
            ActiveOrganizationSession.set(httpRequest.getSession(true), outcome.organizationId());
        }
        log.info("Invitation accepted: userId={} orgId={}", outcome.userId(), outcome.organizationId());
        return meService.build(outcome.userId(), outcome.organizationId());
    }

    private Outcome doAccept(AcceptInvitationRequest request, Optional<UUID> sessionUser) {
        Instant now = clock.instant();
        // Row lock: a concurrent accept of the same link waits here and then finds it already accepted (404).
        Invitation invitation = invitations.findByTokenHashForUpdate(tokens.hash(request.token()))
                .filter(i -> i.isPending(now))
                .orElseThrow(InvitationAcceptService::invalid);
        UUID orgId = invitation.getOrganizationId();

        UUID userId;
        boolean newAccount = false;
        if (sessionUser.isPresent()) {
            UserAccountService.UserSummary user = users.require(sessionUser.get());
            if (!user.email().equalsIgnoreCase(invitation.getEmail())) {
                throw new ApiException(HttpStatus.FORBIDDEN, "invitation-wrong-account", "Wrong account",
                        "This invitation was sent to a different email address. Sign in with that account.");
            }
            userId = user.id();
        } else {
            if (users.findByEmail(invitation.getEmail()).isPresent()) {
                throw signInToAccept();
            }
            requireNewAccountFields(request);
            userId = users.createVerifiedUser(invitation.getEmail(), request.fullName(), request.password());
            newAccount = true;
        }

        Optional<UUID> membershipId = members.join(orgId, userId, invitation.getRole());
        invitation.markAccepted(now);
        users.rememberActiveOrganization(userId, orgId);
        audit.record(orgId, userId, "invitation.accepted", "invitation", invitation.getId(),
                Map.of("role", invitation.getRole().name(), "newAccount", newAccount));
        membershipId.ifPresent(id -> audit.record(orgId, userId, "membership.created", "membership", id,
                Map.of("role", invitation.getRole().name(), "via", "invitation")));
        return new Outcome(userId, orgId, newAccount);
    }

    private static void requireNewAccountFields(AcceptInvitationRequest request) {
        List<FieldViolation> errors = new ArrayList<>();
        if (request.fullName() == null || request.fullName().isBlank()) {
            errors.add(new FieldViolation("fullName", "is required to create your account"));
        }
        if (request.password() == null || request.password().isEmpty()) {
            errors.add(new FieldViolation("password", "is required to create your account"));
        }
        if (!errors.isEmpty()) {
            throw new RequestValidationException(errors);
        }
    }

    /** The authenticated user of this request, if any (the endpoint is public, so there may be none). */
    private static Optional<UUID> sessionUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(auth.getName()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static NotFoundException invalid() {
        return new NotFoundException("Invitation not found.");
    }

    private static ApiException signInToAccept() {
        return new ApiException(HttpStatus.CONFLICT, "sign-in-to-accept", "Sign in to accept",
                "An account with this email already exists. Sign in to accept the invitation.");
    }
}
