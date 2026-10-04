package com.vendorflow.portal.application;

import com.vendorflow.billing.application.SubscriptionService;
import com.vendorflow.compliance.application.ComplianceContext;
import com.vendorflow.compliance.application.ComplianceContextService;
import com.vendorflow.compliance.domain.ComplianceCalculator;
import com.vendorflow.compliance.domain.ComplianceCalculator.DocState;
import com.vendorflow.document.api.DocumentSummary;
import com.vendorflow.document.api.DocumentTypeView;
import com.vendorflow.document.application.DocumentReadService;
import com.vendorflow.document.application.DocumentTypeService;
import com.vendorflow.document.application.DocumentUploadService;
import com.vendorflow.identity.application.TokenGenerator;
import com.vendorflow.notification.NotificationKind;
import com.vendorflow.notification.OutboxService;
import com.vendorflow.organization.application.MembershipService;
import com.vendorflow.organization.application.OrganizationService;
import com.vendorflow.organization.domain.StaffRecipient;
import com.vendorflow.portal.api.PortalInfo;
import com.vendorflow.portal.api.PortalUploadResult;
import com.vendorflow.portal.domain.VendorUploadLink;
import com.vendorflow.portal.domain.VendorUploadLinkType;
import com.vendorflow.portal.infrastructure.VendorUploadLinkRepository;
import com.vendorflow.portal.infrastructure.VendorUploadLinkTypeRepository;
import com.vendorflow.shared.error.ApiException;
import com.vendorflow.shared.ratelimit.RateLimiter;
import com.vendorflow.vendor.application.VendorService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Public side of the vendor portal (ADR-0011). There is NO tenant context here: the organization is the one stored
 * on the verified link, and every call below passes it explicitly. Order of checks: token format, link lookup by hash
 * (unknown/expired/revoked/inactive vendor all produce the same 404), per-link rate limit, subscription (402), upload
 * budget (422), then the shared upload pipeline.
 *
 * <p>Nothing in this class may log or persist the raw token; only link ids are logged.
 */
@Service
public class PortalService {

    private static final Logger log = LoggerFactory.getLogger(PortalService.class);
    static final String LINK_RATE_RULE = "portal-link";
    /** Upload ATTEMPTS per link (successful or not), tighter than the view+upload rule above (L1). */
    static final String LINK_UPLOAD_RATE_RULE = "portal-link-upload";
    /** 32 random bytes as unpadded base64url: exactly 43 characters. Checked before any database access. */
    private static final Pattern TOKEN_FORMAT = Pattern.compile("[A-Za-z0-9_-]{43}");

    private final VendorUploadLinkRepository links;
    private final VendorUploadLinkTypeRepository linkTypes;
    private final TokenGenerator tokens;
    private final VendorService vendors;
    private final DocumentTypeService documentTypes;
    private final DocumentReadService documentReads;
    private final DocumentUploadService uploads;
    private final ComplianceContextService contexts;
    private final OrganizationService organizations;
    private final MembershipService memberships;
    private final SubscriptionService subscriptions;
    private final OutboxService outbox;
    private final RateLimiter rateLimiter;
    private final Clock clock;

    public PortalService(VendorUploadLinkRepository links, VendorUploadLinkTypeRepository linkTypes,
            TokenGenerator tokens, VendorService vendors, DocumentTypeService documentTypes,
            DocumentReadService documentReads, DocumentUploadService uploads, ComplianceContextService contexts,
            OrganizationService organizations, MembershipService memberships, SubscriptionService subscriptions,
            OutboxService outbox, RateLimiter rateLimiter, Clock clock) {
        this.links = links;
        this.linkTypes = linkTypes;
        this.tokens = tokens;
        this.vendors = vendors;
        this.documentTypes = documentTypes;
        this.documentReads = documentReads;
        this.uploads = uploads;
        this.contexts = contexts;
        this.organizations = organizations;
        this.memberships = memberships;
        this.subscriptions = subscriptions;
        this.outbox = outbox;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
    }

    /** A verified link plus its vendor (loaded once per request). */
    private record Resolved(VendorUploadLink link, VendorService.VendorBrief vendor) {
        UUID orgId() {
            return link.getOrganizationId();
        }
    }

