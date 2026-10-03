package com.vendorflow.shared.security;

import com.vendorflow.shared.error.Problems;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderNotFoundException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.savedrequest.NullRequestCache;

/**
 * Deny-by-default security for a JSON API with server-side sessions (ADR-0002).
 *
 * <ul>
 *   <li>Login is a custom JSON endpoint (identity.AuthService); formLogin/httpBasic/logout are disabled.</li>
 *   <li>CSRF: cookie token repository ({@code XSRF-TOKEN}, readable by JS) + {@code X-XSRF-TOKEN} header, the
 *       configuration Spring Security documents for SPAs. We use the plain (non-XOR) handler: the token is
 *       only ever sent in a header, so BREACH-style masking of a form field is not needed.</li>
 *   <li>The SecurityContext lives in the HTTP session, which Spring Session persists in Postgres.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    public static final String CSRF_COOKIE = "XSRF-TOKEN";
    public static final String CSRF_HEADER = "X-XSRF-TOKEN";

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
                        .requestMatchers("/api/v1/invitations/lookup", "/api/v1/invitations/accept").permitAll();
                    // E2E-only test mailbox: public only under profile e2e (ProfileGuard forbids e2e + prod).
                    if (e2e) {
                        auth.requestMatchers("/api/test/mailbox").permitAll();
                    }
                    auth.anyRequest().authenticated();
                })
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
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
     * {bcrypt} via DelegatingPasswordEncoder so hashes can migrate to Argon2 later without a schema change.
     * PasswordEncoderFactories.createDelegatingPasswordEncoder() hard-codes bcrypt strength 10, so we build the
     * delegating encoder ourselves to get the cost factor documented in SECURITY.md (12; configurable for tests).
     */
    @Bean
    PasswordEncoder passwordEncoder(@Value("${app.security.bcrypt-strength:12}") int strength) {
        return new DelegatingPasswordEncoder("bcrypt", Map.of("bcrypt", new BCryptPasswordEncoder(strength)));
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
