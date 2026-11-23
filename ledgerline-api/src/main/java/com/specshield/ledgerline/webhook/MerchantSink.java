package com.specshield.ledgerline.webhook;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * State of the demo merchant: how it answers, and what it received. In memory only; it is a stand-in for a merchant's
 * system, not part of Ledgerline. It deduplicates by {@code Ledgerline-Event-Id}, as a real receiver must, and
 * reports repeats as duplicates.
 *
 * <p>{@link Mode#FAIL} answers 500 and {@link Mode#SLOW} answers after a delay. The chaos API (A5) will switch modes
 * over HTTP; until then they are set in code (tests) only.
 */
@Component
@ConditionalOnProperty(name = "ledgerline.merchant.sink.enabled", havingValue = "true")
public class MerchantSink {

    public enum Mode {
        OK,
        FAIL,
        SLOW
    }

    /**
     * One received request.
     *
     * @param eventId the {@code Ledgerline-Event-Id} header, or null when missing
     * @param duplicate this event id had already been accepted
     * @param responseStatus what the sink answered
     */
    public record Delivery(
            Instant receivedAt,
            String eventId,
            String attempt,
            String eventType,
            boolean signatureValid,
            boolean duplicate,
            int responseStatus) {}

    private final WebhookSignatureVerifier verifier;
    private final Clock clock;
    private final int capacity;
    private final Deque<Delivery> deliveries = new ArrayDeque<>();
    private final Set<String> accepted = new HashSet<>();
    private volatile Mode mode = Mode.OK;
    private volatile Duration slowDelay = Duration.ofSeconds(5);

    public MerchantSink(MerchantSinkProperties properties, Clock clock) {
        if (properties.secrets().isEmpty()) {
            throw new IllegalStateException("ledgerline.merchant.sink.secrets is required when the sink is enabled");
        }
        this.verifier = new WebhookSignatureVerifier(properties.secrets(), properties.tolerance());
        this.clock = clock;
        this.capacity = properties.capacity();
    }

    public Mode mode() {
        return mode;
    }

    public Duration slowDelay() {
        return slowDelay;
    }

    public void setMode(Mode mode) {
        this.mode = Objects.requireNonNull(mode, "mode");
    }

    public void setSlowDelay(Duration delay) {
        this.slowDelay = Objects.requireNonNull(delay, "delay");
    }

    WebhookSignatureVerifier verifier() {
        return verifier;
    }

    Clock clock() {
        return clock;
    }

    /** Records a request; returns whether this event id was seen before (and accepted). */
    synchronized boolean record(
            String eventId, String attempt, String eventType, boolean signatureValid, int responseStatus) {
        boolean duplicate = eventId != null && accepted.contains(eventId);
        if (signatureValid && responseStatus / 100 == 2 && eventId != null) {
            accepted.add(eventId);
        }
        deliveries.addLast(
                new Delivery(clock.instant(), eventId, attempt, eventType, signatureValid, duplicate, responseStatus));
        while (deliveries.size() > capacity) {
            deliveries.removeFirst();
        }
        return duplicate;
    }

    /** Newest first. */
    public synchronized List<Delivery> deliveries() {
        return List.copyOf(deliveries.reversed());
    }

    public synchronized void reset() {
        deliveries.clear();
        accepted.clear();
        mode = Mode.OK;
        slowDelay = Duration.ofSeconds(5);
    }
}