    @Transactional(readOnly = true)
    public PortalInfo info(String token) {
        Resolved r = resolve(token);
        UUID orgId = r.orgId();
        List<DocumentTypeView> types = requestedTypes(r);
        ComplianceContext ctx = contexts.forOrganization(orgId);
        Map<UUID, DocumentSummary> currentByType = documentReads.findByVendor(orgId, r.vendor().id(), false).stream()
                .collect(Collectors.toMap(d -> d.documentType().id(), d -> d, (a, b) -> a));
        List<PortalInfo.RequestedType> items = types.stream().map(t -> {
            Optional<DocState> state = Optional.ofNullable(currentByType.get(t.id()))
                    .map(d -> new DocState(d.reviewStatus(), d.expirationDate()));
            ComplianceCalculator.Evaluated evaluated = ComplianceCalculator.evaluated(t.hasExpiration(), state,
                    ctx.today(), ctx.windowDays());
            return new PortalInfo.RequestedType(t.id(), t.name(), t.hasExpiration(), evaluated.status(),
                    evaluated.expirationDate());
        }).toList();
        int remaining = remaining(r.link());
        return new PortalInfo(organizations.nameOf(orgId), r.vendor().companyName(), r.link().getExpiresAt(),
                remaining, remaining > 0 && !subscriptions.isReadOnly(orgId), items);
    }

    /**
     * Cheap pre-check for the upload guard filter (runs BEFORE the multipart body is parsed): well-formed token that
     * maps to a live link. No rate limit and no side effects; the full checks run again in {@link #upload}.
     */
    @Transactional(readOnly = true)
    public boolean isUsableToken(String token) {
        return token != null && TOKEN_FORMAT.matcher(token).matches()
                && links.findByTokenHash(tokens.hash(token)).filter(l -> l.isLive(clock.instant())).isPresent();
    }

    /** Not @Transactional: the file is stored outside the database transaction (DocumentUploadService). */
    public PortalUploadResult upload(String token, MultipartFile file, String documentTypeId, String issueDate,
            String expirationDate) {
        Resolved r = resolve(token);
        UUID orgId = r.orgId();
        // Counted after the token is validated and BEFORE anything else, so failed attempts (bad file, wrong type,
        // scanner) burn the same budget as successful ones: a leaked link cannot be used to hammer the pipeline.
        rateLimit(LINK_UPLOAD_RATE_RULE, r.link());
        if (subscriptions.isReadOnly(orgId)) {
            throw new ApiException(HttpStatus.PAYMENT_REQUIRED, "portal-uploads-unavailable",
                    "Uploads unavailable", organizations.nameOf(orgId)
                            + " is not accepting uploads right now. Please contact them directly.");
        }
        if (remaining(r.link()) <= 0
                || (file != null && file.getSize() > r.link().getMaxTotalBytes() - r.link().getUsedBytes())) {
            // Cheap pre-check with the declared size; the atomic claim in afterInsert is the authority.
            throw limitReached();
        }
        Set<UUID> allowed = requestedTypes(r).stream().map(DocumentTypeView::id).collect(Collectors.toSet());
        DocumentUploadService.PortalUpload portal = new DocumentUploadService.PortalUpload(orgId, r.vendor().id(),
                r.link().getId(), allowed, contexts.forOrganization(orgId).today(), summary -> afterInsert(r, summary));
        DocumentSummary doc = uploads.uploadFromPortal(portal, file, documentTypeId, issueDate, expirationDate);
        int remaining = links.findByIdAndOrganizationIdAndVendorId(r.link().getId(), orgId, r.vendor().id())
                .map(this::remaining).orElse(0);
        log.info("Portal upload accepted: link={} org={} vendor={} document={}", r.link().getId(), orgId,
                r.vendor().id(), doc.id());
        return new PortalUploadResult(new PortalUploadResult.TypeRef(doc.documentType().id(), doc.documentType().name()),
                doc.originalFilename(), "PENDING_REVIEW", doc.uploadedAt(), remaining);
    }

    // ---- steps ----

