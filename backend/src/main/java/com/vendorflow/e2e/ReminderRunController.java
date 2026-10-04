package com.vendorflow.e2e;

import com.vendorflow.organization.application.AuthorizationService;
import com.vendorflow.shared.tenant.TenantContext;
import com.vendorflow.organization.domain.Permission;
import com.vendorflow.reminder.ReminderService;
import com.vendorflow.shared.error.NotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Profile {@code e2e} only (bean does not exist otherwise; ProfileGuard forbids e2e + prod). Lets the full-stack E2E
 * run the reminder job right now for the caller's active organization, ignoring the 07:00 / once-per-day gates.
 * Same loopback guard as the mailbox. The caller must be logged in (CSRF header required like any POST) with the
 * organization-settings permission (OWNER/ADMIN).
 */
@RestController
@RequestMapping("/api/test/reminders")
@Profile("e2e")
public class ReminderRunController {

    public record RunView(boolean ran, int newLedgerRows, int digestsEnqueued) {
    }

    private final ReminderService reminders;
    private final AuthorizationService authorization;

    public ReminderRunController(ReminderService reminders, AuthorizationService authorization) {
        this.reminders = reminders;
        this.authorization = authorization;
    }

    @PostMapping("/run")
    public RunView run(HttpServletRequest request) {
        if (!isLoopback(request.getRemoteAddr())) {
            throw new NotFoundException("Not found");
        }
        TenantContext.Tenant tenant = authorization.require(Permission.ORG_SETTINGS_MANAGE);
        ReminderService.RunResult result = reminders.runOrganization(tenant.organizationId(), true);
        return new RunView(result.ran(), result.newLedgerRows(), result.digestsEnqueued());
    }

    private static boolean isLoopback(String remoteAddr) {
        try {
            return remoteAddr != null && InetAddress.getByName(remoteAddr).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
