package com.vendorflow.identity.api;

import com.vendorflow.identity.application.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * Primes the XSRF-TOKEN cookie. The token is deferred in Spring Security 6+, so merely calling this endpoint does
     * not set the cookie: reading the token value is what generates and stores it.
     */
    @GetMapping("/csrf")
    public ResponseEntity<Void> csrf(CsrfToken token) {
        token.getToken();
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/signup")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    public Me signup(@Valid @RequestBody SignupRequest request, HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        return authService.signup(request, httpRequest, httpResponse);
    }

    @PostMapping("/login")
    public Me login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        return authService.login(request, httpRequest, httpResponse);
    }

    /** 204 whether or not a session existed. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        authService.logout(request);
        return ResponseEntity.noContent().build();
    }
}
