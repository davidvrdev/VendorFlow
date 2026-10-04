package com.vendorflow.vendor.infrastructure;

import com.vendorflow.compliance.domain.ComplianceSummary;
import com.vendorflow.compliance.domain.VendorCompliance;
import com.vendorflow.compliance.infrastructure.RequirementStatusSql;
import com.vendorflow.vendor.api.VendorSummary;
import com.vendorflow.vendor.domain.VendorStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

/**
 * Vendor list with compliance: ONE native SQL query for the page (requirement statuses, counts, vendor status and
 * next expiration are computed in the same statement, so there is no per-vendor follow-up query) plus ONE count query
 * built from the very same CTEs, so filters and totals can never disagree. Search and sort are optional/dynamic, so
 * the SQL is assembled by hand: every user-controlled value is a bind parameter, and ORDER BY comes from a closed
 * enum, never from request text. "Today" and the window are bind parameters (computed in Java from the organization
 * time zone and the injected Clock), never SQL now().
 */
@Repository
public class VendorSearchRepository {

    public enum SortField {
        COMPANY_NAME, CREATED_AT, UPDATED_AT, COMPLIANCE, NEXT_EXPIRATION
    }

    /**
     * {@code status} null = all statuses; {@code query}, {@code category}, {@code compliance} null = no filter.
     * {@code today}/{@code windowDays}: see {@code ComplianceContextService}.
     */
    public record Criteria(String query, VendorStatus status, String category, VendorCompliance compliance,
            SortField sortField, boolean ascending, LocalDate today, int windowDays) {
    }

    /** "!" is the LIKE escape character (a plain ASCII char avoids backslash-escaping layers in Java and SQL). */
    private static final char ESCAPE = '!';

    /**
     * The per-requirement status CTE lives in {@link RequirementStatusSql} (shared with the dashboard).
     * next_exp: earliest exp_date of the vendor's requirements (non-rejected CURRENT document of an expiring type).
     */
    private static final String CTES = "with " + RequirementStatusSql.REQ_CTE + """
            ,
            agg as (
              select vendor_id,
                     count(*) as total,
                     count(*) filter (where req_status = 'MISSING') as missing,
                     count(*) filter (where req_status = 'EXPIRED') as expired,
                     count(*) filter (where req_status = 'EXPIRING') as expiring,
                     count(*) filter (where req_status = 'REVIEW_REQUIRED') as review_required,
                     count(*) filter (where req_status = 'OK') as ok,
                     min(exp_date) as next_exp
              from req
              group by vendor_id
            ),
            listing as (
              select v.id, v.company_name, v.contact_name, v.email, v.phone, v.category, v.status, v.notes,
                     v.created_at, v.updated_at,
                     coalesce(a.total, 0) as total, coalesce(a.missing, 0) as missing,
                     coalesce(a.expired, 0) as expired, coalesce(a.expiring, 0) as expiring,
                     coalesce(a.review_required, 0) as review_required, coalesce(a.ok, 0) as ok,
                     a.next_exp,
                     case
                       when coalesce(a.missing, 0) + coalesce(a.expired, 0) > 0 then 'NON_COMPLIANT'
                       when coalesce(a.review_required, 0) + coalesce(a.expiring, 0) > 0 then 'ATTENTION'
                       else 'COMPLIANT'
                     end as compliance
              from vendor v
              left join agg a on a.vendor_id = v.id
              where v.organization_id = :organizationId
            """;

    @PersistenceContext
    private EntityManager em;

    /** A list row plus the columns the CSV export needs that the list API does not show (notes). */
    public record ExportRow(VendorSummary summary, String notes) {
    }

    public Page<VendorSummary> search(UUID organizationId, Criteria criteria, Pageable pageable) {
        Page<ExportRow> rows = run(organizationId, criteria, pageable, false);
        return new PageImpl<>(rows.getContent().stream().map(ExportRow::summary).toList(), pageable,
                rows.getTotalElements());
    }

    /**
     * Same SQL, filters and ordering as {@link #search}, for the CSV export: ONE statement (no count query) that
     * returns at most {@code limit} rows plus one more, so the caller can tell "too many" without a second query.
     */
    public List<ExportRow> export(UUID organizationId, Criteria criteria, int limit) {
        return run(organizationId, criteria, Pageable.ofSize(limit + 1), true).getContent();
    }

