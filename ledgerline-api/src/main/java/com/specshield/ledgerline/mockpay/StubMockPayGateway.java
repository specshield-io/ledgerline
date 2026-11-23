package com.specshield.ledgerline.mockpay;

import com.specshield.ledgerline.application.PaymentGateway;
import com.specshield.ledgerline.domain.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Placeholder for the MockPay client. It contacts nothing and returns a deterministic synthetic reference. The
 * real MockPay integration replaces it in a later step (A3).
 */
@Component
public class StubMockPayGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(StubMockPayGateway.class);

    @Override
    public String requestPayment(Order order) {
        String reference = "mp_stub_" + order.id();
        log.info(
                "MockPay stub: payment requested for order {} ({} {} minor units)",
                order.id(),
                order.amountMinor(),
                order.currency());
        return reference;
    }
}
