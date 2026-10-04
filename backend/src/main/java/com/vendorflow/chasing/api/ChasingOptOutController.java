package com.vendorflow.chasing.api;

import com.vendorflow.chasing.application.ChasingOptOutService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PUBLIC vendor unsubscribe (ADR-0012): no session, no cookies read; the token in the X-Portal-Token header is the
 * credential (same convention as the portal, so it never reaches access logs). GET never changes state. Responses are
 * no-store and no-referrer.
 */
@RestController
@RequestMapping("/api/v1/portal/chasing/opt-out")
public class ChasingOptOutController {

    public static final String TOKEN_HEADER = "X-Portal-Token";

    private final ChasingOptOutService optOut;

    public ChasingOptOutController(ChasingOptOutService optOut) {
        this.optOut = optOut;
    }

    @GetMapping
    public ResponseEntity<OptOutView> info(@RequestHeader(name = TOKEN_HEADER, required = false) String token) {
        return sensitive(optOut.info(token));
    }

    @PostMapping
    public ResponseEntity<OptOutView> optOut(@RequestHeader(name = TOKEN_HEADER, required = false) String token) {
        return sensitive(optOut.optOut(token));
    }

    private static ResponseEntity<OptOutView> sensitive(OptOutView body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Referrer-Policy", "no-referrer")
                .body(body);
    }
}
