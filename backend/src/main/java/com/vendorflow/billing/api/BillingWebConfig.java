package com.vendorflow.billing.api;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class BillingWebConfig implements WebMvcConfigurer {

    private final ReadOnlyGuardInterceptor guard;

    public BillingWebConfig(ReadOnlyGuardInterceptor guard) {
        this.guard = guard;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(guard).addPathPatterns("/api/v1/**").excludePathPatterns(
                "/api/v1/auth/**",        // login/logout/signup/password reset: no tenant data
                "/api/v1/billing/**",     // the way OUT of read-only (checkout, portal)
                "/api/v1/webhooks/**",    // Stripe must always be able to deliver
                "/api/v1/session/**",     // switching to another (possibly active) organization
                "/api/v1/invitations/**", // accepting an invitation to ANOTHER organization
                "/api/v1/me/**");         // account endpoints
    }
}
