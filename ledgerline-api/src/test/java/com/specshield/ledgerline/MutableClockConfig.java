package com.specshield.ledgerline;

import com.specshield.ledgerline.web.FailureInjectionHook;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** A {@link MutableClock} that wins over the production {@code Clock.systemUTC()} wherever a Clock is injected. */
@TestConfiguration(proxyBeanMethods = false)
public class MutableClockConfig {

    @Bean
    @Primary
    MutableClock mutableClock() {
        return new MutableClock();
    }

    /** Replaces the no-op PaymentEventHook so tests can inject a failure before commit (LL-I12). */
    @Bean
    @Primary
    FailureInjectionHook failureInjectionHook() {
        return new FailureInjectionHook();
    }
}
