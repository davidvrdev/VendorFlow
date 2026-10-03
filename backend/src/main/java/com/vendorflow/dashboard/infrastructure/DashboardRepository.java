package com.vendorflow.dashboard.infrastructure;

import com.vendorflow.compliance.domain.RequirementStatus;
import com.vendorflow.compliance.infrastructure.RequirementStatusSql;
import com.vendorflow.dashboard.api.AttentionItem;
import com.vendorflow.dashboard.api.AttentionItem.AttentionAction;
import com.vendorflow.dashboard.api.DashboardSummary;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

/**
 * Dashboard reads: ONE native statement for the summary, ONE page statement + ONE count for the attention list. All
 * of them build on the shared per-requirement status CTE ({@link RequirementStatusSql}); only ACTIVE vendors count.
 * Every value is a bind parameter; "today" is computed in Java from the organization time zone.
 */
@Repository
public class DashboardRepository {

    /** Public only so the performance test can EXPLAIN exactly this statement. */
    public static final String SUMMARY_SQL = "with " + RequirementStatusSql.REQ_CTE + """
            ,
            agg as (
              select vendor_id,
                     count(*) as total,
                     count(*) filter (where req_status = 'MISSING') as missing,
                     count(*) filter (where req_status = 'EXPIRED') as expired,
                     count(*) filter (where req_status = 'EXPIRING') as expiring,
                     count(*) filter (where req_status = 'REVIEW_REQUIRED') as review_required
              from req
              group by vendor_id
            ),
            per_vendor as (
              select coalesce(a.total, 0) as total, coalesce(a.missing, 0) as missing,
                     coalesce(a.expired, 0) as expired, coalesce(a.expiring, 0) as expiring,
                     coalesce(a.review_required, 0) as review_required
              from vendor v
              left join agg a on a.vendor_id = v.id
              where v.organization_id = :organizationId and v.status = 'ACTIVE'
            )
            select count(*) as active,
                   count(*) filter (where missing + expired = 0 and review_required + expiring = 0) as compliant,
                   count(*) filter (where missing + expired = 0 and review_required + expiring > 0) as attention,
                   count(*) filter (where missing + expired > 0) as non_compliant,
                   count(*) filter (where total = 0) as no_requirements,
                   coalesce(sum(missing), 0), coalesce(sum(expired), 0), coalesce(sum(expiring), 0),
                   coalesce(sum(review_required), 0)
            from per_vendor
            """;

    private static final String ATTENTION_FROM = """
            from req r
            join vendor v on v.organization_id = :organizationId and v.id = r.vendor_id and v.status = 'ACTIVE'
            where r.req_status <> 'OK'
            """;

    /**
     * Urgency: EXPIRED (earliest date = most overdue) -> MISSING (vendor name, type sortOrder) -> EXPIRING (soonest)
     * -> REVIEW_REQUIRED (oldest upload). The CASE keys are NULL outside their group, so each group only sorts by its
     * own keys; vendor id and type id make the order total (stable paging).
     */
    private static final String ATTENTION_ORDER = """
            order by case r.req_status when 'EXPIRED' then 0 when 'MISSING' then 1 when 'EXPIRING' then 2 else 3 end,
                     case when r.req_status in ('EXPIRED', 'EXPIRING') then r.exp_date end asc,
                     case when r.req_status = 'MISSING' then lower(v.company_name) end asc,
                     case when r.req_status = 'MISSING' then r.type_sort_order end asc,
                     case when r.req_status = 'REVIEW_REQUIRED' then r.uploaded_at end asc,
                     r.vendor_id asc, r.document_type_id asc
            """;

    /** Public only so the performance test can EXPLAIN exactly this statement. */
    public static final String ATTENTION_PAGE_SQL = "with " + RequirementStatusSql.REQ_CTE + """

            select r.vendor_id, v.company_name, r.document_type_id, r.type_name, r.req_status, r.document_id,
                   r.exp_date, v.email
            """ + ATTENTION_FROM + ATTENTION_ORDER + " limit :limit offset :offset";

    private static final String ATTENTION_COUNT_SQL = "with " + RequirementStatusSql.REQ_CTE
            + "\nselect count(*)\n" + ATTENTION_FROM;

    @PersistenceContext
    private EntityManager em;

    public DashboardSummary summary(UUID organizationId, LocalDate today, int windowDays) {
        Query q = em.createNativeQuery(SUMMARY_SQL);
        bind(q, organizationId, today, windowDays);
        Object[] r = (Object[]) q.getSingleResult();
        return new DashboardSummary(today, windowDays,
                new DashboardSummary.Vendors(n(r[0]), n(r[1]), n(r[2]), n(r[3]), n(r[4])),
                new DashboardSummary.Documents(n(r[5]), n(r[6]), n(r[7]), n(r[8])));
    }

    public Page<AttentionItem> attention(UUID organizationId, LocalDate today, int windowDays, Pageable pageable) {
        Query page = em.createNativeQuery(ATTENTION_PAGE_SQL);
        Query count = em.createNativeQuery(ATTENTION_COUNT_SQL);
        bind(page, organizationId, today, windowDays);
        bind(count, organizationId, today, windowDays);
        page.setParameter("limit", pageable.getPageSize());
        page.setParameter("offset", pageable.getOffset());

        List<AttentionItem> items = new ArrayList<>();
        for (Object row : page.getResultList()) {
            Object[] r = (Object[]) row;
            RequirementStatus status = RequirementStatus.valueOf((String) r[4]);
            LocalDate expiration = toLocalDate(r[6]);
            Integer days = expiration == null ? null : (int) (expiration.toEpochDay() - today.toEpochDay());
            items.add(new AttentionItem(toUuid(r[0]), (String) r[1], toUuid(r[2]), (String) r[3], status,
                    r[5] == null ? null : toUuid(r[5]), expiration, days, AttentionAction.of(status), (String) r[7]));
        }
        long total = ((Number) count.getSingleResult()).longValue();
        return new PageImpl<>(items, pageable, total);
    }

    private static void bind(Query q, UUID organizationId, LocalDate today, int windowDays) {
        q.setParameter("organizationId", organizationId);
        q.setParameter("today", today);
        q.setParameter("windowDays", windowDays);
    }

    private static long n(Object value) {
        return ((Number) value).longValue();
    }

    private static UUID toUuid(Object value) {
        return value instanceof UUID u ? u : UUID.fromString(value.toString());
    }

    private static LocalDate toLocalDate(Object value) {
        return switch (value) {
            case null -> null;
            case LocalDate d -> d;
            case java.sql.Date d -> d.toLocalDate();
            default -> throw new IllegalStateException("Unexpected date type " + value.getClass());
        };
    }
}
