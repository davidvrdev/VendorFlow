package com.vendorflow.shared.security;

import com.vendorflow.shared.error.Problems;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.savedrequest.NullRequestCache;

/**
 * Phase 0 baseline: deny by default. Phase 1 replaces the "no users" setup with real session-based login (and the
 * CSRF cookie repository). Lives in shared.security because it is cross-cutting; Phase 1 feature code goes in
 * the identity package and plugs into this chain.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ProblemJsonWriter writer) {
        // CSRF stays at its secure default (enabled). Do not disable it.
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().authenticated())
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

    /**
     * An explicit, empty user store. Boot's UserDetailsServiceAutoConfiguration only creates the default
     * "user" with a random password logged at startup when no UserDetailsService (or AuthenticationProvider /
     * AuthenticationManager) bean exists, so defining this bean switches that off without excluding autoconfig.
     * Replaced by a JPA-backed service in Phase 1.
     */
    @Bean
    UserDetailsService userDetailsService() {
        return new InMemoryUserDetailsManager();
    }
}
