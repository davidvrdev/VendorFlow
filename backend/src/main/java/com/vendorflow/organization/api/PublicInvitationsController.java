package com.vendorflow.organization.api;

import com.vendorflow.identity.api.Me;
import com.vendorflow.organization.application.InvitationAcceptService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public (token is the credential); both are rate limited per IP. See SecurityConfig and RateLimitFilter. */
@RestController
@RequestMapping("/api/v1/invitations")
public class PublicInvitationsController {

    private final InvitationAcceptService service;

    public PublicInvitationsController(InvitationAcceptService service) {
        this.service = service;
    }

    @PostMapping("/lookup")
    public InvitationAcceptService.Lookup lookup(@Valid @RequestBody InvitationTokenRequest request) {
        return service.lookup(request.token());
    }

    @PostMapping("/accept")
    public Me accept(@Valid @RequestBody AcceptInvitationRequest request, HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        return service.accept(request, httpRequest, httpResponse);
    }
}
