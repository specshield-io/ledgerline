package com.specshield.ledgerline.config;

import com.specshield.ledgerline.outbox.BackoffPolicy;
import com.specshield.ledgerline.outbox.MerchantProperties;
import com.specshield.ledgerline.outbox.OutboxProperties;
import com.specshield.ledgerline.webhook.MerchantSinkProperties;
import java.security.SecureRandom;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({OutboxProperties.class, MerchantProperties.class, MerchantSinkProperties.class})
public class OutboxConfig {

    @Bean
    BackoffPolicy outboxBackoffPolicy(OutboxProperties properties) {
        // Jitter needs no cryptographic strength, but SecureRandom is thread safe and avoids correlated seeds
        // across instances started at the same moment.
        return new BackoffPolicy(
                properties.backoffBase(), properties.backoffCap(), properties.maxAttempts(), new SecureRandom());
    }
}
