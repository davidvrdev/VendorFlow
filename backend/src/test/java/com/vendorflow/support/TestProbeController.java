package com.vendorflow.support;

import com.vendorflow.shared.error.NotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Test-only endpoints (src/test) used to exercise the global error handling. Never shipped. */
@RestController
@RequestMapping("/api/v1/test-probe")
@Validated
public class TestProbeController {

    public record Payload(@NotBlank String name, @Email String email) {
    }

    @GetMapping("/boom")
    String boom() {
        throw new RuntimeException("secret-db-password");
    }

    @GetMapping("/missing")
    String missing() {
        throw new NotFoundException("Vendor not found.");
    }

    @PostMapping("/validate")
    String validate(@Valid @RequestBody Payload payload) {
        return "ok";
    }

    @GetMapping("/param")
    String param(@RequestParam @Min(1) int size) {
        return "ok";
    }
}
