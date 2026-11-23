package com.specshield.ledgerline.chaos;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.specshield.ledgerline.application.AuditRecord;
import com.specshield.ledgerline.application.AuditTrail;
import com.specshield.ledgerline.application.OrderService;
import com.specshield.ledgerline.domain.Money;
import com.specshield.ledgerline.domain.Order;
import com.specshield.ledgerline.domain.OrderStatus;
import com.specshield.ledgerline.mockpay.MockPayScripts;
import com.specshield.ledgerline.mockpay.MockPayScripts.Payment;
import com.specshield.ledgerline.mockpay.MockPayScripts.Tamper;
import com.specshield.ledgerline.webhook.MerchantSink;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Starts and runs chaos scenarios.
 *
 * <p>{@link #start} takes one of {@code maxConcurrentRuns} slots (or fails with {@link ChaosExceptions.CapacityExceeded}),
 * creates a fresh synthetic order and returns at once; the steps run on a virtual thread, which frees the slot when it
 * ends. Every step is written to the order's audit log ({@code CHAOS_STEP}, actor {@code chaos:<scenario>}), so the
 * order timeline shows the run as it happens, next to the provider events and their outcomes.
 */
@Service
public class ChaosService {

    static final long ORDER_AMOUNT_MINOR = 4_999;
    static final long PARTIAL_REFUND_MINOR = 1_500;
    static final int DUPLICATE_DELIVERIES = 5;

    private static final Logger log = LoggerFactory.getLogger(ChaosService.class);

    private final OrderService orders;
    private final ObjectProvider<MockPayScripts> mockPay;
    private final ObjectProvider<MerchantSink> merchant;
    private final AuditTrail audit;
    private final ScenarioHook hook;
    private final ChaosProperties properties;
    private final Clock clock;
    private final JsonMapper json;
    private final Semaphore slots;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Cache<String, ScenarioRun> runs = Caffeine.newBuilder()
            .maximumSize(1_000)
            .expireAfterWrite(Duration.ofHours(1))
            .build();

    public ChaosService(
            OrderService orders,
            ObjectProvider<MockPayScripts> mockPay,
            ObjectProvider<MerchantSink> merchant,
            AuditTrail audit,
            ScenarioHook hook,
            ChaosProperties properties,
            Clock clock,
            JsonMapper json) {
        this.orders = orders;
        this.mockPay = mockPay;
        this.merchant = merchant;
        this.audit = audit;
        this.hook = hook;
        this.properties = properties;
        this.clock = clock;
        this.json = json;
        this.slots = new Semaphore(properties.maxConcurrentRuns());
    }

    public ScenarioRun start(Scenario scenario) {
        MockPayScripts provider = mockPay.getIfAvailable();
        if (provider == null) {
            throw new ChaosExceptions.Unavailable(
                    "Chaos scenarios need MockPay, which is disabled in this deployment.");
        }
        MerchantSink sink = merchant.getIfAvailable();
        if (scenario.needsMerchantSink() && sink == null) {
            throw new ChaosExceptions.Unavailable(
                    "This scenario needs the demo merchant, which is disabled in this deployment.");
        }
        if (!slots.tryAcquire()) {
            throw new ChaosExceptions.CapacityExceeded(properties.maxConcurrentRuns());
        }
        try {
            String runId = UUID.randomUUID().toString();
            String correlationId = MDC.get(OrderService.CORRELATION_ID_MDC_KEY);
            Order order = orders.create(
                    "chaos-" + scenario.slug() + "-" + runId.substring(0, 8), ORDER_AMOUNT_MINOR, Money.USD);
            ScenarioRun run = new ScenarioRun(runId, scenario, order.id(), clock.instant(), correlationId);
            runs.put(runId, run);
            step(run, "scenario started: " + scenario.description(), null);
            executor.submit(() -> execute(run, provider, sink));
            return run;
        } catch (RuntimeException e) {
            slots.release();
            throw e;
        }
    }

    public Optional<ScenarioRun> find(String runId) {
        return Optional.ofNullable(runs.getIfPresent(runId));
    }

    /** Slots currently free (for tests and metrics). */
    public int availableSlots() {
        return slots.availablePermits();
    }

    private void execute(ScenarioRun run, MockPayScripts provider, MerchantSink sink) {
        if (run.correlationId() != null) {
            MDC.put(OrderService.CORRELATION_ID_MDC_KEY, run.correlationId());
        }
        try {
            hook.beforeSteps(run);
            steps(run, provider, sink);
            OrderStatus end = orders.get(run.orderId()).status();
            step(run, "scenario finished; order is " + end, null);
            run.complete(clock.instant(), end.name());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            run.fail(clock.instant(), "interrupted");
        } catch (RuntimeException | ExecutionException e) {
            log.warn("chaos run {} ({}) failed: {}", run.id(), run.scenario().slug(), e.toString());
            safeStep(run, "scenario failed: " + e.getClass().getSimpleName());
            run.fail(clock.instant(), e.getClass().getSimpleName());
        } finally {
            slots.release();
            MDC.remove(OrderService.CORRELATION_ID_MDC_KEY);
        }
    }

    private void steps(ScenarioRun run, MockPayScripts provider, MerchantSink sink)
            throws InterruptedException, ExecutionException {
        Order order = orders.get(run.orderId());
        switch (run.scenario()) {
            case HAPPY_PATH -> {
                OrderService.PaymentRequested requested = orders.pay(order.id());
                step(run, "payment requested; MockPay will report it (" + requested.paymentReference() + ")", null);
                awaitStatus(run, OrderStatus.PAID);
            }
            case DUPLICATE_STORM -> {
                Payment payment = initiate(run, provider, order);
                provider.settle(payment, true);
                String eventId = provider.newEventId();
                List<Future<MockPayScripts.Delivery>> storm = new ArrayList<>();
                for (int i = 0; i < DUPLICATE_DELIVERIES; i++) {
                    storm.add(executor.submit(() -> provider.send(
                            payment,
                            eventId,
                            "PAYMENT_SUCCEEDED",
                            2,
                            order.amountMinor(),
                            Tamper.NONE,
                            run.correlationId())));
                }
                for (Future<MockPayScripts.Delivery> delivery : storm) {
                    step(
                            run,
                            "PAYMENT_SUCCEEDED " + eventId + " delivered (one of 5 at once)",
                            delivery.get().status());
                }
            }
            case TAMPERED_WEBHOOK -> {
                Payment payment = initiate(run, provider, order);
                send(
                        run,
                        provider,
                        payment,
                        "PAYMENT_SUCCEEDED",
                        2,
                        order.amountMinor(),
                        Tamper.BODY_ALTERED_AFTER_SIGNING,
                        "PAYMENT_SUCCEEDED with the amount changed to 1 cent after signing");
            }
            case REPLAY_ATTACK -> {
                Payment payment = initiate(run, provider, order);
                send(
                        run,
                        provider,
                        payment,
                        "PAYMENT_SUCCEEDED",
                        2,
                        order.amountMinor(),
                        Tamper.STALE_TIMESTAMP,
                        "PAYMENT_SUCCEEDED signed 10 minutes ago (replayed)");
            }
            case OUT_OF_ORDER -> {
                Payment payment = initiate(run, provider, order);
                provider.settle(payment, true);
                send(
                        run,
                        provider,
                        payment,
                        "PAYMENT_SUCCEEDED",
                        2,
                        order.amountMinor(),
                        Tamper.NONE,
                        "PAYMENT_SUCCEEDED (sequence 2)");
                send(
                        run,
                        provider,
                        payment,
                        "PAYMENT_FAILED",
                        1,
                        order.amountMinor(),
                        Tamper.NONE,
                        "late PAYMENT_FAILED (sequence 1) from an earlier attempt");
            }
            case MERCHANT_DOWN -> {
                sink.failFor(properties.merchantOutage());
                step(
                        run,
                        "demo merchant answers 500 for "
                                + properties.merchantOutage().toSeconds() + " s",
                        null);
                pay(run, provider, order);
            }
            case MERCHANT_SLOW -> {
                sink.slowFor(properties.merchantSlowDelay(), properties.merchantOutage());
                step(
                        run,
                        "demo merchant takes " + properties.merchantSlowDelay().toSeconds() + " s to answer, for "
                                + properties.merchantOutage().toSeconds() + " s",
                        null);
                pay(run, provider, order);
            }
            case REFUND_FLOW -> {
                Payment payment = pay(run, provider, order);
                send(
                        run,
                        provider,
                        payment,
                        "REFUND_SUCCEEDED",
                        3,
                        PARTIAL_REFUND_MINOR,
                        Tamper.NONE,
                        "REFUND_SUCCEEDED for " + PARTIAL_REFUND_MINOR + " cents (partial)");
                long rest = order.amountMinor() - PARTIAL_REFUND_MINOR;
                send(
                        run,
                        provider,
                        payment,
                        "REFUND_SUCCEEDED",
                        4,
                        rest,
                        Tamper.NONE,
                        "REFUND_SUCCEEDED for the remaining " + rest + " cents");
            }
        }
    }

    /** PAYMENT_INITIATED (sequence 1): the order becomes PAYMENT_PENDING. */
    private Payment initiate(ScenarioRun run, MockPayScripts provider, Order order) {
        Payment payment = provider.open(order);
        send(
                run,
                provider,
                payment,
                "PAYMENT_INITIATED",
                1,
                order.amountMinor(),
                Tamper.NONE,
                "PAYMENT_INITIATED (sequence 1)");
        return payment;
    }

    /** INITIATED then SUCCEEDED: the order becomes PAID and an order.paid notification is queued. */
    private Payment pay(ScenarioRun run, MockPayScripts provider, Order order) {
        Payment payment = initiate(run, provider, order);
        provider.settle(payment, true);
        send(
                run,
                provider,
                payment,
                "PAYMENT_SUCCEEDED",
                2,
                order.amountMinor(),
                Tamper.NONE,
                "PAYMENT_SUCCEEDED (sequence 2)");
        return payment;
    }

    private void send(
            ScenarioRun run,
            MockPayScripts provider,
            Payment payment,
            String type,
            long sequence,
            long amountMinor,
            Tamper tamper,
            String description) {
        MockPayScripts.Delivery delivery =
                provider.send(payment, provider.newEventId(), type, sequence, amountMinor, tamper, run.correlationId());
        step(run, description, delivery.status(), delivery.problem());
    }

    private void awaitStatus(ScenarioRun run, OrderStatus expected) throws InterruptedException {
        long deadline = System.nanoTime() + properties.awaitTimeout().toNanos();
        while (orders.get(run.orderId()).status() != expected) {
            if (System.nanoTime() > deadline) {
                step(
                        run,
                        "order did not reach " + expected + " within "
                                + properties.awaitTimeout().toSeconds() + " s",
                        null);
                return;
            }
            Thread.sleep(50);
        }
    }

    private void step(ScenarioRun run, String description, Integer httpStatus) {
        step(run, description, httpStatus, null);
    }

    private void step(ScenarioRun run, String description, Integer httpStatus, String result) {
        run.step(new ScenarioRun.Step(clock.instant(), description, httpStatus, result));
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("scenarioRunId", run.id());
        detail.put("scenario", run.scenario().slug());
        detail.put("step", description);
        if (httpStatus != null) {
            detail.put("httpStatus", httpStatus);
        }
        if (result != null) {
            detail.put("result", result);
        }
        audit.append(new AuditRecord(
                null,
                run.orderId(),
                "CHAOS_STEP",
                json.writeValueAsString(detail),
                "chaos:" + run.scenario().slug(),
                run.correlationId(),
                clock.instant()));
    }

    private void safeStep(ScenarioRun run, String description) {
        try {
            step(run, description, null);
        } catch (RuntimeException ignored) {
            // the audit write itself failed; the run view still has the error
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
