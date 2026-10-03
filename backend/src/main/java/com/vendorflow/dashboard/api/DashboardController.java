package com.vendorflow.dashboard.api;

import com.vendorflow.dashboard.application.DashboardService;
import com.vendorflow.shared.web.PageResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

    private final DashboardService service;

    public DashboardController(DashboardService service) {
        this.service = service;
    }

    @GetMapping("/summary")
    public DashboardSummary summary() {
        return service.summary();
    }

    @GetMapping("/attention")
    public PageResponse<AttentionItem> attention(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DashboardService.DEFAULT_ATTENTION_SIZE) int size) {
        return service.attention(page, size);
    }
}
