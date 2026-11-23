package com.specshield.ledgerline.mockpay;

import com.specshield.ledgerline.application.PaymentGateway;
import com.specshield.ledgerline.domain.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Used when {@code ledgerline.mockpay.enabled} is false: contacts nothing, sends no webhooks and returns a
 * deterministic synthetic reference. {@link MockPayGateway} is the real simulator.
 */
@Component
@ConditionalOnProperty(name = "ledgerline.mockpay.enabled", havingValue = "false", matchIfMissing = true)
public class StubMockPayGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(StubMockPayGateway.class);

    @Override
    public String startPayment(Order order) {
        String reference = "mp_stub_" + order.id();
        log.info(
                "MockPay stub: payment requested for order {} ({} {} minor units)",
                order.id(),
                order.amountMinor(),
                order.currency());
        return reference;
    }
}
