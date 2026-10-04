package com.vendorflow.chasing.application;

import com.vendorflow.audit.AuditService;
import com.vendorflow.chasing.api.OptOutView;
import com.vendorflow.chasing.infrastructure.ChasingStore;
import com.vendorflow.identity.application.TokenGenerator;
import com.vendorflow.notification.EmailSuppressionService;
import com.vendorflow.notification.NotificationKind;
import com.vendorflow.notification.OutboxService;
import com.vendorflow.organization.application.MembershipService;
import com.vendorflow.organization.application.OrganizationService;
import com.vendorflow.organization.domain.StaffRecipient;
import com.vendorflow.shared.error.ApiException;
import com.vendorflow.vendor.application.VendorService;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public vendor-side unsubscribe (ADR-0012). No tenant context: the organization comes from the verified chase row the
 * token belongs to. {@link #info} never changes state (email scanners and prefetchers open links); {@link #optOut} does
 * and is idempotent. Unknown, malformed and expired tokens all answer the same 404: no oracle. Works for read-only
 * organizations and inactive vendors (unsubscribing must never be blocked). Nothing here logs or stores the raw token.
 */
@Service
public class ChasingOptOutService {

    private static final Logger log = LoggerFactory.getLogger(ChasingOptOutService.class);
    /** 32 random bytes as unpadded base64url: exactly 43 characters. Checked before any database access. */
    private static final Pattern TOKEN_FORMAT = Pattern.compile("[A-Za-z0-9_-]{43}");

    private final ChasingStore store;
    private final TokenGenerator tokens;
    private final VendorService vendors;
    private final OrganizationService organizations;
    private final MembershipService memberships;
    private final OutboxService outbox;
    private final EmailSuppressionService suppression;
    private final AuditService audit;
    private final Clock clock;

    public ChasingOptOutService(ChasingStore store, TokenGenerator tokens, VendorService vendors,
            OrganizationService organizations, MembershipService memberships, OutboxService outbox,
            EmailSuppressionService suppression, AuditService audit, Clock clock) {
        this.store = store;
        this.tokens = tokens;
        this.vendors = vendors;
        this.organizations = organizations;
        this.memberships = memberships;
        this.outbox = outbox;
        this.suppression = suppression;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public OptOutView info(String token) {
        ChasingStore.ChaseRef ref = resolve(token);
        return view(ref, store.isOptedOut(ref.organizationId(), ref.vendorId()));
    }

    @Transactional
    public OptOutView optOut(String token) {
        ChasingStore.ChaseRef ref = resolve(token);
        UUID orgId = ref.organizationId();
        OptOutView view = view(ref, true);
        // The consent belongs to the address: suppress it globally (every organization, manual requests too), and make sure
        // nothing already queued for this vendor goes out. Both are idempotent, so a repeated POST repairs a partial state.
        suppression.suppressHash(ref.recipientHash(), EmailSuppressionService.REASON_CHASING_OPT_OUT);
        outbox.cancelPendingVendorChases(orgId, ref.vendorId(), "Vendor opted out");
        if (store.optOut(orgId, ref.vendorId(), clock.instant())) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("chaseId", ref.chaseId().toString());
            audit.record(orgId, null, "vendor.chasing.opted_out", VendorService.ENTITY_TYPE, ref.vendorId(), metadata);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("vendorId", ref.vendorId().toString());
            item.put("vendorName", view.vendorName());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("organizationName", view.organizationName());
            payload.put("event", "OPTED_OUT");
            payload.put("items", List.of(item));
            for (StaffRecipient r : memberships.staffRecipients(orgId)) {
                outbox.enqueue(NotificationKind.CHASING_STAFF_NOTICE, orgId, r.email(),
                        "chaseoptout:" + ref.vendorId() + ":" + r.userId() + ":" + ref.chaseId(), payload);
            }
            log.info("Vendor opted out of chasing: org={} vendor={}", orgId, ref.vendorId());
        }
        return view;
    }

    private ChasingStore.ChaseRef resolve(String token) {
        if (token == null || !TOKEN_FORMAT.matcher(token).matches()) {
            throw invalid();
        }
        return store.findChaseByOptOutHash(tokens.hash(token), clock.instant())
                .orElseThrow(ChasingOptOutService::invalid);
    }

    private OptOutView view(ChasingStore.ChaseRef ref, boolean optedOut) {
        String vendorName = vendors.findBrief(ref.organizationId(), ref.vendorId())
                .map(VendorService.VendorBrief::companyName).orElseThrow(ChasingOptOutService::invalid);
        return new OptOutView(organizations.nameOf(ref.organizationId()), vendorName, optedOut);
    }

    /** One answer for unknown, malformed and expired tokens. */
    public static ApiException invalid() {
        return new ApiException(HttpStatus.NOT_FOUND, "chasing-opt-out-invalid", "Link not valid",
                "This link is invalid or has expired.");
    }
}
