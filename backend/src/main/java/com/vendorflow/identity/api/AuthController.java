package com.vendorflow.identity.api;

import com.vendorflow.identity.application.AuthService;
import com.vendorflow.identity.application.EmailVerificationService;
import com.vendorflow.identity.application.PasswordResetService;
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
    private final EmailVerificationService emailVerification;
    private final PasswordResetService passwordReset;

    public AuthController(AuthService authService, EmailVerificationService emailVerification,
            PasswordResetService passwordReset) {
        this.emailVerification = emailVerification;
        this.passwordReset = passwordReset;
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

    /** Public: the token in the body is the credential. 400 "Invalid or expired link" for any unusable token. */
    @PostMapping("/verify-email")
    public ResponseEntity<Void> verifyEmail(@Valid @RequestBody TokenRequest request) {
        emailVerification.verify(request.token());
        return ResponseEntity.noContent().build();
    }

    /** Authenticated (not in the public list of SecurityConfig). Takes no body. */
    @PostMapping("/resend-verification")
    public ResponseEntity<Void> resendVerification() {
        emailVerification.resend();
        return ResponseEntity.noContent().build();
    }

    /** Always 202 with an empty body, whether or not the email is registered. */
    @PostMapping("/password-reset/request")
    public ResponseEntity<Void> requestPasswordReset(@Valid @RequestBody PasswordResetRequest request) {
        passwordReset.request(request.email());
        return ResponseEntity.accepted().build();
    }

    /** Public. Does not log the user in; all existing sessions of the user are revoked. */
    @PostMapping("/password-reset/confirm")
    public ResponseEntity<Void> confirmPasswordReset(@Valid @RequestBody PasswordResetConfirmRequest request) {
        passwordReset.confirm(request.token(), request.newPassword());
        return ResponseEntity.noContent().build();
    }
}
