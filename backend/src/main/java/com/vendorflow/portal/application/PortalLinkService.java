package com.vendorflow.portal.application;

import com.vendorflow.audit.AuditService;
import com.vendorflow.document.api.DocumentTypeView;
import com.vendorflow.document.application.DocumentTypeService;
import com.vendorflow.identity.application.TokenGenerator;
import com.vendorflow.identity.application.UserAccountService;
import com.vendorflow.notification.NotificationKind;
import com.vendorflow.notification.OutboxService;
import com.vendorflow.organization.application.AuthorizationService;
import com.vendorflow.organization.application.OrganizationService;
import com.vendorflow.organization.domain.Permission;
import com.vendorflow.portal.api.CreateUploadLinkRequest;
import com.vendorflow.portal.api.CreatedUploadLink;
import com.vendorflow.portal.api.UploadLinkView;
import com.vendorflow.portal.domain.VendorUploadLink;
import com.vendorflow.portal.domain.VendorUploadLinkType;
import com.vendorflow.portal.infrastructure.VendorUploadLinkRepository;
import com.vendorflow.portal.infrastructure.VendorUploadLinkTypeRepository;
import com.vendorflow.shared.error.ApiException;
import com.vendorflow.shared.error.NotFoundException;
import com.vendorflow.shared.error.RequestValidationException;
import com.vendorflow.shared.ratelimit.RateLimiter;
import com.vendorflow.shared.tenant.TenantContext;
import com.vendorflow.shared.web.PageResponse;
import com.vendorflow.vendor.application.VendorService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Staff side of the vendor portal (ADR-0011): create, list and revoke upload links of a vendor of the caller's
 * organization. The raw token is generated here, returned once and (optionally) put in the outbox payload; only its
 * SHA-256 is stored. Nothing here may log, audit or persist the raw token.
 */
@Service
public class PortalLinkService {

    private static final Logger log = LoggerFactory.getLogger(PortalLinkService.class);
    static final String CREATE_RATE_RULE = "portal-link-create-user";
    static final String ENTITY_TYPE = "vendor_upload_link";
    static final int DEFAULT_EXPIRY_DAYS = 14;
    static final int DEFAULT_MAX_UPLOADS = 20;
    static final int DEFAULT_MAX_TOTAL_MB = 100;

    private final AuthorizationService authorization;
    private final VendorService vendors;
    private final DocumentTypeService documentTypes;
    private final VendorUploadLinkRepository links;
    private final VendorUploadLinkTypeRepository linkTypes;
    private final TokenGenerator tokens;
    private final UserAccountService users;
    private final OrganizationService organizations;
    private final OutboxService outbox;
    private final AuditService audit;
    private final RateLimiter rateLimiter;
    private final Clock clock;
    private final String baseUrl;

