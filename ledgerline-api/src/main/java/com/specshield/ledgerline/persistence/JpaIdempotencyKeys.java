package com.specshield.ledgerline.persistence;

import com.specshield.ledgerline.application.IdempotencyKeys;
import com.specshield.ledgerline.application.StoredIdempotencyKey;
import com.specshield.ledgerline.application.StoredResponse;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Each method commits in its own transaction, independent of any business transaction. */
@Repository
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class JpaIdempotencyKeys implements IdempotencyKeys {

    private final IdempotencyKeyJpaRepository repository;

    JpaIdempotencyKeys(IdempotencyKeyJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public boolean insertIfAbsent(String key, String requestHash, Instant now, Instant expiresAt) {
        return repository.insertIfAbsent(key, requestHash, now, expiresAt) == 1;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<StoredIdempotencyKey> find(String key) {
        return repository.findById(key).map(e -> {
            boolean completed = e.getState() == IdempotencyState.COMPLETED;
            StoredResponse response = completed
                    ? new StoredResponse(
                            e.getResponseStatus(),
                            e.getResponseBody(),
                            e.getResponseContentType(),
                            e.getResponseLocation())
                    : null;
            return new StoredIdempotencyKey(
                    key, e.getRequestHash(), completed, response, e.getCreatedAt(), e.getExpiresAt());
        });
    }

    @Override
    public boolean complete(String key, StoredResponse response) {
        return repository.complete(key, response.status(), response.body(), response.contentType(), response.location())
                == 1;
    }

    @Override
    public void deleteInProgress(String key) {
        repository.deleteInProgress(key);
    }

    @Override
    public boolean deleteIfExpired(String key, Instant now) {
        return repository.deleteIfExpired(key, now) == 1;
    }

    @Override
    public int deleteExpired(Instant now) {
        return repository.deleteExpired(now);
    }
}
