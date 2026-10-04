package com.vendorflow.e2e;

import com.vendorflow.chasing.application.ChasingService;
import com.vendorflow.organization.application.AuthorizationService;
import com.vendorflow.organization.domain.Permission;
import com.vendorflow.shared.error.NotFoundException;
import com.vendorflow.shared.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Profile {@code e2e} only (same rules as {@link ReminderRunController}): runs the chasing tick right now for the
 * caller's active organization instead of waiting for the hourly cron. The send-hour gate is NOT bypassed (the job is
 * "at or after sendHour", so the E2E sets the hour to 0); idempotency (one chase per vendor per local day) still applies.
 */
@RestController
@RequestMapping("/api/test/chasing")
@Profile("e2e")
public class ChasingRunController {

    public record RunView(boolean ran, int chased, int skippedSameDay, int exhaustedNotified) {
    }

    private final ChasingService chasing;
    private final AuthorizationService authorization;

    public ChasingRunController(ChasingService chasing, AuthorizationService authorization) {
        this.chasing = chasing;
        this.authorization = authorization;
    }

    @PostMapping("/run")
    public RunView run(HttpServletRequest request) {
        if (!isLoopback(request.getRemoteAddr())) {
            throw new NotFoundException("Not found");
        }
        TenantContext.Tenant tenant = authorization.require(Permission.ORG_SETTINGS_MANAGE);
        ChasingService.RunResult result = chasing.runOrganization(tenant.organizationId());
        return new RunView(result.ran(), result.chased(), result.skippedSameDay(), result.exhaustedNotified());
    }

    private static boolean isLoopback(String remoteAddr) {
        try {
            return remoteAddr != null && InetAddress.getByName(remoteAddr).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
