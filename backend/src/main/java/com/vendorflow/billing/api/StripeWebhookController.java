package com.vendorflow.billing.api;

import com.vendorflow.billing.application.BillingProperties;
import com.vendorflow.billing.application.StripeWebhookService;
import com.vendorflow.shared.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stripe's callback. No session and no CSRF token (SecurityConfig): authenticity comes from the signature over the RAW
 * body, so the body is read as bytes here and never through a JSON converter.
 */
@RestController
@RequestMapping("/api/v1/webhooks")
public class StripeWebhookController {

    private final StripeWebhookService service;
    private final BillingProperties properties;

    public StripeWebhookController(StripeWebhookService service, BillingProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @PostMapping("/stripe")
    public ResponseEntity<Map<String, Boolean>> receive(HttpServletRequest request,
            @RequestHeader(name = "Stripe-Signature", required = false) String signature) throws IOException {
        int max = properties.getWebhookMaxBodyBytes();
        if (request.getContentLengthLong() > max) {
            throw tooLarge();
        }
        byte[] body = request.getInputStream().readNBytes(max + 1);
        if (body.length > max) {
            throw tooLarge();
        }
        service.handle(new String(body, StandardCharsets.UTF_8), signature);
        return ResponseEntity.ok(Map.of("received", true));
    }

    private static ApiException tooLarge() {
        return new ApiException(HttpStatus.CONTENT_TOO_LARGE, "payload-too-large", "Payload too large",
                "The request body is too large.");
    }
}
