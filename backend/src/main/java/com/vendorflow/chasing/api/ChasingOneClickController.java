package com.vendorflow.chasing.api;

import com.vendorflow.chasing.application.ChasingOptOutService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * RFC 8058 one-click unsubscribe target of the {@code List-Unsubscribe} header (ADR-0012). The mail client (or the
 * provider on its behalf) POSTs {@code List-Unsubscribe=One-Click} here without cookies or scripts, so the token has to be
 * in the path: the header can only carry a URL. POST only; any other method (GET from a link scanner or prefetcher
 * included) is answered 405 by the framework and never changes state. Same answer as the header-token endpoint for
 * unknown, malformed and expired tokens (identical 404). The body, if any, is ignored.
 *
 * <p>The path is a secret: nothing of ours logs it (the request-URI is masked, see ProblemJsonWriter and the access
 * logging note in docs/SECURITY.md); a reverse proxy in front of the API must not log it either.
 */
@RestController
@RequestMapping("/api/v1/portal/chasing/one-click")
public class ChasingOneClickController {

    private final ChasingOptOutService optOut;

    public ChasingOneClickController(ChasingOptOutService optOut) {
        this.optOut = optOut;
    }

    @PostMapping("/{token}")
    public ResponseEntity<OptOutView> oneClick(@PathVariable("token") String token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Referrer-Policy", "no-referrer")
                .body(optOut.optOut(token));
    }
}
