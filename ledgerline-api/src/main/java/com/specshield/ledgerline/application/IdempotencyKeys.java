package com.specshield.ledgerline.application;

import java.time.Instant;
import java.util.Optional;

/** Port for idempotency key storage. Every method runs in, and commits, its own transaction. */
public interface IdempotencyKeys {

    /** INSERT ... ON CONFLICT DO NOTHING. Returns true if this call created the IN_PROGRESS row. */
    boolean insertIfAbsent(String key, String requestHash, Instant now, Instant expiresAt);

    Optional<StoredIdempotencyKey> find(String key);

    /** Stores the response and marks the key COMPLETED. Returns false if the row was not IN_PROGRESS. */
    boolean complete(String key, StoredResponse response);

    /** Deletes the row if it is still IN_PROGRESS, so the client may retry. */
    void deleteInProgress(String key);

    /** Deletes this key if it has expired at {@code now}. Returns true if a row was deleted. */
    boolean deleteIfExpired(String key, Instant now);

    /** Deletes every key that has expired at {@code now}. Returns the number deleted. */
    int deleteExpired(Instant now);
}
