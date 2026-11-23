package com.specshield.ledgerline.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Table {@code order_events_inbox}: raw provider events, unique per (provider, provider_event_id). */
@Entity
@Table(name = "order_events_inbox")
public class InboxEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String provider;

    @Column(name = "provider_event_id", nullable = false)
    private String providerEventId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "signature_valid", nullable = false)
    private boolean signatureValid;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private InboxOutcome outcome;

    protected InboxEventEntity() {}

    public InboxEventEntity(
            String provider, String providerEventId, String payload, boolean signatureValid, Instant receivedAt) {
        this.provider = provider;
        this.providerEventId = providerEventId;
        this.payload = payload;
        this.signatureValid = signatureValid;
        this.receivedAt = receivedAt;
    }

    /** Records the processing result. */
    public void markProcessed(InboxOutcome outcome, Instant processedAt) {
        this.outcome = outcome;
        this.processedAt = processedAt;
    }

    public Long getId() {
        return id;
    }

    public String getProvider() {
        return provider;
    }

    public String getProviderEventId() {
        return providerEventId;
    }

    public String getPayload() {
        return payload;
    }

    public boolean isSignatureValid() {
        return signatureValid;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    public InboxOutcome getOutcome() {
        return outcome;
    }
}
