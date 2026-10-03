package com.vendorflow.organization.api;

import com.vendorflow.organization.application.MembershipService;
import com.vendorflow.organization.domain.MemberView;
import com.vendorflow.shared.web.PageResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Members of the caller's ACTIVE organization. Membership ids of other organizations are 404. */
@RestController
@RequestMapping("/api/v1/organization/members")
public class MembersController {

    private final MembershipService service;

    public MembersController(MembershipService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<MemberView> list(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + PageResponse.DEFAULT_SIZE) int size) {
        return service.list(page, size);
    }

    @PatchMapping("/{membershipId}")
    public MemberView changeRole(@PathVariable UUID membershipId, @Valid @RequestBody ChangeRoleRequest request) {
        return service.changeRole(membershipId, request.role());
    }

    @DeleteMapping("/{membershipId}")
    public ResponseEntity<Void> remove(@PathVariable UUID membershipId) {
        service.remove(membershipId);
        return ResponseEntity.noContent().build();
    }
}
