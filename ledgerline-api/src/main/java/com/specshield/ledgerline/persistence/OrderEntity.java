package com.specshield.ledgerline.persistence;

import com.specshield.ledgerline.domain.OrderStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** Table {@code orders}. Only {@link OrderMapper} converts between this and the domain {@code Order}. */
@Entity
@Table(name = "orders")
class OrderEntity {

    @Id
    private UUID id;

    @Column(name = "merchant_ref", nullable = false, length = 64)
    private String merchantRef;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private OrderStatus status;

    @Column(name = "refunded_minor", nullable = false)
    private long refundedMinor;

    @Column(name = "last_event_sequence", nullable = false)
    private long lastEventSequence;

    /** Null until first persisted, which is how Spring Data tells a new entity from an existing one. */
    @Version
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "last_provider_event_at")
    private Instant lastProviderEventAt;

    protected OrderEntity() {}

    OrderEntity(UUID id) {
        this.id = id;
    }

    UUID getId() {
        return id;
    }

    String getMerchantRef() {
        return merchantRef;
    }

    void setMerchantRef(String merchantRef) {
        this.merchantRef = merchantRef;
    }

    long getAmountMinor() {
        return amountMinor;
    }

    void setAmountMinor(long amountMinor) {
        this.amountMinor = amountMinor;
    }

    String getCurrency() {
        return currency;
    }

    void setCurrency(String currency) {
        this.currency = currency;
    }

    OrderStatus getStatus() {
        return status;
    }

    void setStatus(OrderStatus status) {
        this.status = status;
    }

    long getRefundedMinor() {
        return refundedMinor;
    }

    void setRefundedMinor(long refundedMinor) {
        this.refundedMinor = refundedMinor;
    }

    long getLastEventSequence() {
        return lastEventSequence;
    }

    void setLastEventSequence(long lastEventSequence) {
        this.lastEventSequence = lastEventSequence;
    }

    Long getVersion() {
        return version;
    }

    void setVersion(Long version) {
        this.version = version;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }

    void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    Instant getLastProviderEventAt() {
        return lastProviderEventAt;
    }

    void setLastProviderEventAt(Instant lastProviderEventAt) {
        this.lastProviderEventAt = lastProviderEventAt;
    }
}
