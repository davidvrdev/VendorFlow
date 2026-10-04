package com.vendorflow.identity.api;

import com.vendorflow.identity.application.CurrentUser;
import com.vendorflow.identity.application.MeService;
import com.vendorflow.shared.tenant.TenantContext;
import com.vendorflow.identity.application.PasswordChangeService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    private final MeService meService;
    private final TenantContext tenantContext;
    private final PasswordChangeService passwordChange;

    public MeController(MeService meService, TenantContext tenantContext, PasswordChangeService passwordChange) {
        this.passwordChange = passwordChange;
        this.meService = meService;
        this.tenantContext = tenantContext;
    }

    /** Not a tenant endpoint: works with no active organization (activeOrganization is then null). */
    @GetMapping
    public Me me() {
        return meService.build(CurrentUser.id(),
                tenantContext.current().map(TenantContext.Tenant::organizationId).orElse(null));
    }

    /**
     * Own password only (user id from the session). 204; other sessions are revoked, this one gets a new id and CSRF
     * token (the client must re-read the XSRF-TOKEN cookie). Wrong current password: 400, and it counts toward lockout.
     */
    @PostMapping("/password")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest body,
            HttpServletRequest request, HttpServletResponse response) {
        passwordChange.change(CurrentUser.id(), body.currentPassword(), body.newPassword(), request, response);
        return ResponseEntity.noContent().build();
    }
}
