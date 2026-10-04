package com.vendorflow.portal.api;

import com.vendorflow.portal.application.PortalService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * PUBLIC vendor portal (ADR-0011): no session, no cookies read, the token in the X-Portal-Token header is the credential. Thin: all
 * checks live in {@link PortalService}. Every response is no-store and no-referrer (set here and again by the
 * security header writers, which also cover error responses).
 */
@RestController
@RequestMapping("/api/v1/portal/link")
public class PortalController {

    /** The token travels in this header, never in the URL, so it cannot reach proxy or platform access logs. */
    public static final String TOKEN_HEADER = "X-Portal-Token";

    private final PortalService portal;

    public PortalController(PortalService portal) {
        this.portal = portal;
    }

    @GetMapping
    public ResponseEntity<PortalInfo> info(@RequestHeader(name = TOKEN_HEADER, required = false) String token) {
        return sensitive(ResponseEntity.ok(), portal.info(token));
    }

    /** Multipart like the staff upload; everything is taken as text/optional so the pipeline owns validation order. */
    @PostMapping(path = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<PortalUploadResult> upload(
            @RequestHeader(name = TOKEN_HEADER, required = false) String token,
            @RequestPart(name = "file", required = false) MultipartFile file,
            @RequestParam(name = "documentTypeId", required = false) String documentTypeId,
            @RequestParam(name = "issueDate", required = false) String issueDate,
            @RequestParam(name = "expirationDate", required = false) String expirationDate) {
        return sensitive(ResponseEntity.status(HttpStatus.CREATED),
                portal.upload(token, file, documentTypeId, issueDate, expirationDate));
    }

    private static <T> ResponseEntity<T> sensitive(ResponseEntity.BodyBuilder builder, T body) {
        return builder.cacheControl(CacheControl.noStore()).header("Referrer-Policy", "no-referrer").body(body);
    }
}
