package com.vendorflow.organization.api;

import com.vendorflow.organization.application.OrganizationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The caller's ACTIVE organization only; there is no organization id in the URL (it comes from TenantContext). */
@RestController
@RequestMapping("/api/v1/organization")
public class OrganizationController {

    private final OrganizationService service;

    public OrganizationController(OrganizationService service) {
        this.service = service;
    }

    @GetMapping
    public OrganizationView get() {
        return service.getActive();
    }

    @PatchMapping
    public OrganizationView update(@Valid @RequestBody UpdateOrganizationRequest request) {
        return service.updateActive(request);
    }
}
