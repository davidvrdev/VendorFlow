package com.vendorflow.portal.api;

import com.vendorflow.portal.application.PortalLinkService;
import com.vendorflow.shared.web.PageResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Staff endpoints of the vendor portal: links of a vendor of the caller's ACTIVE organization. */
@RestController
@RequestMapping("/api/v1/vendors/{vendorId}/upload-links")
public class PortalLinksController {

    private final PortalLinkService links;

    public PortalLinksController(PortalLinkService links) {
        this.links = links;
    }

    @PostMapping
    public ResponseEntity<CreatedUploadLink> create(@PathVariable UUID vendorId,
            @Valid @RequestBody CreateUploadLinkRequest request) {
        // The body holds the raw token: never cacheable.
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(links.create(vendorId, request));
    }

    @GetMapping
    public PageResponse<UploadLinkView> list(@PathVariable UUID vendorId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return links.list(vendorId, page, size);
    }

    @PostMapping("/{linkId}/revoke")
    public UploadLinkView revoke(@PathVariable UUID vendorId, @PathVariable UUID linkId) {
        return links.revoke(vendorId, linkId);
    }
}
