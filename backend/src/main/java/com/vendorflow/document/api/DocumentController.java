package com.vendorflow.document.api;

import com.vendorflow.document.application.ContentDispositions;
import com.vendorflow.document.application.DocumentDownloadService;
import com.vendorflow.document.application.DocumentService;
import com.vendorflow.document.application.DocumentUploadService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Documents of the caller's ACTIVE organization. Thin: validation and rules live in the application services. */
@RestController
@RequestMapping("/api/v1")
public class DocumentController {

    private static final Logger log = LoggerFactory.getLogger(DocumentController.class);

    private final DocumentUploadService uploads;
    private final DocumentService documents;
    private final DocumentDownloadService downloads;

    public DocumentController(DocumentUploadService uploads, DocumentService documents,
            DocumentDownloadService downloads) {
        this.uploads = uploads;
        this.documents = documents;
        this.downloads = downloads;
    }

    /**
     * Multipart: {@code file}, {@code documentTypeId}, {@code issueDate?}, {@code expirationDate?}. Everything is
     * taken as text/optional here so the service can apply the documented validation ORDER and field errors.
     */
    @PostMapping(path = "/vendors/{vendorId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentSummary upload(@PathVariable UUID vendorId,
            @RequestPart(name = "file", required = false) MultipartFile file,
            @RequestParam(name = "documentTypeId", required = false) String documentTypeId,
            @RequestParam(name = "issueDate", required = false) String issueDate,
            @RequestParam(name = "expirationDate", required = false) String expirationDate) {
        return uploads.upload(vendorId, file, documentTypeId, issueDate, expirationDate);
    }

    @GetMapping("/vendors/{vendorId}/documents")
    public List<DocumentSummary> list(@PathVariable UUID vendorId,
            @RequestParam(name = "includeHistory", defaultValue = "false") boolean includeHistory) {
        return documents.listForVendor(vendorId, includeHistory);
    }

    @GetMapping("/documents/{id}")
    public DocumentSummary get(@PathVariable UUID id) {
        return documents.get(id);
    }

    /** Body is a plain JSON object so an absent key ("unchanged") differs from an explicit null ("clear"). */
    @PatchMapping("/documents/{id}")
    public DocumentSummary patch(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        return documents.changeDates(id, body);
    }

    @PostMapping("/documents/{id}/review")
    public DocumentSummary review(@PathVariable UUID id, @Valid @RequestBody ReviewRequest request) {
        return documents.review(id, request);
    }

    @PostMapping("/documents/{id}/archive")
    public DocumentSummary archive(@PathVariable UUID id) {
        return documents.archive(id);
    }

    /**
     * Streams the file. The service has already authorized, opened the object and written the audit row, so every
     * failure (403/404/missing object) happens BEFORE the first byte and is rendered as a problem by the advice.
     * Written straight to the response (no converters, no Range handling) so nothing is buffered in memory.
     */
    @GetMapping("/documents/{id}/download")
    public void download(@PathVariable UUID id, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        // Spring MVC maps HEAD to every GET handler. A HEAD would pass authorization and WRITE a "downloaded" audit
        // row without sending a byte, so audit would not mean "file was served": refuse it before the service runs.
        if (HttpMethod.HEAD.matches(request.getMethod())) {
            response.setHeader(HttpHeaders.ALLOW, "GET");
            response.sendError(HttpStatus.METHOD_NOT_ALLOWED.value());
            return;
        }
        DocumentDownloadService.Download download = downloads.open(id);
        try (InputStream in = download.stream()) {
            response.setStatus(HttpStatus.OK.value());
            response.setContentType(download.mimeType());
            response.setContentLengthLong(download.sizeBytes());
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDispositions.attachment(download.filename()));
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
            response.setHeader("Content-Security-Policy", "sandbox");
            in.transferTo(response.getOutputStream());
        } catch (IOException e) {
            // Client went away mid-download (or the file shrank): headers are already out, nothing to render.
            log.debug("Download interrupted: documentId={}", id);
        }
    }
}
