package com.vendorflow.dashboard.application;

import com.vendorflow.compliance.application.ComplianceContext;
import com.vendorflow.compliance.application.ComplianceContextService;
import com.vendorflow.dashboard.api.AttentionItem;
import com.vendorflow.dashboard.api.DashboardSummary;
import com.vendorflow.dashboard.infrastructure.DashboardRepository;
import com.vendorflow.organization.application.AuthorizationService;
import com.vendorflow.shared.tenant.TenantContext;
import com.vendorflow.organization.domain.Permission;
import com.vendorflow.shared.web.PageResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Dashboard use cases (read-only; every role may read, like the vendor list). */
@Service
public class DashboardService {

    public static final int DEFAULT_ATTENTION_SIZE = 10;

    private final AuthorizationService authorization;
    private final ComplianceContextService contexts;
    private final DashboardRepository repository;

    public DashboardService(AuthorizationService authorization, ComplianceContextService contexts,
            DashboardRepository repository) {
        this.authorization = authorization;
        this.contexts = contexts;
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public DashboardSummary summary() {
        TenantContext.Tenant tenant = authorization.require(Permission.DATA_VIEW);
        ComplianceContext ctx = contexts.forOrganization(tenant.organizationId());
        return repository.summary(tenant.organizationId(), ctx.today(), ctx.windowDays());
    }

    @Transactional(readOnly = true)
    public PageResponse<AttentionItem> attention(int page, int size) {
        TenantContext.Tenant tenant = authorization.require(Permission.DATA_VIEW);
        ComplianceContext ctx = contexts.forOrganization(tenant.organizationId());
        Pageable pageable = PageResponse.pageable(page, size);
        return PageResponse.of(repository.attention(tenant.organizationId(), ctx.today(), ctx.windowDays(), pageable));
    }
}
