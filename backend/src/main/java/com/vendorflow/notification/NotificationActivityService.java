package com.vendorflow.notification;

import com.vendorflow.organization.application.AuthorizationService;
import com.vendorflow.organization.application.TenantContext;
import com.vendorflow.organization.domain.Permission;
import com.vendorflow.shared.web.PageResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Email activity of the caller's organization (OWNER/ADMIN: MEMBERS_MANAGE). */
@Service
public class NotificationActivityService {

    public static final int DEFAULT_PAGE_SIZE = 25;

    private final NotificationRepository notifications;
    private final AuthorizationService authorization;

    public NotificationActivityService(NotificationRepository notifications, AuthorizationService authorization) {
        this.notifications = notifications;
        this.authorization = authorization;
    }

    @Transactional(readOnly = true)
    public PageResponse<NotificationView> list(int page, int size) {
        TenantContext.Tenant tenant = authorization.require(Permission.MEMBERS_MANAGE);
        return PageResponse.of(notifications.findActivity(tenant.organizationId(), PageResponse.pageable(page, size))
                .map(NotificationView::from));
    }
}