    public PortalLinkService(AuthorizationService authorization, VendorService vendors,
            DocumentTypeService documentTypes, VendorUploadLinkRepository links,
            VendorUploadLinkTypeRepository linkTypes, TokenGenerator tokens, UserAccountService users,
            OrganizationService organizations, OutboxService outbox, AuditService audit, RateLimiter rateLimiter,
            Clock clock, @Value("${app.base-url}") String baseUrl) {
        this.authorization = authorization;
        this.vendors = vendors;
        this.documentTypes = documentTypes;
        this.links = links;
        this.linkTypes = linkTypes;
        this.tokens = tokens;
        this.users = users;
        this.organizations = organizations;
        this.outbox = outbox;
        this.audit = audit;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @Transactional
    public CreatedUploadLink create(UUID vendorId, CreateUploadLinkRequest request) {
        TenantContext.Tenant tenant = authorization.require(Permission.CONTENT_WRITE);
        UUID orgId = tenant.organizationId();
        RateLimiter.Decision decision = rateLimiter.tryAcquire(CREATE_RATE_RULE, tenant.userId().toString());
        if (!decision.allowed()) {
            log.warn("Rate limit exceeded: rule={}", CREATE_RATE_RULE);
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "rate-limited", "Too many requests",
                    "Too many requests. Please try again later.", decision.retryAfterSeconds());
        }
        VendorService.VendorBrief vendor = vendors.findBrief(orgId, vendorId)
                .orElseThrow(() -> new NotFoundException("Vendor not found."));
        Set<UUID> typeIds = new LinkedHashSet<>(request.documentTypeIds());
        List<DocumentTypeView> types = documentTypes.findActiveByIds(orgId, typeIds);
        Set<UUID> required = vendors.requirementTypeIds(orgId, vendorId);
        // One message for unknown, foreign, inactive and not-required ids: no oracle about other tenants' types.
        if (types.size() != typeIds.size() || !required.containsAll(typeIds)) {
            throw new RequestValidationException("documentTypeIds",
                    "must be active document types required by this vendor");
        }
        if (!vendor.active()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "vendor-inactive", "Vendor is inactive",
                    "Reactivate the vendor before creating an upload link.");
        }
        boolean sendEmail = Boolean.TRUE.equals(request.sendEmail());
        if (sendEmail && (vendor.email() == null || vendor.email().isBlank())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "vendor-no-email", "Vendor has no email",
                    "Add an email address to the vendor first.");
        }

        Instant now = clock.instant();
        int days = request.expiresInDays() == null ? DEFAULT_EXPIRY_DAYS : request.expiresInDays();
        int maxUploads = request.maxUploads() == null ? DEFAULT_MAX_UPLOADS : request.maxUploads();
        long maxTotalBytes = (request.maxTotalMb() == null ? DEFAULT_MAX_TOTAL_MB : request.maxTotalMb())
                * 1024L * 1024L;
        String token = tokens.newToken();
        UUID id = UUID.randomUUID();
        VendorUploadLink link = links.saveAndFlush(new VendorUploadLink(id, orgId, vendorId, tokens.hash(token),
                tenant.userId(), now.plus(Duration.ofDays(days)), maxUploads, maxTotalBytes, now));
        linkTypes.saveAll(typeIds.stream().map(t -> new VendorUploadLinkType(orgId, id, t)).toList());

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("vendorId", vendorId.toString());
        metadata.put("documentTypeIds", typeIds.stream().map(UUID::toString).toList());
        metadata.put("expiresAt", link.getExpiresAt().toString());
        metadata.put("maxUploads", maxUploads);
        metadata.put("maxTotalBytes", maxTotalBytes);
        metadata.put("emailed", sendEmail);
        audit.record("portal_link.created", ENTITY_TYPE, id, metadata);

        if (sendEmail) {
            UserAccountService.UserSummary requester = users.require(tenant.userId());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("organizationName", organizations.nameOf(orgId));
            payload.put("documentTypeName", types.stream().sorted(Comparator.comparingInt(DocumentTypeView::sortOrder))
                    .map(DocumentTypeView::name).collect(Collectors.joining(", ")));
            payload.put("vendorName", vendor.companyName());
            if (vendor.contactName() != null) {
                payload.put("contactName", vendor.contactName());
            }
            payload.put("requesterName", requester.fullName());
            payload.put("replyTo", requester.email());
            payload.put("expiresAt", link.getExpiresAt().toString());
            // The raw token lives in the outbox payload only until the dispatcher marks the row SENT (it removes the key).
            payload.put("token", token);
            // Explicit marker for the template (L4): the variant must not depend on the presence of a secret key.
            payload.put("portal", true);
            outbox.enqueue(NotificationKind.DOCUMENT_REQUEST, orgId, vendor.email(), "portallink:" + id, payload);
        }
        log.info("Portal link created: id={} org={} vendor={} emailed={}", id, orgId, vendorId, sendEmail);
        return new CreatedUploadLink(view(link, types.stream().map(t -> new UploadLinkView.TypeRef(t.id(), t.name()))
                .toList(), tenant.userId()), baseUrl + "/portal#token=" + token, sendEmail);
    }

    @Transactional(readOnly = true)
    public PageResponse<UploadLinkView> list(UUID vendorId, int page, int size) {
        TenantContext.Tenant tenant = authorization.require(Permission.DATA_VIEW);
        UUID orgId = tenant.organizationId();
        vendors.requireExists(orgId, vendorId);
        Page<VendorUploadLink> result = links.findByVendor(orgId, vendorId, PageResponse.pageable(page, size));
        return new PageResponse<>(views(orgId, result.getContent()), result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }

    @Transactional
    public UploadLinkView revoke(UUID vendorId, UUID linkId) {
        TenantContext.Tenant tenant = authorization.require(Permission.CONTENT_WRITE);
        UUID orgId = tenant.organizationId();
        VendorUploadLink link = links.findForUpdate(linkId, orgId, vendorId)
                .orElseThrow(() -> new NotFoundException("Upload link not found."));
        if (!link.isRevoked()) {
            link.revoke(clock.instant());
            links.saveAndFlush(link);
            audit.record("portal_link.revoked", ENTITY_TYPE, linkId, Map.of("vendorId", vendorId.toString()));
            log.info("Portal link revoked: id={} org={}", linkId, orgId);
        }
        return views(orgId, List.of(link)).get(0);
    }

    // ---- mapping (one query per kind for a whole page: no N+1) ----

    private List<UploadLinkView> views(UUID orgId, List<VendorUploadLink> page) {
        if (page.isEmpty()) {
            return List.of();
        }
        List<UUID> linkIds = page.stream().map(VendorUploadLink::getId).toList();
        Map<UUID, List<UUID>> typeIdsByLink = linkTypes.findByOrganizationIdAndLinkIdIn(orgId, linkIds).stream()
                .collect(Collectors.groupingBy(VendorUploadLinkType::getLinkId,
                        Collectors.mapping(VendorUploadLinkType::getDocumentTypeId, Collectors.toList())));
        Set<UUID> allTypeIds = typeIdsByLink.values().stream().flatMap(Collection::stream)
                .collect(Collectors.toCollection(HashSet::new));
        Map<UUID, DocumentTypeView> typesById = documentTypes.findByIds(orgId, allTypeIds).stream()
                .collect(Collectors.toMap(DocumentTypeView::id, t -> t));
        Set<UUID> creatorIds = page.stream().map(VendorUploadLink::getCreatedByUserId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, String> names = users.fullNames(creatorIds);
        return page.stream().map(l -> {
            List<UploadLinkView.TypeRef> refs = typeIdsByLink.getOrDefault(l.getId(), List.of()).stream()
                    .map(typesById::get).filter(java.util.Objects::nonNull)
                    .sorted(Comparator.comparingInt(DocumentTypeView::sortOrder).thenComparing(DocumentTypeView::name))
                    .map(t -> new UploadLinkView.TypeRef(t.id(), t.name())).toList();
            return map(l, refs, names.get(l.getCreatedByUserId()));
        }).toList();
    }

    private UploadLinkView view(VendorUploadLink link, List<UploadLinkView.TypeRef> refs, UUID creatorId) {
        return map(link, refs, users.fullNames(List.of(creatorId)).get(creatorId));
    }

    private UploadLinkView map(VendorUploadLink l, List<UploadLinkView.TypeRef> refs, String creatorName) {
        return new UploadLinkView(l.getId(), l.getVendorId(), refs, l.statusAt(clock.instant()),
                creatorName == null ? null : new UploadLinkView.CreatedBy(creatorName), l.getCreatedAt(),
                l.getExpiresAt(), l.getRevokedAt(), l.getLastUsedAt(), l.getUseCount(), l.getMaxUploads(), l.getMaxTotalBytes(), l.getUsedBytes());
    }
}
