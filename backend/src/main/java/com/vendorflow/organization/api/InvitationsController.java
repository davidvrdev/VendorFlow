package com.vendorflow.organization.api;

import com.vendorflow.organization.application.InvitationService;
import com.vendorflow.organization.domain.InvitationView;
import com.vendorflow.shared.web.PageResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Invitation management for the caller's ACTIVE organization (MEMBERS_MANAGE). */
@RestController
@RequestMapping("/api/v1/organization/invitations")
public class InvitationsController {

    private final InvitationService service;

    public InvitationsController(InvitationService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<InvitationView> list(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + PageResponse.DEFAULT_SIZE) int size) {
        return service.listPending(page, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InvitationView create(@Valid @RequestBody CreateInvitationRequest request) {
        return service.create(request.email(), request.role());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revoke(@PathVariable UUID id) {
        service.revoke(id);
        return ResponseEntity.noContent().build();
    }
}
