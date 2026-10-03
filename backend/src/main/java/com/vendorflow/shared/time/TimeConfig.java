package com.vendorflow.shared.time;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Features inject {@link Clock} instead of calling Instant.now(), so tests can control "today". */
@Configuration(proxyBeanMethods = false)
public class TimeConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
