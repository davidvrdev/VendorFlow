package com.vendorflow.compliance.infrastructure;

/**
 * THE single SQL definition of "status of one requirement" (mirrors ComplianceCalculator.evaluate; the shared vectors
 * tests keep both identical). Used by the vendor list and the dashboard; the status CASE must never be copied.
 *
 * <p>{@link #REQ_CTE} is a CTE named {@code req}, one row per requirement of an ACTIVE document type, joined to the
 * CURRENT document of (vendor, type) if any (document_current_uq guarantees at most one, so no duplication). Both
 * joins carry organization_id. Columns: vendor_id, document_type_id, type_name, type_sort_order, document_id (null if
 * no CURRENT document; a REJECTED one is still reported here), uploaded_at, req_status, exp_date (expiration date of a
 * non-rejected CURRENT document of a type that has expiration; null otherwise).
 *
 * <p>Binds: {@code :organizationId}, {@code :today} (date in the org time zone), {@code :windowDays}.
 * Usage: {@code "with " + REQ_CTE + ", other as (...) select ..."}.
 */
public final class RequirementStatusSql {

    private RequirementStatusSql() {
    }

    public static final String REQ_CTE = """
            req as (
              select r.vendor_id, r.document_type_id, t.name as type_name, t.sort_order as type_sort_order,
                     d.id as document_id, d.created_at as uploaded_at,
                     case
                       when d.id is null or d.review_status = 'REJECTED' then 'MISSING'
                       when t.has_expiration and d.expiration_date < cast(:today as date) then 'EXPIRED'
                       when d.review_status = 'PENDING' or (t.has_expiration and d.expiration_date is null)
                         then 'REVIEW_REQUIRED'
                       when t.has_expiration and d.expiration_date - cast(:today as date) <= :windowDays
                         then 'EXPIRING'
                       else 'OK'
                     end as req_status,
                     case when d.id is not null and d.review_status <> 'REJECTED' and t.has_expiration
                          then d.expiration_date end as exp_date
              from vendor_requirement r
              join document_type t on t.organization_id = r.organization_id and t.id = r.document_type_id
                                  and t.active
              left join document d on d.organization_id = r.organization_id and d.vendor_id = r.vendor_id
                                  and d.document_type_id = r.document_type_id and d.state = 'CURRENT'
              where r.organization_id = :organizationId
            )""";
}
