package com.vendorflow.notification;

import com.vendorflow.shared.web.PageResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationsController {

    private final NotificationActivityService service;

    public NotificationsController(NotificationActivityService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<NotificationView> list(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + NotificationActivityService.DEFAULT_PAGE_SIZE) int size) {
        return service.list(page, size);
    }
}
