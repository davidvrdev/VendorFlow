package com.vendorflow.chasing.api;

import com.vendorflow.chasing.application.ChasingSettingsService;
import com.vendorflow.chasing.application.VendorChasingService;
import com.vendorflow.shared.web.PageResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Staff endpoints of automated chasing (docs/API.md Phase 15). Thin: authorization lives in the services. */
@RestController
public class ChasingController {

    private final ChasingSettingsService settings;
    private final VendorChasingService vendorChasing;

    public ChasingController(ChasingSettingsService settings, VendorChasingService vendorChasing) {
        this.settings = settings;
        this.vendorChasing = vendorChasing;
    }

    @GetMapping("/api/v1/organization/chasing")
    public ChasingSettingsView getSettings() {
        return settings.get();
    }

    @PutMapping("/api/v1/organization/chasing")
    public ChasingSettingsView updateSettings(@Valid @RequestBody UpdateChasingSettingsRequest request) {
        return settings.update(request);
    }

    @GetMapping("/api/v1/vendors/{vendorId}/chasing")
    public VendorChasingStateView state(@PathVariable UUID vendorId) {
        return vendorChasing.state(vendorId);
    }

    @PutMapping("/api/v1/vendors/{vendorId}/chasing")
    public VendorChasingStateView setPaused(@PathVariable UUID vendorId,
            @Valid @RequestBody PauseVendorChasingRequest request) {
        return vendorChasing.setPaused(vendorId, request.paused());
    }

    @GetMapping("/api/v1/vendors/{vendorId}/chases")
    public PageResponse<ChaseView> chases(@PathVariable UUID vendorId, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return vendorChasing.chases(vendorId, page, size);
    }
}
