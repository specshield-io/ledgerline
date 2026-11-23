package com.specshield.ledgerline.persistence;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdempotencyKeyJpaRepository extends JpaRepository<IdempotencyKeyEntity, String> {

    @Modifying
    @Query(nativeQuery = true, value = """
                    INSERT INTO idempotency_keys (key, request_hash, state, created_at, expires_at)
                    VALUES (:key, :hash, 'IN_PROGRESS', :now, :expiresAt)
                    ON CONFLICT (key) DO NOTHING
                    """)
    int insertIfAbsent(
            @Param("key") String key,
            @Param("hash") String hash,
            @Param("now") Instant now,
            @Param("expiresAt") Instant expiresAt);

    @Modifying
    @Query(nativeQuery = true, value = """
                    UPDATE idempotency_keys
                       SET state = 'COMPLETED', response_status = :status, response_body = :body,
                           response_content_type = :contentType, response_location = :location
                     WHERE key = :key AND state = 'IN_PROGRESS'
                    """)
    int complete(
            @Param("key") String key,
            @Param("status") int status,
            @Param("body") byte[] body,
            @Param("contentType") String contentType,
            @Param("location") String location);

    @Modifying
    @Query(nativeQuery = true, value = "DELETE FROM idempotency_keys WHERE key = :key AND state = 'IN_PROGRESS'")
    int deleteInProgress(@Param("key") String key);

    @Modifying
    @Query(nativeQuery = true, value = "DELETE FROM idempotency_keys WHERE key = :key AND expires_at <= :now")
    int deleteIfExpired(@Param("key") String key, @Param("now") Instant now);

    @Modifying
    @Query(nativeQuery = true, value = "DELETE FROM idempotency_keys WHERE expires_at <= :now")
    int deleteExpired(@Param("now") Instant now);
}
