package com.specshield.ledgerline.config;

import com.specshield.ledgerline.domain.OrderIds;
import com.specshield.ledgerline.domain.OrderStateMachine;
import java.security.SecureRandom;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Wires the framework-free domain objects, and enables scheduled jobs. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class DomainConfig {

    @Bean
    OrderStateMachine orderStateMachine(Clock clock) {
        return new OrderStateMachine(clock);
    }

    @Bean
    OrderIds orderIds(Clock clock) {
        return new OrderIds(clock, new SecureRandom());
    }
}
