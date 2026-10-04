package com.vendorflow.organization.application;

import com.vendorflow.shared.tenant.TenantContext;

import com.vendorflow.audit.AuditService;
import com.vendorflow.identity.application.TokenGenerator;
import com.vendorflow.identity.application.UserAccountService;
import com.vendorflow.notification.NotificationKind;
import com.vendorflow.notification.OutboxService;
import com.vendorflow.organization.domain.Invitation;
import com.vendorflow.organization.domain.InvitationView;
import com.vendorflow.organization.domain.Permission;
import com.vendorflow.shared.tenant.Role;
import com.vendorflow.organization.infrastructure.InvitationRepository;
import com.vendorflow.shared.error.ApiException;
import com.vendorflow.shared.error.NotFoundException;
import com.vendorflow.shared.error.RequestValidationException;
import com.vendorflow.shared.web.PageResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Managing invitations of the ACTIVE organization (MEMBERS_MANAGE). Accepting/looking up is InvitationAcceptService. */
@Service
public class InvitationService {

    static final Duration INVITATION_TTL = Duration.ofDays(7);

    private final InvitationRepository invitations;
    private final MembershipService members;
    private final OrganizationService organizations;
    private final UserAccountService users;
    private final AuthorizationService authorization;
    private final TokenGenerator tokens;
    private final AuditService audit;
    private final OutboxService outbox;
    private final Clock clock;

    public InvitationService(InvitationRepository invitations, MembershipService members,
            OrganizationService organizations, UserAccountService users, AuthorizationService authorization,
            TokenGenerator tokens, AuditService audit, OutboxService outbox, Clock clock) {
        this.invitations = invitations;
        this.members = members;
        this.organizations = organizations;
        this.users = users;
        this.authorization = authorization;
        this.tokens = tokens;
        this.audit = audit;
        this.outbox = outbox;
        this.clock = clock;
    }

    /** Pending only: not accepted, not revoked, not expired. Newest first. */
    @Transactional(readOnly = true)
    public PageResponse<InvitationView> listPending(int page, int size) {
        TenantContext.Tenant tenant = authorization.require(Permission.MEMBERS_MANAGE);
        return PageResponse.of(invitations.findPending(tenant.organizationId(), clock.instant(),
                PageResponse.pageable(page, size)));
    }

    @Transactional
    public InvitationView create(String rawEmail, Role role) {
        TenantContext.Tenant tenant = authorization.require(Permission.MEMBERS_MANAGE);
        if (role == Role.OWNER) {
            throw new RequestValidationException("role", "must be one of ADMIN, MEMBER, VIEWER");
        }
        if (role == Role.ADMIN && tenant.role() != Role.OWNER) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "Access denied",
                    "Only an owner can invite someone as an admin.");
        }
        UserAccountService.UserSummary inviter = users.require(tenant.userId());
        if (!inviter.emailVerified()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "email-not-verified", "Email not verified",
                    "Verify your email address before inviting others.");
        }
        UUID orgId = tenant.organizationId();
        String email = rawEmail.trim();
        if (members.isMemberByEmail(orgId, email)) {
            throw new ApiException(HttpStatus.CONFLICT, "already-member", "Already a member",
                    "This person is already a member of the organization.");
        }
        Instant now = clock.instant();
        Optional<Invitation> open = invitations.findOpenByEmail(orgId, email);
        if (open.isPresent()) {
            if (open.get().isPending(now)) {
                throw alreadyPending();
            }
            // Expired but never revoked: it still occupies the unique "pending" slot, so retire it first. Flush
            // explicitly: Hibernate would otherwise run the INSERT before this UPDATE and hit the unique index.
            open.get().revoke(now);
            invitations.flush();
        }

        String rawToken = tokens.newToken();
        Invitation invitation = new Invitation(orgId, email, role, tokens.hash(rawToken), tenant.userId(),
                now.plus(INVITATION_TTL), now);
        try {
            // Flush now so a concurrent duplicate (partial unique index) surfaces here as a clean 409.
            invitations.saveAndFlush(invitation);
        } catch (DataIntegrityViolationException e) {
            throw alreadyPending();
        }
        outbox.enqueue(NotificationKind.INVITATION, orgId, email, "invitation:" + invitation.getId(),
                Map.of("token", rawToken, "organizationName", organizations.nameOf(orgId),
                        "inviterName", inviter.fullName(), "role", role.name()));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("email", email);
        metadata.put("role", role.name());
        audit.record("invitation.created", "invitation", invitation.getId(), metadata);
        return new InvitationView(invitation.getId(), email, role, invitation.getExpiresAt(), now,
                new InvitationView.InvitedBy(inviter.fullName()));
    }

    /** Foreign, unknown and already accepted/revoked ids are all 404. An expired-but-unrevoked row can be revoked. */
    @Transactional
    public void revoke(UUID invitationId) {
        TenantContext.Tenant tenant = authorization.require(Permission.MEMBERS_MANAGE);
        Invitation invitation = invitations.findByIdAndOrganizationId(invitationId, tenant.organizationId())
                .filter(Invitation::isOpen)
                .orElseThrow(() -> new NotFoundException("Invitation not found."));
        invitation.revoke(clock.instant());
        audit.record("invitation.revoked", "invitation", invitation.getId(), Map.of("email", invitation.getEmail()));
    }

    private static ApiException alreadyPending() {
        return new ApiException(HttpStatus.CONFLICT, "invitation-pending", "Invitation already pending",
                "There is already a pending invitation for this email address.");
    }
}
