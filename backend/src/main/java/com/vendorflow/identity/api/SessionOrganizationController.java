package com.vendorflow.identity.api;

import com.vendorflow.identity.application.AuthService;
import com.vendorflow.identity.application.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/session")
public class SessionOrganizationController {

    private final AuthService authService;

    public SessionOrganizationController(AuthService authService) {
        this.authService = authService;
    }

    /** The only endpoint that accepts an organization id from the client; membership is verified (else 404). */
    @PostMapping("/organization")
    public Me switchOrganization(@Valid @RequestBody SwitchOrganizationRequest request,
            HttpServletRequest httpRequest) {
        return authService.switchOrganization(CurrentUser.id(), request.organizationId(), httpRequest);
    }
}
