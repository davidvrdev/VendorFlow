package com.vendorflow.shared.security;

import com.vendorflow.shared.error.Problems;
import java.util.Map;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderNotFoundException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * Deny-by-default security for a JSON API with server-side sessions (ADR-0002).
 *
 * <ul>
 *   <li>Login is a custom JSON endpoint (identity.AuthService); formLogin/httpBasic/logout are disabled.</li>
 *   <li>CSRF: cookie token repository ({@code XSRF-TOKEN}, readable by JS) + {@code X-XSRF-TOKEN} header, the
 *       configuration Spring Security documents for SPAs. We use the plain (non-XOR) handler: the token is
 *       only ever sent in a header (HeaderOnlyCsrfTokenRequestHandler: the {@code _csrf} parameter is NOT read), so
 *       BREACH-style masking of a form field is not needed.</li>
 *   <li>The SecurityContext lives in the HTTP session, which Spring Session persists in Postgres.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    public static final String CSRF_COOKIE = "XSRF-TOKEN";
    public static final String CSRF_HEADER = "X-XSRF-TOKEN";

    /** Strict policy for JSON responses: nothing may load or embed; cannot be framed. */
    public static final String API_CSP = "default-src 'none'; frame-ancestors 'none'";
    public static final String PERMISSIONS_POLICY =
            "accelerometer=(), camera=(), geolocation=(), gyroscope=(), magnetometer=(), microphone=(), "
                    + "payment=(), usb=()";
    static final long HSTS_SECONDS = 31_536_000L;

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ProblemJsonWriter writer,
            CookieCsrfTokenRepository csrfTokenRepository, SecurityContextRepository securityContextRepository,
            Environment environment) {
        boolean e2e = environment.acceptsProfiles(Profiles.of("e2e"));
        http
                .authorizeHttpRequests(auth -> {
                    auth
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        // Explicit list instead of "/api/v1/auth/**": resend-verification needs a logged-in user.
                        .requestMatchers("/api/v1/auth/csrf", "/api/v1/auth/signup", "/api/v1/auth/login",
                                "/api/v1/auth/logout", "/api/v1/auth/verify-email",
                                "/api/v1/auth/password-reset/**").permitAll()
                        .requestMatchers("/api/v1/invitations/lookup", "/api/v1/invitations/accept").permitAll()
                        // Stripe webhook: no session; authenticity = signature over the raw body (billing feature).
                        .requestMatchers(HttpMethod.POST, "/api/v1/webhooks/stripe").permitAll()
                        // Vendor portal (ADR-0011): the token in the X-Portal-Token header is the credential. Exactly these two paths.
                        .requestMatchers(HttpMethod.GET, "/api/v1/portal/link").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/portal/link/documents").permitAll();
                    // E2E-only test mailbox: public only under profile e2e (ProfileGuard forbids e2e + prod).
                    if (e2e) {
                        auth.requestMatchers("/api/test/mailbox").permitAll();
                    }
                    auth.anyRequest().authenticated();
                })
                .csrf(csrf -> csrf
                        .ignoringRequestMatchers("/api/v1/webhooks/stripe")
                        // Portal upload: no cookie/session is read, so there is no ambient credential to forge a request with.
                        .ignoringRequestMatchers(PathPatternRequestMatcher.withDefaults()
                                .matcher(HttpMethod.POST, "/api/v1/portal/link/documents"))
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(new HeaderOnlyCsrfTokenRequestHandler()))
                .headers(headers -> headers
                        .contentTypeOptions(Customizer.withDefaults())
                        .frameOptions(frame -> frame.deny())
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER))
                        // The API only ever returns JSON (or an attachment that sets its own, stricter "sandbox" CSP and
                        // wins because Spring Security does not overwrite a header the handler already set).
                        .contentSecurityPolicy(csp -> csp.policyDirectives(API_CSP))
                        .permissionsPolicyHeader(pp -> pp.policy(PERMISSIONS_POLICY))
                        // Written only on requests that are secure (TLS, or X-Forwarded-Proto from a trusted proxy),
                        // so plain-http local development is unaffected. Cache-Control: no-store comes from the default
                        // cacheControl writer (also Pragma and Expires).
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true)
                                .maxAgeInSeconds(HSTS_SECONDS)))
                .securityContext(sc -> sc.securityContextRepository(securityContextRepository))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                // JSON API: no "saved request" redirect flow, so a 401 must not create a session cookie.
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, e) ->
                                writer.write(response, Problems.unauthorized(), request.getRequestURI()))
                        .accessDeniedHandler((request, response, e) ->
                                writer.write(response, Problems.forbidden(), request.getRequestURI())));
        return http.build();
    }

    /** JS-readable CSRF cookie. SameSite=Lax; Secure follows the request scheme (X-Forwarded-Proto in prod). */
    @Bean
    CookieCsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repo = new CookieCsrfTokenRepository();
        repo.setCookieName(CSRF_COOKIE);
        repo.setHeaderName(CSRF_HEADER);
        // NB: setCookieCustomizer replaces the default customizer, so httpOnly(false) must be set here.
        repo.setCookieCustomizer(cookie -> cookie.httpOnly(false).sameSite("Lax"));
        return repo;
    }

    /** HTTP-session backed (Spring Session JDBC). AuthService saves the context explicitly after login. */
    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    /**
     * Argon2id for new hashes (OWASP: m=19 MiB, t=2, p=1; ASVS V2.1.2), {bcrypt} kept so existing hashes still verify;
     * AuthService re-hashes a bcrypt hash to Argon2id on the next successful login (upgradeEncoding). Built by hand
     * instead of PasswordEncoderFactories because that factory hard-codes bcrypt strength 10 as the default.
     * Cost parameters are properties only so the test profile can lower them for speed.
     */
    @Bean
    PasswordEncoder passwordEncoder(@Value("${app.security.bcrypt-strength:12}") int bcryptStrength,
            @Value("${app.security.argon2-memory-kib:19456}") int argonMemoryKib,
            @Value("${app.security.argon2-iterations:2}") int argonIterations) {
        return new DelegatingPasswordEncoder("argon2", Map.of(
                "argon2", new Argon2PasswordEncoder(16, 32, 1, argonMemoryKib, argonIterations),
                "bcrypt", new BCryptPasswordEncoder(bcryptStrength)));
    }


    /**
     * Login is implemented by identity.AuthService, so no AuthenticationManager is ever used. Spring Boot only
     * creates its default "user" with a random password (logged at startup) when no AuthenticationManager /
     * AuthenticationProvider / UserDetailsService bean exists; defining this one that rejects everything switches
     * that off without excluding autoconfiguration.
     */
    @Bean
    AuthenticationManager authenticationManager() {
        return authentication -> {
            throw new ProviderNotFoundException("Authentication is handled by AuthService");
        };
    }
}
