package com.vendorflow.shared.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * Plain (non-XOR) handler that resolves the CSRF token ONLY from the request header ({@code X-XSRF-TOKEN}).
 *
 * <p>The default {@code CsrfTokenRequestHandler.resolveCsrfTokenValue} falls back to the {@code _csrf} request
 * PARAMETER when the header is missing. Reading a parameter of a multipart request makes the container parse the whole
 * body (spooling up to 17 MB to a temp file) BEFORE the CSRF check can fail. Header-only resolution rejects a
 * token-less upload with 403 without touching the body. All our clients send the header (docs/SECURITY.md section 3).
 */
public class HeaderOnlyCsrfTokenRequestHandler extends CsrfTokenRequestAttributeHandler {

    @Override
    public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
        return request.getHeader(csrfToken.getHeaderName());
    }
}
