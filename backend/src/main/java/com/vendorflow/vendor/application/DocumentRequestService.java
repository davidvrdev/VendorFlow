package com.vendorflow.vendor.application;

import com.vendorflow.audit.AuditService;
import com.vendorflow.compliance.application.ComplianceContextService;
import com.vendorflow.document.api.DocumentTypeView;
import com.vendorflow.document.application.DocumentTypeService;
import com.vendorflow.identity.application.UserAccountService;
import com.vendorflow.notification.NotificationKind;
import com.vendorflow.notification.OutboxService;
import com.vendorflow.organization.application.AuthorizationService;
import com.vendorflow.organization.application.OrganizationService;
import com.vendorflow.organization.application.TenantContext;
import com.vendorflow.organization.domain.Permission;
import com.vendorflow.shared.error.ApiException;
import com.vendorflow.shared.error.NotFoundException;
import com.vendorflow.shared.error.RequestValidationException;
import com.vendorflow.shared.ratelimit.RateLimiter;
import com.vendorflow.vendor.api.DocumentRequestResult;
import com.vendorflow.vendor.domain.Vendor;
import com.vendorflow.vendor.domain.VendorStatus;
import com.vendorflow.vendor.infrastructure.VendorRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Request document" email to a vendor contact (docs/API.md Phase 6). Order of checks: permission, per-user rate
 * limit, vendor (404 if not in the caller's organization), document type (400), vendor ACTIVE (422), vendor email
 * (422), then idempotency per (vendor, type, org-local day) which answers 409 on a repeat. The email is enqueued in the
 * same transaction as the audit row.
 */
@Service
public class DocumentRequestService {

    private static final Logger log = LoggerFactory.getLogger(DocumentRequestService.class);
    static final String RATE_RULE = "document-request-user";
    static final String ACTION = "vendor.document_requested";

    private final VendorRepository vendors;
    private final DocumentTypeService documentTypes;
    private final UserAccountService users;
    private final OrganizationService organizations;
    private final OutboxService outbox;
    private final AuthorizationService authorization;
    private final AuditService audit;
    private final RateLimiter rateLimiter;
    private final ComplianceContextService contexts;
    private final Clock clock;

    public DocumentRequestService(VendorRepository vendors, DocumentTypeService documentTypes,
            UserAccountService users, OrganizationService organizations, OutboxService outbox,
            AuthorizationService authorization, AuditService audit, RateLimiter rateLimiter,
            ComplianceContextService contexts, Clock clock) {
        this.vendors = vendors;
        this.documentTypes = documentTypes;
        this.users = users;
        this.organizations = organizations;
        this.outbox = outbox;
        this.authorization = authorization;
        this.audit = audit;
        this.rateLimiter = rateLimiter;
        this.contexts = contexts;
        this.clock = clock;
    }

    @Transactional
    public DocumentRequestResult request(UUID vendorId, UUID documentTypeId) {
        TenantContext.Tenant tenant = authorization.require(Permission.CONTENT_WRITE);
        UUID orgId = tenant.organizationId();
        RateLimiter.Decision decision = rateLimiter.tryAcquire(RATE_RULE, tenant.userId().toString());
        if (!decision.allowed()) {
            log.warn("Rate limit exceeded: rule={}", RATE_RULE);
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "rate-limited", "Too many requests",
                    "Too many requests. Please try again later.", decision.retryAfterSeconds());
        }
        Vendor vendor = vendors.findByIdAndOrganizationId(vendorId, orgId)
                .orElseThrow(() -> new NotFoundException("Vendor not found."));
        List<DocumentTypeView> types = documentTypes.findActiveByIds(orgId, List.of(documentTypeId));
        if (types.isEmpty()) {
            throw new RequestValidationException("documentTypeId", "must be an active document type");
        }
        DocumentTypeView type = types.get(0);
        if (vendor.getStatus() != VendorStatus.ACTIVE) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "vendor-inactive", "Vendor is inactive",
                    "Reactivate the vendor before requesting documents.");
        }
        String recipient = vendor.getEmail();
        if (recipient == null || recipient.isBlank()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "vendor-no-email", "Vendor has no email",
                    "Add an email address to the vendor first.");
        }
        UserAccountService.UserSummary requester = users.require(tenant.userId());
        String zone = organizations.complianceSettings(orgId).timeZone();
        LocalDate today = contexts.todayIn(zone);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("organizationName", organizations.nameOf(orgId));
        payload.put("documentTypeName", type.name());
        payload.put("vendorName", vendor.getCompanyName());
        if (vendor.getContactName() != null) {
            payload.put("contactName", vendor.getContactName());
        }
        payload.put("requesterName", requester.fullName());
        payload.put("replyTo", requester.email());
        String key = "docreq:" + vendorId + ":" + documentTypeId + ":" + today;
        if (!outbox.enqueue(NotificationKind.DOCUMENT_REQUEST, orgId, recipient, key, payload)) {
            throw new ApiException(HttpStatus.CONFLICT, "already-requested", "Already requested today",
                    "This document was already requested from this vendor today.");
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("documentTypeId", documentTypeId.toString());
        metadata.put("typeCode", type.code());
        metadata.put("typeName", type.name());
        metadata.put("recipientEmail", recipient);
        audit.record(ACTION, VendorService.ENTITY_TYPE, vendorId, metadata);
        return new DocumentRequestResult(clock.instant(), recipient);
    }
}
