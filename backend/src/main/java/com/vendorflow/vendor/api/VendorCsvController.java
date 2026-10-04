package com.vendorflow.vendor.api;

import com.vendorflow.vendor.application.VendorCsvService;
import com.vendorflow.vendor.application.VendorImportService;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * CSV export / import endpoints of the caller's ACTIVE organization (docs/API.md "Phase 7 contract details").
 * The literal paths (export.csv, import/...) win over {@code /vendors/{id}} in Spring's matching.
 */
@RestController
@RequestMapping("/api/v1/vendors")
public class VendorCsvController {

    private static final MediaType CSV = MediaType.parseMediaType("text/csv; charset=utf-8");

    private final VendorCsvService csv;
    private final VendorImportService imports;

    public VendorCsvController(VendorCsvService csv, VendorImportService imports) {
        this.csv = csv;
        this.imports = imports;
    }

    @GetMapping("/export.csv")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String q,
            @RequestParam(required = false) String status, @RequestParam(required = false) String category,
            @RequestParam(required = false) String compliance) {
        VendorCsvService.CsvFile file = csv.export(q, status, category, compliance);
        return attachment(file.content(), file.filename());
    }

    @GetMapping("/import/template.csv")
    public ResponseEntity<byte[]> template() {
        return attachment(csv.template(), "vendor-import-template.csv");
    }

    @PostMapping("/import/preview")
    public ImportPreview preview(@RequestParam(value = "file", required = false) MultipartFile file) {
        return imports.preview(file);
    }

    @PostMapping("/import/{importId}/commit")
    public ImportResult commit(@PathVariable UUID importId) {
        return imports.commit(importId);
    }

    /** The file name is fixed/ASCII (no user text), the response is never cached (it holds the tenant's data). */
    private static ResponseEntity<byte[]> attachment(byte[] body, String filename) {
        return ResponseEntity.ok().contentType(CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .header("X-Content-Type-Options", "nosniff").cacheControl(CacheControl.noStore()).body(body);
    }
}
