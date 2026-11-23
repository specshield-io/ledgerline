package com.specshield.ledgerline.chaos;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** One scenario run, kept in memory for an hour. Thread safe. */
public final class ScenarioRun {

    public enum Status {
        RUNNING,
        COMPLETED,
        FAILED
    }

    /** One thing the run did, for the run view. */
    /**
     * @param httpStatus the webhook receiver's answer, for deliveries
     * @param result the receiver's problem type for an error answer (e.g. {@code invalid-signature}), else null
     */
    public record Step(Instant at, String description, Integer httpStatus, String result) {}

    private final String id;
    private final Scenario scenario;
    private final UUID orderId;
    private final Instant startedAt;
    private final String correlationId;
    private final List<Step> steps = new CopyOnWriteArrayList<>();
    private volatile Status status = Status.RUNNING;
    private volatile Instant finishedAt;
    private volatile String orderStatusAtEnd;
    private volatile String error;

    ScenarioRun(String id, Scenario scenario, UUID orderId, Instant startedAt, String correlationId) {
        this.id = id;
        this.scenario = scenario;
        this.orderId = orderId;
        this.startedAt = startedAt;
        this.correlationId = correlationId;
    }

    void step(Step step) {
        steps.add(step);
    }

    void complete(Instant at, String orderStatus) {
        this.orderStatusAtEnd = orderStatus;
        this.finishedAt = at;
        this.status = Status.COMPLETED;
    }

    void fail(Instant at, String error) {
        this.error = error;
        this.finishedAt = at;
        this.status = Status.FAILED;
    }

    public String id() {
        return id;
    }

    public Scenario scenario() {
        return scenario;
    }

    public UUID orderId() {
        return orderId;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public String correlationId() {
        return correlationId;
    }

    public List<Step> steps() {
        return List.copyOf(steps);
    }

    public Status status() {
        return status;
    }

    public Instant finishedAt() {
        return finishedAt;
    }

    public String orderStatusAtEnd() {
        return orderStatusAtEnd;
    }

    public String error() {
        return error;
    }
}
