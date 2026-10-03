package com.vendorflow.organization.application;

import com.vendorflow.audit.AuditService;
import com.vendorflow.organization.api.OrganizationView;
import com.vendorflow.organization.api.UpdateOrganizationRequest;
import com.vendorflow.organization.domain.Membership;
import com.vendorflow.organization.domain.Organization;
import com.vendorflow.organization.domain.OrganizationSummary;
import com.vendorflow.organization.domain.Permission;
import com.vendorflow.organization.domain.Role;
import com.vendorflow.organization.infrastructure.MembershipRepository;
import com.vendorflow.organization.infrastructure.OrganizationRepository;
import com.vendorflow.shared.error.FieldViolation;
import com.vendorflow.shared.error.NotFoundException;
import com.vendorflow.shared.error.RequestValidationException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrganizationService {

    private static final Comparator<OrganizationSummary> BY_NAME = Comparator
            .comparing(OrganizationSummary::name, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(OrganizationSummary::name)
            .thenComparing(OrganizationSummary::id);

    private final OrganizationRepository organizations;
    private final MembershipRepository memberships;
    private final TenantContext tenantContext;
    private final AuthorizationService authorization;
    private final AuditService audit;
    private final Clock clock;

    public OrganizationService(OrganizationRepository organizations, MembershipRepository memberships,
            TenantContext tenantContext, AuthorizationService authorization, AuditService audit, Clock clock) {
        this.organizations = organizations;
        this.memberships = memberships;
        this.tenantContext = tenantContext;
        this.authorization = authorization;
        this.audit = audit;
        this.clock = clock;
    }

    /** Called by signup inside its transaction (an organization never exists without an OWNER). */
    @Transactional
    public UUID createWithOwner(String name, UUID ownerUserId) {
        Instant now = clock.instant();
        Organization org = organizations.save(new Organization(name.trim(), now));
        memberships.save(new Membership(org.getId(), ownerUserId, Role.OWNER, now));
        audit.record(org.getId(), ownerUserId, "organization.created", "organization", org.getId(),
                Map.of("name", org.getName()));
        return org.getId();
    }

    /** The caller's active organization. Any member may read it (no extra permission). */
    @Transactional(readOnly = true)
    public OrganizationView getActive() {
        TenantContext.Tenant tenant = tenantContext.require();
        return OrganizationView.from(load(tenant.organizationId()));
    }

    @Transactional
    public OrganizationView updateActive(UpdateOrganizationRequest request) {
        TenantContext.Tenant tenant = authorization.require(Permission.ORG_SETTINGS_MANAGE);
        validate(request);
        Organization org = load(tenant.organizationId());

        Map<String, Object> changes = new LinkedHashMap<>();
        if (request.name() != null && !request.name().trim().equals(org.getName())) {
            changes.put("name", change(org.getName(), request.name().trim()));
            org.setName(request.name().trim());
        }
        if (request.timeZone() != null && !request.timeZone().equals(org.getTimeZone())) {
            changes.put("timeZone", change(org.getTimeZone(), request.timeZone()));
            org.setTimeZone(request.timeZone());
        }
        if (request.expiringWindowDays() != null && request.expiringWindowDays() != org.getExpiringWindowDays()) {
            changes.put("expiringWindowDays", change(org.getExpiringWindowDays(), request.expiringWindowDays()));
            org.setExpiringWindowDays(request.expiringWindowDays());
        }
        if (request.reminderOffsetsDays() != null) {
            int[] offsets = request.reminderOffsetsDays().stream().mapToInt(Integer::intValue).toArray();
            if (!Arrays.equals(offsets, org.getReminderOffsetsDays())) {
                changes.put("reminderOffsetsDays", change(toList(org.getReminderOffsetsDays()), toList(offsets)));
                org.setReminderOffsetsDays(offsets);
            }
        }
        if (request.remindersEnabled() != null && request.remindersEnabled() != org.isRemindersEnabled()) {
            changes.put("remindersEnabled", change(org.isRemindersEnabled(), request.remindersEnabled()));
            org.setRemindersEnabled(request.remindersEnabled());
        }
        if (!changes.isEmpty()) {
            org.touch(clock.instant());
            audit.record("organization.updated", "organization", org.getId(), Map.of("changes", changes));
        }
        return OrganizationView.from(org);
    }

    /** All organizations of a user, sorted by name (case-insensitive). */
    @Transactional(readOnly = true)
    public List<OrganizationSummary> listForUser(UUID userId) {
        return memberships.findAllSummariesByUserId(userId).stream().sorted(BY_NAME).toList();
    }

    /** Membership check used when switching organization: not a member or nonexistent are indistinguishable (404). */
    @Transactional(readOnly = true)
    public OrganizationSummary requireMembership(UUID userId, UUID organizationId) {
        return memberships.findSummary(userId, organizationId)
                .orElseThrow(() -> new NotFoundException("Organization not found."));
    }

    /** Login rule: last active organization if still a member, else first by name, else none. */
    @Transactional(readOnly = true)
    public Optional<UUID> resolveActiveOrganization(UUID userId, UUID lastActiveOrganizationId) {
        List<OrganizationSummary> mine = listForUser(userId);
        if (lastActiveOrganizationId != null
                && mine.stream().anyMatch(o -> o.id().equals(lastActiveOrganizationId))) {
            return Optional.of(lastActiveOrganizationId);
        }
        return mine.stream().findFirst().map(OrganizationSummary::id);
    }

    private Organization load(UUID organizationId) {
        return organizations.findById(organizationId)
                .orElseThrow(() -> new NotFoundException("Organization not found."));
    }

    private void validate(UpdateOrganizationRequest request) {
        List<FieldViolation> errors = new ArrayList<>();
        if (request.name() != null && request.name().isBlank()) {
            errors.add(new FieldViolation("name", "must not be blank"));
        }
        if (request.timeZone() != null && !ZoneId.getAvailableZoneIds().contains(request.timeZone())) {
            errors.add(new FieldViolation("timeZone", "must be a valid IANA time zone"));
        }
        if (request.reminderOffsetsDays() != null) {
            List<Integer> offsets = request.reminderOffsetsDays();
            Set<Integer> distinct = offsets.stream().collect(Collectors.toSet());
            if (distinct.size() != offsets.size()) {
                errors.add(new FieldViolation("reminderOffsetsDays", "must not contain duplicates"));
            }
        }
        if (!errors.isEmpty()) {
            throw new RequestValidationException(errors);
        }
    }

    private static Map<String, Object> change(Object before, Object after) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("before", before);
        m.put("after", after);
        return m;
    }

    private static List<Integer> toList(int[] values) {
        return Arrays.stream(values).boxed().toList();
    }
}
