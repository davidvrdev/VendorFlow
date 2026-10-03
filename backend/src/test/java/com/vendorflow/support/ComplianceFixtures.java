package com.vendorflow.support;

import com.vendorflow.document.domain.ReviewStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** JDBC fixtures for compliance/dashboard tests (every review/date combination is possible; no uploads needed). */
public class ComplianceFixtures {

    private final JdbcTemplate jdbc;

    public ComplianceFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID typeId(String org, String code) {
        return jdbc.queryForObject("select id from document_type where organization_id = ?::uuid and code = ?",
                UUID.class, org, code);
    }

    /** A vendor with NO requirements (the API would add the default ones). */
    public UUID vendor(String org, String name) {
        return vendor(org, name, "ACTIVE");
    }

    public UUID vendor(String org, String name, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into vendor (id, organization_id, company_name, status, created_at, updated_at)
                values (?, ?::uuid, ?, ?, now(), now())""", id, org, name, status);
        return id;
    }

    public void require(String org, UUID vendor, String typeCode) {
        jdbc.update("""
                insert into vendor_requirement (id, organization_id, vendor_id, document_type_id, created_at)
                values (gen_random_uuid(), ?::uuid, ?, ?, now())""", org, vendor, typeId(org, typeCode));
    }

    public void document(String org, UUID vendor, String typeCode, String state, ReviewStatus review,
            LocalDate expiration, Instant createdAt) {
        jdbc.update("""
                insert into document (id, organization_id, vendor_id, document_type_id, state, review_status,
                    expiration_date, storage_key, original_filename, mime_type, size_bytes, sha256, created_at,
                    updated_at)
                values (gen_random_uuid(), ?::uuid, ?, ?, ?, ?, ?, 'k/' || gen_random_uuid(), 'f.pdf',
                    'application/pdf', 10, repeat('a', 64), ?::timestamptz, ?::timestamptz)""",
                org, vendor, typeId(org, typeCode), state, review.name(), expiration, createdAt.toString(),
                createdAt.toString());
    }

    /** One requirement of the given code for a vendor, with an optional CURRENT document. */
    public void requirement(String org, UUID vendor, String code, ReviewStatus review, LocalDate expiration) {
        requirement(org, vendor, code, review, expiration, Instant.now());
    }

    public void requirement(String org, UUID vendor, String code, ReviewStatus review, LocalDate expiration,
            Instant uploadedAt) {
        require(org, vendor, code);
        if (review != null) {
            document(org, vendor, code, "CURRENT", review, expiration, uploadedAt);
        }
    }
}
