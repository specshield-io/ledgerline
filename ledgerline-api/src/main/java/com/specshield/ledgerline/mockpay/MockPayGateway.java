package com.specshield.ledgerline.mockpay;

import com.specshield.ledgerline.application.PaymentGateway;
import com.specshield.ledgerline.domain.Order;
import com.specshield.ledgerline.domain.OrderStatus;
import com.specshield.ledgerline.webhook.WebhookSigner;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * The simulated provider. {@link #startPayment} records the payment in MockPay's ledger and, once the caller's
 * transaction commits, sends real signed HTTP webhooks from a virtual thread: PAYMENT_INITIATED at once (skipped
 * when the order is retrying after PAYMENT_FAILED), then the outcome after a random 300-800 ms.
 */
@Component
@ConditionalOnProperty(name = "ledgerline.mockpay.enabled", havingValue = "true")
public class MockPayGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(MockPayGateway.class);

    private final MockPayProperties properties;
    private final MockPayLedger ledger;
    private final MockPayWebhookSender sender;
    private final Environment environment;
    private final Clock clock;
    private final JsonMapper json;
    private final RandomGenerator random;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final HttpClient http;

    public MockPayGateway(
            MockPayProperties properties, MockPayLedger ledger, Environment environment, Clock clock, JsonMapper json) {
        this.properties = properties;
        this.ledger = ledger;
        this.environment = environment;
        this.clock = clock;
        this.json = json;
        this.random = RandomGenerator.of("L64X128MixRandom");
        this.http = HttpClient.newBuilder()
                .executor(executor)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.sender = new MockPayWebhookSender(
                http,
                new WebhookSigner(properties.signingSecrets()),
                clock,
                properties.maxDeliveryAttempts(),
                properties.retryBackoff());
    }

    record Event(String id, String type, long sequence) {}

    @Override
    public String startPayment(Order order) {
        String paymentId = "mp_" + randomHex(12);
        long sequence = ledger.lastSequence(order.id());
        List<Event> events = new ArrayList<>();
        if (order.status() == OrderStatus.CREATED) {
            events.add(new Event("evt_" + randomHex(12), "PAYMENT_INITIATED", ++sequence));
        }
        events.add(new Event("evt_" + randomHex(12), properties.defaultOutcome(), ++sequence));
        ledger.record(paymentId, order.id(), order.amountMinor(), order.currency(), sequence, clock.instant());

        Runnable dispatch = () -> executor.submit(() -> send(paymentId, order, events));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatch.run();
                }
            });
        } else {
            dispatch.run();
        }
        log.info("MockPay payment {} started for order {} ({} event(s))", paymentId, order.id(), events.size());
        return paymentId;
    }

    private void send(String paymentId, Order order, List<Event> events) {
        try {
            URI url = URI.create(environment.resolveRequiredPlaceholders(properties.webhookUrl()));
            for (int i = 0; i < events.size(); i++) {
                Event event = events.get(i);
                boolean outcome = i == events.size() - 1;
                if (outcome) {
                    long min = properties.minDelay().toMillis();
                    long max = properties.maxDelay().toMillis();
                    Thread.sleep(min + (max > min ? random.nextLong(max - min + 1) : 0));
                    ledger.settle(
                            paymentId,
                            event.type().equals("PAYMENT_SUCCEEDED")
                                    ? MockPayLedger.Status.SUCCEEDED
                                    : MockPayLedger.Status.FAILED,
                            clock.instant());
                }
                sender.deliver(url, event.id(), body(event, order));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.error("MockPay dispatch for payment {} failed", paymentId, e);
        }
    }

    private byte[] body(Event event, Order order) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", order.id().toString());
        data.put("amountMinor", order.amountMinor());
        data.put("currency", order.currency());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", event.id());
        body.put("type", event.type());
        body.put("created", clock.instant().getEpochSecond());
        body.put("sequence", event.sequence());
        body.put("data", data);
        return json.writeValueAsBytes(body);
    }

    private String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        random.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    @PreDestroy
    void shutdown() throws InterruptedException {
        executor.shutdown();
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
            executor.shutdownNow();
        }
        http.close();
    }
}