    /**
     * Runs INSIDE the document insert transaction: consumes one upload of the link atomically (refused if it was just
     * revoked/expired or the budget was spent by a concurrent upload; the whole upload then rolls back and the stored
     * file is deleted) and queues the staff notice, so both commit or vanish together with the document.
     */
    private void afterInsert(Resolved r, DocumentSummary doc) {
        UUID orgId = r.orgId();
        Instant now = clock.instant();
        if (links.claimUse(orgId, r.link().getId(), now, doc.sizeBytes()) == 0) {
            // Refused because revoked/expired (invalid) or because a budget (uploads or bytes) is spent (limit).
            boolean stillValid = links.findByIdAndOrganizationIdAndVendorId(r.link().getId(), orgId, r.vendor().id())
                    .filter(l -> l.isLive(now)).isPresent();
            throw stillValid ? limitReached() : invalid();
        }
        List<StaffRecipient> recipients = memberships.staffRecipients(orgId);
        String orgName = organizations.nameOf(orgId);
        LocalDate day = LocalDate.ofInstant(now, ZoneOffset.UTC);
        for (StaffRecipient recipient : recipients) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("organizationName", orgName);
            payload.put("vendorName", r.vendor().companyName());
            payload.put("vendorId", r.vendor().id().toString());
            payload.put("documentTypeName", doc.documentType().name());
            // One notice per recipient per link per day: a vendor sending ten files must not mail ten times.
            outbox.enqueue(NotificationKind.PORTAL_UPLOAD, orgId, recipient.email(),
                    "portalupload:" + r.link().getId() + ":" + recipient.userId() + ":" + day, payload);
        }
    }

    private Resolved resolve(String token) {
        if (token == null || !TOKEN_FORMAT.matcher(token).matches()) {
            throw invalid();
        }
        Instant now = clock.instant();
        VendorUploadLink link = links.findByTokenHash(tokens.hash(token)).filter(l -> l.isLive(now))
                .orElseThrow(PortalService::invalid);
        // Per link, and only AFTER a successful lookup: unknown tokens (attacker-chosen keys) must not grow the map.
        rateLimit(LINK_RATE_RULE, link);
        VendorService.VendorBrief vendor = vendors.findBrief(link.getOrganizationId(), link.getVendorId())
                .filter(VendorService.VendorBrief::active).orElseThrow(PortalService::invalid);
        return new Resolved(link, vendor);
    }

    private void rateLimit(String rule, VendorUploadLink link) {
        RateLimiter.Decision decision = rateLimiter.tryAcquire(rule, link.getId().toString());
        if (!decision.allowed()) {
            log.warn("Rate limit exceeded: rule={} link={}", rule, link.getId());
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "rate-limited", "Too many requests",
                    "Too many requests. Please try again later.", decision.retryAfterSeconds());
        }
    }

    /** The link's types that are still ACTIVE and still a requirement of the vendor, in display order. */
    private List<DocumentTypeView> requestedTypes(Resolved r) {
        UUID orgId = r.orgId();
        Set<UUID> linked = linkTypes.findByOrganizationIdAndLinkIdIn(orgId, List.of(r.link().getId())).stream()
                .map(VendorUploadLinkType::getDocumentTypeId).collect(Collectors.toSet());
        Set<UUID> required = vendors.requirementTypeIds(orgId, r.vendor().id());
        linked.retainAll(required);
        return documentTypes.findActiveByIds(orgId, linked).stream()
                .sorted(Comparator.comparingInt(DocumentTypeView::sortOrder).thenComparing(DocumentTypeView::name))
                .toList();
    }

    private int remaining(VendorUploadLink link) {
        return Math.max(0, link.getMaxUploads() - link.getUseCount());
    }

    /** One answer for unknown, malformed, expired, revoked and inactive-vendor links: no oracle. */
    public static ApiException invalid() {
        return new ApiException(HttpStatus.NOT_FOUND, "portal-link-invalid", "Link not valid",
                "This link is invalid or has expired.");
    }

    private static ApiException limitReached() {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "portal-upload-limit", "Upload limit reached",
                "This link has reached its upload limit. Please ask for a new link.");
    }
}
