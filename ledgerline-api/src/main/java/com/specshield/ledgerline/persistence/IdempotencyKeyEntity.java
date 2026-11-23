package com.specshield.ledgerline.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import org.springframework.data.domain.Persistable;

/**
 * Table {@code idempotency_keys}. Implements {@link Persistable} because the key is assigned by the client: without
 * it Spring Data would treat every save as an update and silently overwrite an existing key.
 */
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyKeyEntity implements Persistable<String> {

    @Id
    @Column(name = "key")
    private String key;

    @Column(name = "request_hash", nullable = false, length = 128)
    private String requestHash;

    @Column(name = "response_status")
    private Integer responseStatus;

    /** Raw response bytes (bytea), replayed byte for byte. */
    @Column(name = "response_body")
    private byte[] responseBody;

    @Column(name = "response_content_type")
    private String responseContentType;

    @Column(name = "response_location", length = 2048)
    private String responseLocation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private IdempotencyState state;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Transient
    private boolean isNew;

    protected IdempotencyKeyEntity() {}

    /** A new key in {@link IdempotencyState#IN_PROGRESS}. */
    public IdempotencyKeyEntity(String key, String requestHash, Instant createdAt, Instant expiresAt) {
        this.key = key;
        this.requestHash = requestHash;
        this.state = IdempotencyState.IN_PROGRESS;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.isNew = true;
    }

    public void complete(int responseStatus, byte[] responseBody, String contentType, String location) {
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
        this.responseContentType = contentType;
        this.responseLocation = location;
        this.state = IdempotencyState.COMPLETED;
    }

    @Override
    public String getId() {
        return key;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public Integer getResponseStatus() {
        return responseStatus;
    }

    public byte[] getResponseBody() {
        return responseBody;
    }

    public String getResponseContentType() {
        return responseContentType;
    }

    public String getResponseLocation() {
        return responseLocation;
    }

    public IdempotencyState getState() {
        return state;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
