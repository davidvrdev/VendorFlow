package com.vendorflow.organization.application;

import com.vendorflow.shared.tenant.TenantContext;

import com.vendorflow.audit.AuditService;
import com.vendorflow.identity.application.UserAccountService;
import com.vendorflow.organization.domain.MemberView;
import com.vendorflow.organization.domain.Membership;
import com.vendorflow.organization.domain.Permission;
import com.vendorflow.shared.tenant.Role;
import com.vendorflow.organization.domain.RolePermissions;
import com.vendorflow.organization.infrastructure.MembershipRepository;
import com.vendorflow.shared.error.ApiException;
import com.vendorflow.shared.error.NotFoundException;
import com.vendorflow.shared.web.PageResponse;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Members of the ACTIVE organization: list, change role, remove/leave.
 *
 * <p>Rules (docs/API.md "Members"):
 * <ul>
 *   <li>OWNER may set any role and remove anyone.</li>
 *   <li>ADMIN may only touch MEMBER/VIEWER memberships and only assign MEMBER or VIEWER.</li>
 *   <li>MEMBER/VIEWER cannot change roles; they may remove only their own membership (leave).</li>
 *   <li>Nobody changes their own role. Anyone may leave, except the last OWNER.</li>
 *   <li>An organization always keeps at least one OWNER, also under concurrency (row locks, see below).</li>
 * </ul>
 */
@Service
public class MembershipService {

    private final MembershipRepository memberships;
    private final UserAccountService users;
    private final AuthorizationService authorization;
    private final TenantContext tenantContext;
    private final AuditService audit;
    private final Clock clock;

    public MembershipService(MembershipRepository memberships, UserAccountService users,
            AuthorizationService authorization, TenantContext tenantContext, AuditService audit, Clock clock) {
        this.memberships = memberships;
        this.users = users;
        this.authorization = authorization;
        this.tenantContext = tenantContext;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<MemberView> list(int page, int size) {
        TenantContext.Tenant tenant = authorization.require(Permission.MEMBERS_VIEW);
        return PageResponse.of(memberships.findMembers(tenant.organizationId(), PageResponse.pageable(page, size)));
    }

    @Transactional
    public MemberView changeRole(UUID membershipId, Role newRole) {
        TenantContext.Tenant tenant = authorization.require(Permission.MEMBERS_MANAGE);
        UUID orgId = tenant.organizationId();
        // Lock first, read after: whoever gets the lock second sees the first one's committed result.
        List<Membership> owners = memberships.lockByRole(orgId, Role.OWNER);
        Membership target = find(orgId, membershipId);
        Role actorRole = freshRole(tenant, Permission.MEMBERS_MANAGE);

        if (target.getUserId().equals(tenant.userId())) {
            throw forbidden("You cannot change your own role. Ask another owner to do it.");
        }
        if (actorRole != Role.OWNER && (isPrivileged(target.getRole()) || isPrivileged(newRole))) {
            throw forbidden("Only an owner can grant or change the owner and admin roles.");
        }
        Role before = target.getRole();
        if (before == newRole) {
            return view(orgId, target.getId());
        }
        if (before == Role.OWNER && owners.size() <= 1) {
            throw lastOwner();
        }
        target.changeRole(newRole, clock.instant());
        audit.record("membership.role_changed", "membership", target.getId(), metadata(target, "before", before,
                "after", newRole));
        memberships.flush();
        return view(orgId, target.getId());
    }

    /** Removes someone, or (when it is the caller's own membership) leaves the organization. */
    @Transactional
    public void remove(UUID membershipId) {
        TenantContext.Tenant tenant = tenantContext.require();
        UUID orgId = tenant.organizationId();
        List<Membership> owners = memberships.lockByRole(orgId, Role.OWNER);
        Membership target = find(orgId, membershipId);
        boolean leaving = target.getUserId().equals(tenant.userId());

        if (!leaving) {
            Role actorRole = freshRole(tenant, Permission.MEMBERS_MANAGE);
            if (actorRole != Role.OWNER && isPrivileged(target.getRole())) {
                throw forbidden("Only an owner can remove an owner or an admin.");
            }
        }
        if (target.getRole() == Role.OWNER && owners.size() <= 1) {
            throw lastOwner();
        }
        UUID userId = target.getUserId();
        Role role = target.getRole();
        memberships.delete(target);
        memberships.flush();
        // Their next request loses the tenant context by itself (TenantContextFilter re-verifies membership); this
        // only keeps "last active organization" from pointing at somewhere they no longer belong.
        users.clearLastActiveOrganization(userId, orgId);
        audit.record(leaving ? "membership.left" : "membership.removed", "membership", membershipId,
                Map.of("userId", userId.toString(), "role", role.name()));
    }

    /**
     * Adds a member (used when an invitation is accepted). Returns the new membership id, or empty if the user
     * already belongs to the organization (their existing role is left untouched).
     */
    @Transactional
    public Optional<UUID> join(UUID organizationId, UUID userId, Role role) {
        if (memberships.findByOrganizationIdAndUserId(organizationId, userId).isPresent()) {
            return Optional.empty();
        }
        return Optional.of(memberships.saveAndFlush(new Membership(organizationId, userId, role, clock.instant()))
                .getId());
    }

    @Transactional(readOnly = true)
    public boolean isMemberByEmail(UUID organizationId, String email) {
        return memberships.countByOrganizationAndEmail(organizationId, email) > 0;
    }

    private Membership find(UUID orgId, UUID membershipId) {
        return memberships.findByIdAndOrganizationId(membershipId, orgId)
                .orElseThrow(() -> new NotFoundException("Member not found."));
    }

    /**
     * The actor's role as of NOW (inside the locked transaction), not the role the request filter saw: a concurrent
     * demotion that committed after the filter ran must take effect here.
     */
    private Role freshRole(TenantContext.Tenant tenant, Permission required) {
        Role role = memberships.findByOrganizationIdAndUserId(tenant.organizationId(), tenant.userId())
                .map(Membership::getRole).orElseThrow(() -> forbidden("You are no longer a member."));
        if (!RolePermissions.has(role, required)) {
            throw forbidden("You do not have permission to manage members.");
        }
        return role;
    }

    private MemberView view(UUID orgId, UUID membershipId) {
        return memberships.findMember(orgId, membershipId)
                .orElseThrow(() -> new NotFoundException("Member not found."));
    }

    private static boolean isPrivileged(Role role) {
        return role == Role.OWNER || role == Role.ADMIN;
    }

    private static Map<String, Object> metadata(Membership target, String k1, Role v1, String k2, Role v2) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userId", target.getUserId().toString());
        m.put(k1, v1.name());
        m.put(k2, v2.name());
        return m;
    }

    private static ApiException forbidden(String detail) {
        return new ApiException(HttpStatus.FORBIDDEN, "forbidden", "Access denied", detail);
    }

    private static ApiException lastOwner() {
        return new ApiException(HttpStatus.CONFLICT, "last-owner", "Last owner",
                "An organization must keep at least one owner. Make someone else an owner first.");
    }
}
