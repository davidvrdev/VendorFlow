package com.vendorflow.identity.api;

import com.vendorflow.identity.application.CurrentUser;
import com.vendorflow.identity.application.MeService;
import com.vendorflow.shared.tenant.TenantContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    private final MeService meService;
    private final TenantContext tenantContext;

    public MeController(MeService meService, TenantContext tenantContext) {
        this.meService = meService;
        this.tenantContext = tenantContext;
    }

    /** Not a tenant endpoint: works with no active organization (activeOrganization is then null). */
    @GetMapping
    public Me me() {
        return meService.build(CurrentUser.id(),
                tenantContext.current().map(TenantContext.Tenant::organizationId).orElse(null));
    }
}
