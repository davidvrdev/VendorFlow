package com.vendorflow.portal.api;

import com.vendorflow.portal.application.PortalService;
import com.vendorflow.shared.error.ApiException;
import com.vendorflow.shared.error.Problems;
import com.vendorflow.shared.security.ProblemJsonWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.util.unit.DataSize;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

/**
 * Guards the public portal upload BEFORE the multipart body is parsed (ADR-0011, M3). Spring parses multipart in the
 * DispatcherServlet, i.e. after every filter, and writes each file part to a temp file; without this filter an
 * anonymous caller could make the server buffer a full-size body for any made-up token. Checks, cheapest first:
 * <ol>
 * <li>{@code X-Portal-Token} present, 43 characters of base64url, and mapping to a LIVE link (one indexed lookup);
 * otherwise the same 404 {@code portal-link-invalid} as every other invalid-link answer (no oracle)</li>
 * <li>declared Content-Length not above the multipart request limit: 413 without reading the body</li>
 * </ol>
 * Runs after the per-IP {@code RateLimitFilter} (so unknown-token floods are already bounded) and before Spring
 * Security. The token is never logged.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class PortalUploadGuardFilter extends OncePerRequestFilter {

    private static final String UPLOAD_PATH = "/api/v1/portal/link/documents";

    private final PortalService portal;
    private final ProblemJsonWriter writer;
    private final long maxRequestBytes;

    public PortalUploadGuardFilter(PortalService portal, ProblemJsonWriter writer,
            @Value("${spring.servlet.multipart.max-request-size:17MB}") DataSize maxRequestSize) {
        this.portal = portal;
        this.writer = writer;
        this.maxRequestBytes = maxRequestSize.toBytes();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !HttpMethod.POST.matches(request.getMethod()) || !UPLOAD_PATH.equals(normalize(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!portal.isUsableToken(request.getHeader(PortalController.TOKEN_HEADER))) {
            ApiException invalid = PortalService.invalid();
            writer.write(response, Problems.of(invalid.status(), invalid.slug(), invalid.title(), invalid.getMessage()),
                    request.getRequestURI());
            return;
        }
        if (request.getContentLengthLong() > maxRequestBytes) {
            writer.write(response, Problems.of(HttpStatus.PAYLOAD_TOO_LARGE, "file-too-large", "File too large",
                    "The upload exceeds the maximum allowed size."), request.getRequestURI());
            return;
        }
        chain.doFilter(request, response);
    }

    /** Same normalization as the rate-limit filter: decoded, no ";params", dot segments resolved, lower case. */
    private static String normalize(HttpServletRequest request) {
        String path = StringUtils.cleanPath(UrlPathHelper.defaultInstance.getPathWithinApplication(request))
                .toLowerCase(Locale.ROOT);
        int end = path.length();
        while (end > 1 && path.charAt(end - 1) == '/') {
            end--;
        }
        return path.substring(0, end);
    }
}
