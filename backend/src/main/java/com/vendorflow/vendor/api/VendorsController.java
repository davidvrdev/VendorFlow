package com.vendorflow.vendor.api;

import com.vendorflow.shared.web.PageResponse;
import com.vendorflow.vendor.application.VendorHistoryService;
import com.vendorflow.vendor.application.VendorService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Vendors of the caller's ACTIVE organization. Ids of other organizations are 404, like nonexistent ones. */
@RestController
@RequestMapping("/api/v1/vendors")
public class VendorsController {

    private final VendorService service;
    private final VendorHistoryService history;

    public VendorsController(VendorService service, VendorHistoryService history) {
        this.service = service;
        this.history = history;
    }

    @GetMapping
    public PageResponse<VendorSummary> list(@RequestParam(required = false) String q,
            @RequestParam(required = false) String status, @RequestParam(required = false) String category,
            @RequestParam(required = false) String compliance,
            @RequestParam(required = false) String sort, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + VendorService.DEFAULT_PAGE_SIZE) int size) {
        return service.list(q, status, category, compliance, sort, page, size);
    }

    @GetMapping("/categories")
    public List<String> categories() {
        return service.categories();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public VendorDetail create(@Valid @RequestBody VendorRequest request) {
        return service.create(request);
    }

    @GetMapping("/{id}")
    public VendorDetail get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PutMapping("/{id}")
    public VendorDetail update(@PathVariable UUID id, @Valid @RequestBody VendorRequest request) {
        return service.update(id, request);
    }

    @PostMapping("/{id}/deactivate")
    public VendorDetail deactivate(@PathVariable UUID id) {
        return service.deactivate(id);
    }

    @PostMapping("/{id}/reactivate")
    public VendorDetail reactivate(@PathVariable UUID id) {
        return service.reactivate(id);
    }

    @PutMapping("/{id}/requirements")
    public VendorDetail updateRequirements(@PathVariable UUID id, @Valid @RequestBody RequirementsRequest request) {
        return service.updateRequirements(id, request);
    }

    @GetMapping("/{id}/history")
    public PageResponse<HistoryEvent> history(@PathVariable UUID id, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + VendorService.DEFAULT_PAGE_SIZE) int size) {
        return history.history(id, page, size);
    }
}
