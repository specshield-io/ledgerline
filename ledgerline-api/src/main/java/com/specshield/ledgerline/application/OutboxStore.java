package com.specshield.ledgerline.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Port for the outbox relay and its administration.
 *
 * <p>The {@code mark*} methods are conditional on the claim: they change the row only if it is still IN_FLIGHT with
 * the given {@code claimedAt}. A dispatcher whose row was meanwhile returned to PENDING by the watchdog (and possibly
 * claimed again) therefore cannot overwrite the newer state; they return false in that case.
 */
public interface OutboxStore {

    /**
     * In one transaction: selects up to {@code limit} PENDING rows due at {@code now}, oldest id first, with
     * {@code FOR UPDATE SKIP LOCKED}, and marks them IN_FLIGHT with {@code claimedAt = now}.
     */
    List<OutboxMessage> claimDue(int limit, Instant now);

    boolean markDelivered(long id, Instant claimedAt, Instant deliveredAt);

    /** One more failed attempt; back to PENDING, due at {@code nextAttemptAt}. */
    boolean markRetry(long id, Instant claimedAt, String error, Instant nextAttemptAt);

    /** One more failed attempt and no retries left. */
    boolean markDead(long id, Instant claimedAt, String error);

    /** Returns IN_FLIGHT rows claimed before {@code claimedBefore} to PENDING, due at {@code now}. */
    int releaseStuck(Instant claimedBefore, String reason, Instant now);

    long countByStatus(OutboxStatus status);

    Optional<Instant> oldestPendingCreatedAt();

    record OutboxPage(List<OutboxMessage> items, int page, int size, long totalElements, int totalPages) {}

    /** Newest first. */
    OutboxPage page(Optional<OutboxStatus> status, int page, int size);

    /** Locks the row for the rest of the caller's transaction. */
    Optional<OutboxMessage> findByIdForUpdate(long id);

    /** DEAD to PENDING: attempts reset to 0, due at {@code now}, last error cleared. Joins the caller's transaction. */
    void requeue(long id, Instant now);
}
