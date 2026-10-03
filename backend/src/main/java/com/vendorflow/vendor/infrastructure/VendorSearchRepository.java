package com.vendorflow.vendor.infrastructure;

import com.vendorflow.vendor.api.VendorSummary;
import com.vendorflow.vendor.domain.VendorStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

/**
 * Vendor list: ONE query for the page (with the requirement count as a correlated subquery, so there is no per-vendor
 * follow-up query) plus ONE count query. Built by hand because search and sort are optional/dynamic; every
 * user-controlled value is a bind parameter, and the ORDER BY comes from a closed enum, never from request text.
 */
@Repository
public class VendorSearchRepository {

    public enum SortField {
        COMPANY_NAME("lower(v.companyName)"), CREATED_AT("v.createdAt"), UPDATED_AT("v.updatedAt");

        private final String expression;

        SortField(String expression) {
            this.expression = expression;
        }
    }

    /** {@code status} null = all statuses; {@code query} and {@code category} null = no filter. */
    public record Criteria(String query, VendorStatus status, String category, SortField sortField,
            boolean ascending) {
    }

    /** "!" is the LIKE escape character (a plain ASCII char avoids backslash-escaping layers in Java, HQL and SQL). */
    private static final char ESCAPE = '!';

    @PersistenceContext
    private EntityManager em;

    public Page<VendorSummary> search(UUID organizationId, Criteria criteria, Pageable pageable) {
        StringBuilder where = new StringBuilder(" from Vendor v where v.organizationId = :organizationId");
        if (criteria.status() != null) {
            where.append(" and v.status = :status");
        }
        if (criteria.category() != null) {
            where.append(" and lower(v.category) = lower(:category)");
        }
        if (criteria.query() != null) {
            where.append(" and (lower(v.companyName) like :pattern escape '").append(ESCAPE).append("'")
                    .append(" or lower(v.contactName) like :pattern escape '").append(ESCAPE).append("'")
                    .append(" or lower(v.email) like :pattern escape '").append(ESCAPE).append("')");
        }

        String select = "select new com.vendorflow.vendor.api.VendorSummary(v.id, v.companyName, v.contactName,"
                + " v.email, v.phone, v.category, v.status,"
                + " (select count(r) from VendorRequirement r where r.organizationId = v.organizationId"
                + " and r.vendorId = v.id), v.createdAt, v.updatedAt)";
        String direction = criteria.ascending() ? " asc" : " desc";
        // id as the last key makes the order total, so pages never repeat or skip rows when values tie.
        String order = " order by " + criteria.sortField().expression + direction + ", v.id asc";

        TypedQuery<VendorSummary> page = em.createQuery(select + where + order, VendorSummary.class);
        TypedQuery<Long> count = em.createQuery("select count(v)" + where, Long.class);
        for (TypedQuery<?> q : new TypedQuery<?>[] {page, count}) {
            q.setParameter("organizationId", organizationId);
            if (criteria.status() != null) {
                q.setParameter("status", criteria.status());
            }
            if (criteria.category() != null) {
                q.setParameter("category", criteria.category());
            }
            if (criteria.query() != null) {
                q.setParameter("pattern", "%" + escapeLike(criteria.query().toLowerCase(Locale.ROOT)) + "%");
            }
        }
        page.setFirstResult((int) pageable.getOffset());
        page.setMaxResults(pageable.getPageSize());
        return new PageImpl<>(page.getResultList(), pageable, count.getSingleResult());
    }

    /** Makes % _ and the escape character itself match literally. */
    static String escapeLike(String value) {
        return value.replace(String.valueOf(ESCAPE), "" + ESCAPE + ESCAPE).replace("%", ESCAPE + "%")
                .replace("_", ESCAPE + "_");
    }
}
