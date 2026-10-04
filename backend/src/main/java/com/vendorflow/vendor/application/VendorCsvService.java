package com.vendorflow.vendor.application;

import com.vendorflow.audit.AuditService;
import com.vendorflow.compliance.application.ComplianceContextService;
import com.vendorflow.compliance.domain.ComplianceSummary;
import com.vendorflow.organization.application.AuthorizationService;
import com.vendorflow.organization.application.TenantContext;
import com.vendorflow.organization.domain.Permission;
import com.vendorflow.shared.error.ApiException;
import com.vendorflow.shared.ratelimit.RateLimiter;
import com.vendorflow.vendor.infrastructure.VendorSearchRepository;
import com.vendorflow.vendor.infrastructure.VendorSearchRepository.ExportRow;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.QuoteMode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CSV export and the import template. The export is built in memory, not streamed: at most 10,000 rows (a few MB even
 * with long notes), and building it fully inside the transaction means the database connection is released before a
 * slow client download starts, while an error (422, 400) can still be returned as a normal problem response because
 * no byte has been sent yet.
 */
@Service
public class VendorCsvService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(VendorCsvService.class);

    public static final int MAX_EXPORT_ROWS = 10_000;
    static final String RATE_RULE = "vendor-export-user";
    static final String ENTITY_TYPE = VendorService.ENTITY_TYPE;
    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private static final List<String> EXPORT_COLUMNS = concat(VendorCsv.IMPORT_COLUMNS, VendorCsv.READ_ONLY_COLUMNS);

    /** The file body plus the organization-local date used in the file name. */
    public record CsvFile(byte[] content, String filename) {
    }

    private final VendorSearchRepository search;
    private final VendorService vendors;
    private final ComplianceContextService complianceContext;
    private final AuthorizationService authorization;
    private final AuditService audit;
    private final RateLimiter rateLimiter;

    public VendorCsvService(VendorSearchRepository search, VendorService vendors,
            ComplianceContextService complianceContext, AuthorizationService authorization, AuditService audit,
            RateLimiter rateLimiter) {
        this.search = search;
        this.vendors = vendors;
        this.complianceContext = complianceContext;
        this.authorization = authorization;
        this.audit = audit;
        this.rateLimiter = rateLimiter;
    }

    /** Not readOnly: the export itself is audited. */
    @Transactional
    public CsvFile export(String q, String status, String category, String compliance) {
        TenantContext.Tenant tenant = authorization.require(Permission.DATA_VIEW);
        // Per-USER budget (10 per 10 minutes), counted before the query: an export reads up to 10,000 vendors.
        RateLimiter.Decision decision = rateLimiter.tryAcquire(RATE_RULE, tenant.userId().toString());
        if (!decision.allowed()) {
            log.warn("Rate limit exceeded: rule={}", RATE_RULE);
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "rate-limited", "Too many requests",
                    "Too many exports. Please try again later.", decision.retryAfterSeconds());
        }
        var criteria = vendors.criteria(tenant.organizationId(), q, status, category, compliance, null);
        List<ExportRow> rows = search.export(tenant.organizationId(), criteria, MAX_EXPORT_ROWS);
        if (rows.size() > MAX_EXPORT_ROWS) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "export-too-large", "Too many vendors to export",
                    "More than " + MAX_EXPORT_ROWS + " vendors match. Narrow the export with a status, category,"
                            + " compliance or search filter.");
        }
        LocalDate today = criteria.today();

        StringWriter out = new StringWriter();
        try (CSVPrinter printer = new CSVPrinter(out, format())) {
            printer.printRecord(EXPORT_COLUMNS);
            for (ExportRow row : rows) {
                var v = row.summary();
                ComplianceSummary c = v.compliance();
                printer.printRecord(VendorCsv.protect(v.companyName()), VendorCsv.protect(v.contactName()),
                        VendorCsv.protect(v.email()), VendorCsv.protect(v.phone()), VendorCsv.protect(v.category()),
                        VendorCsv.protect(row.notes()), v.status().name(), c.status().name(), c.missing(),
                        c.expired(), c.expiring(), c.reviewRequired(),
                        c.nextExpiration() == null ? "" : c.nextExpiration().toString());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        // Filters and the row count only: no vendor data in the audit trail.
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("rowCount", rows.size());
        metadata.put("status", status == null || status.isBlank() ? "ACTIVE" : status);
        metadata.put("compliance", blankToNull(compliance));
        metadata.put("category", blankToNull(category));
        metadata.put("q", blankToNull(q));
        audit.record("vendor.exported", ENTITY_TYPE, null, metadata);

        return new CsvFile(withBom(out.toString()), "vendors-" + today + ".csv");
    }

    /** The header row plus one example row; no tenant data, so no tenant lookup beyond the permission check. */
    public byte[] template() {
        authorization.require(Permission.ARCHIVE_AND_IMPORT);
        StringWriter out = new StringWriter();
        try (CSVPrinter printer = new CSVPrinter(out, format())) {
            printer.printRecord(VendorCsv.IMPORT_COLUMNS);
            printer.printRecord("Acme Plumbing LLC", "Jane Smith", "jane@acmeplumbing.example", "555-0100",
                    "Plumbing", "Preferred vendor for the east building", "ACTIVE");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return withBom(out.toString());
    }

    /** CRLF records (RFC 4180, what Excel expects); fields are quoted only when needed (comma, quote, line break). */
    private static CSVFormat format() {
        return CSVFormat.DEFAULT.builder().setRecordSeparator("\r\n").setQuoteMode(QuoteMode.MINIMAL).get();
    }

    /** The BOM makes Excel open the file as UTF-8 instead of the legacy code page. */
    private static byte[] withBom(String text) {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[BOM.length + body.length];
        System.arraycopy(BOM, 0, result, 0, BOM.length);
        System.arraycopy(body, 0, result, BOM.length, body.length);
        return result;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static List<String> concat(List<String> a, List<String> b) {
        return java.util.stream.Stream.concat(a.stream(), b.stream()).toList();
    }
}
