package com.vendorflow.billing;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Replaces the Stripe API adapter in billing tests. The webhook verifier stays REAL (real signatures). */
@TestConfiguration(proxyBeanMethods = false)
public class BillingTestConfig {

    @Bean
    @Primary
    FakeBillingGateway fakeBillingGateway() {
        return new FakeBillingGateway();
    }
}
