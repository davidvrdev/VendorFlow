package com.vendorflow.vendor.application;

import com.vendorflow.audit.AuditEntry;
import com.vendorflow.audit.AuditQueryService;
import com.vendorflow.shared.web.PageResponse;
import com.vendorflow.vendor.api.HistoryEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Vendor timeline built from audit events: the vendor's own and those of its documents (metadata.vendorId). */
@Service
public class VendorHistoryService {

    private final VendorService vendors;
    private final AuditQueryService auditQuery;

    public VendorHistoryService(VendorService vendors, AuditQueryService auditQuery) {
        this.vendors = vendors;
        this.auditQuery = auditQuery;
    }

    @Transactional(readOnly = true)
    public PageResponse<HistoryEvent> history(UUID vendorId, int page, int size) {
        UUID organizationId = vendors.requireViewable(vendorId);
        return PageResponse.of(auditQuery.forVendorTimeline(organizationId, vendorId,
                PageResponse.pageable(page, size)).map(VendorHistoryService::toEvent));
    }

    private static HistoryEvent toEvent(AuditEntry entry) {
        HistoryEvent.Actor actor = entry.actorFullName() == null ? null
                : new HistoryEvent.Actor(entry.actorFullName());
        return new HistoryEvent(entry.id(), entry.action(), actor, entry.occurredAt(), changes(entry.metadata()),
                detail(entry.metadata()));
    }


    /** "Type name - file name" for document events (their metadata carries documentTypeName and filename). */
    static String detail(Map<String, Object> metadata) {
        if (metadata == null) {
            return null;
        }
        Object type = metadata.get("documentTypeName");
        Object filename = metadata.get("filename");
        if (type instanceof String t && filename instanceof String f) {
            return t + " - " + f;
        }
        return type instanceof String t ? t : null;
    }

    /**
     * Audit metadata shapes written for vendors -> the API {@code changes} map {field: {before, after}}:
     * <ul>
     * <li>{@code {"changes": {field: {before, after}}}} (updated/deactivated/reactivated) is passed through;</li>
     * <li>{@code {"added": [codes], "removed": [codes]}} (requirements_changed) becomes
     * {@code {"added": {before: null, after: [codes]}, "removed": {before: [codes], after: null}}};</li>
     * <li>anything else (vendor.created) has no field diff: null.</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    static Map<String, HistoryEvent.Change> changes(Map<String, Object> metadata) {
        if (metadata == null) {
            return null;
        }
        Map<String, HistoryEvent.Change> result = new LinkedHashMap<>();
        if (metadata.get("changes") instanceof Map<?, ?> raw) {
            raw.forEach((field, value) -> {
                if (value instanceof Map<?, ?> change) {
                    result.put(String.valueOf(field), new HistoryEvent.Change(change.get("before"), change.get("after")));
                }
            });
        }
        if (metadata.get("added") instanceof List<?> added && !added.isEmpty()) {
            result.put("added", new HistoryEvent.Change(null, added));
        }
        if (metadata.get("removed") instanceof List<?> removed && !removed.isEmpty()) {
            result.put("removed", new HistoryEvent.Change(removed, null));
        }
        return result.isEmpty() ? null : result;
    }
}
