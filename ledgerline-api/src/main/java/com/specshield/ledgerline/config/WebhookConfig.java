package com.specshield.ledgerline.config;

import com.specshield.ledgerline.application.PaymentEventHook;
import com.specshield.ledgerline.domain.OrderEventSequencer;
import com.specshield.ledgerline.domain.OrderStateMachine;
import com.specshield.ledgerline.mockpay.MockPayProperties;
import com.specshield.ledgerline.webhook.WebhookProperties;
import com.specshield.ledgerline.webhook.WebhookSignatureVerifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({WebhookProperties.class, MockPayProperties.class})
public class WebhookConfig {

    @Bean
    WebhookSignatureVerifier mockPaySignatureVerifier(WebhookProperties properties) {
        return new WebhookSignatureVerifier(properties.secrets(), properties.tolerance());
    }

    @Bean
    OrderEventSequencer orderEventSequencer(OrderStateMachine stateMachine) {
        return new OrderEventSequencer(stateMachine);
    }

    /** Does nothing in production. Tests override it with a @Primary bean to inject failures. */
    @Bean
    PaymentEventHook paymentEventHook() {
        return changed -> {};
    }
}
