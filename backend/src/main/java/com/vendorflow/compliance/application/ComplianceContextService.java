package com.vendorflow.compliance.application;

import com.vendorflow.organization.application.OrganizationService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The single place that turns (clock, organization settings) into "today" and the expiring window. Nothing is stored:
 * changing the time zone or window in settings applies on the next request.
 */
@Service
public class ComplianceContextService {

    private final OrganizationService organizations;
    private final Clock clock;

    public ComplianceContextService(OrganizationService organizations, Clock clock) {
        this.organizations = organizations;
        this.clock = clock;
    }

    public ComplianceContext forOrganization(UUID organizationId) {
        OrganizationService.ComplianceSettings s = organizations.complianceSettings(organizationId);
        return new ComplianceContext(todayIn(s.timeZone()), s.expiringWindowDays());
    }

    public LocalDate todayIn(String timeZone) {
        return LocalDate.now(clock.withZone(ZoneId.of(timeZone)));
    }
}
