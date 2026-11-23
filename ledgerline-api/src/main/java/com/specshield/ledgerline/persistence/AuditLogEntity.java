package com.specshield.ledgerline.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Table {@code audit_log}. Append only. */
@Entity
@Table(name = "audit_log")
public class AuditLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id")
    private UUID orderId;

    @Column(nullable = false, length = 64)
    private String kind;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String detail;

    @Column(nullable = false, length = 128)
    private String actor;

    @Column(name = "correlation_id", length = 128)
    private String correlationId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AuditLogEntity() {}

    public AuditLogEntity(
            UUID orderId, String kind, String detail, String actor, String correlationId, Instant createdAt) {
        this.orderId = orderId;
        this.kind = kind;
        this.detail = detail;
        this.actor = actor;
        this.correlationId = correlationId;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public String getKind() {
        return kind;
    }

    public String getDetail() {
        return detail;
    }

    public String getActor() {
        return actor;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