    private Page<ExportRow> run(UUID organizationId, Criteria criteria, Pageable pageable, boolean export) {
        // Filters on vendor columns go inside the listing CTE; the compliance filter needs the computed column, so it
        // is applied on the outer select (page and count share the same text, hence the same result set).
        StringBuilder inner = new StringBuilder();
        if (criteria.status() != null) {
            inner.append(" and v.status = :status");
        }
        if (criteria.category() != null) {
            inner.append(" and lower(v.category) = lower(:category)");
        }
        if (criteria.query() != null) {
            inner.append(" and (lower(v.company_name) like :pattern escape '").append(ESCAPE).append("'")
                    .append(" or lower(v.contact_name) like :pattern escape '").append(ESCAPE).append("'")
                    .append(" or lower(v.email) like :pattern escape '").append(ESCAPE).append("')");
        }
        inner.append("\n            )\n");
        String outerWhere = criteria.compliance() == null ? "" : " where compliance = :compliance";

        String base = CTES + inner;
        String pageSql = base + "select id, company_name, contact_name, email, phone, category, status, created_at,"
                + " updated_at, total, missing, expired, expiring, review_required, ok, next_exp, compliance,"
                // notes (up to 5,000 chars per vendor) only travel for the export, never for the list API
                + (export ? " notes" : " null as notes") + " from listing" + outerWhere + orderBy(criteria) + " limit :limit offset :offset";
        String countSql = base + "select count(*) from listing" + outerWhere;

        Query page = em.createNativeQuery(pageSql);
        // The export needs no total: it asks for limit + 1 rows and checks the size.
        Query count = export ? null : em.createNativeQuery(countSql);
        for (Query q : export ? List.of(page) : List.of(page, count)) {
            q.setParameter("organizationId", organizationId);
            q.setParameter("today", criteria.today());
            q.setParameter("windowDays", criteria.windowDays());
            if (criteria.status() != null) {
                q.setParameter("status", criteria.status().name());
            }
            if (criteria.category() != null) {
                q.setParameter("category", criteria.category());
            }
            if (criteria.query() != null) {
                q.setParameter("pattern", "%" + escapeLike(criteria.query().toLowerCase(Locale.ROOT)) + "%");
            }
            if (criteria.compliance() != null) {
                q.setParameter("compliance", criteria.compliance().name());
            }
        }
        page.setParameter("limit", pageable.getPageSize());
        page.setParameter("offset", pageable.getOffset());

        List<ExportRow> items = new ArrayList<>();
        for (Object row : page.getResultList()) {
            Object[] r = (Object[]) row;
            items.add(new ExportRow(toSummary(r, criteria.today()), (String) r[17]));
        }
        long total = export ? items.size() : ((Number) count.getSingleResult()).longValue();
        return new PageImpl<>(items, pageable, total);
    }

    /** id as the last key makes the order total, so pages never repeat or skip rows when values tie. */
    private static String orderBy(Criteria c) {
        String dir = c.ascending() ? " asc" : " desc";
        return " order by " + switch (c.sortField()) {
            case COMPANY_NAME -> "lower(company_name)" + dir;
            case CREATED_AT -> "created_at" + dir;
            case UPDATED_AT -> "updated_at" + dir;
            // asc = worst first (NON_COMPLIANT, ATTENTION, COMPLIANT); ties by name, always ascending.
            case COMPLIANCE -> "case compliance when 'NON_COMPLIANT' then 0 when 'ATTENTION' then 1 else 2 end" + dir
                    + ", lower(company_name) asc";
            case NEXT_EXPIRATION -> "next_exp" + dir + " nulls last";
        } + ", id asc";
    }

    private static VendorSummary toSummary(Object[] r, LocalDate today) {
        ComplianceSummary compliance = ComplianceSummary.of(VendorCompliance.valueOf((String) r[16]),
                ((Number) r[10]).intValue(), ((Number) r[11]).intValue(), ((Number) r[12]).intValue(),
                ((Number) r[13]).intValue(), ((Number) r[14]).intValue(), toLocalDate(r[15]), today);
        return new VendorSummary(toUuid(r[0]), (String) r[1], (String) r[2], (String) r[3], (String) r[4],
                (String) r[5], VendorStatus.valueOf((String) r[6]), ((Number) r[9]).longValue(), toInstant(r[7]),
                toInstant(r[8]), compliance);
    }

    private static UUID toUuid(Object value) {
        return value instanceof UUID u ? u : UUID.fromString(value.toString());
    }

    private static Instant toInstant(Object value) {
        return switch (value) {
            case Instant i -> i;
            case OffsetDateTime o -> o.toInstant();
            case Timestamp t -> t.toInstant();
            default -> throw new IllegalStateException("Unexpected timestamp type " + value.getClass());
        };
    }

    private static LocalDate toLocalDate(Object value) {
        return switch (value) {
            case null -> null;
            case LocalDate d -> d;
            case java.sql.Date d -> d.toLocalDate();
            default -> throw new IllegalStateException("Unexpected date type " + value.getClass());
        };
    }

    /** Makes % _ and the escape character itself match literally. */
    static String escapeLike(String value) {
        return value.replace(String.valueOf(ESCAPE), "" + ESCAPE + ESCAPE).replace("%", ESCAPE + "%")
                .replace("_", ESCAPE + "_");
    }
}
