package com.specshield.ledgerline.persistence;

import com.specshield.ledgerline.application.Outbox;
import com.specshield.ledgerline.application.OutboxMessage;
import com.specshield.ledgerline.application.OutboxStatus;
import com.specshield.ledgerline.application.OutboxStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The outbox table with plain SQL. {@link #append} joins the caller's transaction (JpaTransactionManager shares the
 * connection), so the row commits atomically with the state change. The relay methods run in their own short
 * transactions.
 */
@Repository
public class JdbcOutboxStore implements Outbox, OutboxStore {

    /** The database constraint outbox_last_error_bounded enforces the same limit. */
    public static final int MAX_ERROR_LENGTH = 500;

    private static final String COLUMNS = """
            id, aggregate_id, event_type, payload::text AS payload, status, attempts, next_attempt_at, last_error,
            created_at, delivered_at, claimed_at""";

    private final JdbcClient jdbc;

    JdbcOutboxStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void append(UUID aggregateId, String eventType, String payloadJson, Instant now) {
        jdbc.sql("""
                        INSERT INTO outbox (aggregate_id, event_type, payload, status, attempts, next_attempt_at, created_at)
                        VALUES (:aggregateId, :eventType, CAST(:payload AS jsonb), 'PENDING', 0, :now, :now)
                        """)
                .param("aggregateId", aggregateId)
                .param("eventType", eventType)
                .param("payload", payloadJson)
                .param("now", Timestamp.from(now))
                .update();
    }

    @Override
    @Transactional
    public List<OutboxMessage> claimDue(int limit, Instant now) {
        // SKIP LOCKED: concurrent dispatchers each take different rows instead of waiting for one another.
        return jdbc
                .sql("""
                        UPDATE outbox SET status = 'IN_FLIGHT', claimed_at = :now
                         WHERE id IN (SELECT id FROM outbox
                                       WHERE status = 'PENDING' AND next_attempt_at <= :now
                                       ORDER BY id
                                       LIMIT :limit
                                       FOR UPDATE SKIP LOCKED)
                        RETURNING\s""" + COLUMNS)
                .param("now", Timestamp.from(now))
                .param("limit", limit)
                .query(JdbcOutboxStore::map)
                .list()
                .stream()
                .sorted(Comparator.comparingLong(OutboxMessage::id))
                .toList();
    }

    @Override
    public boolean markDelivered(long id, Instant claimedAt, Instant deliveredAt) {
        return jdbc.sql("""
                        UPDATE outbox SET status = 'DELIVERED', attempts = attempts + 1, delivered_at = :at,
                                          last_error = NULL, claimed_at = NULL
                         WHERE id = :id AND status = 'IN_FLIGHT' AND claimed_at = :claimedAt
                        """)
                        .param("id", id)
                        .param("claimedAt", Timestamp.from(claimedAt))
                        .param("at", Timestamp.from(deliveredAt))
                        .update()
                == 1;
    }

    @Override
    public boolean markRetry(long id, Instant claimedAt, String error, Instant nextAttemptAt) {
        return jdbc.sql("""
                        UPDATE outbox SET status = 'PENDING', attempts = attempts + 1, last_error = :error,
                                          next_attempt_at = :next, claimed_at = NULL
                         WHERE id = :id AND status = 'IN_FLIGHT' AND claimed_at = :claimedAt
                        """)
                        .param("id", id)
                        .param("claimedAt", Timestamp.from(claimedAt))
                        .param("error", truncate(error))
                        .param("next", Timestamp.from(nextAttemptAt))
                        .update()
                == 1;
    }

    @Override
    public boolean markDead(long id, Instant claimedAt, String error) {
        return jdbc.sql("""
                        UPDATE outbox SET status = 'DEAD', attempts = attempts + 1, last_error = :error,
                                          claimed_at = NULL
                         WHERE id = :id AND status = 'IN_FLIGHT' AND claimed_at = :claimedAt
                        """)
                        .param("id", id)
                        .param("claimedAt", Timestamp.from(claimedAt))
                        .param("error", truncate(error))
                        .update()
                == 1;
    }

    @Override
    public int releaseStuck(Instant claimedBefore, String reason, Instant now) {
        return jdbc.sql("""
                        UPDATE outbox SET status = 'PENDING', claimed_at = NULL, next_attempt_at = :now,
                                          last_error = :reason
                         WHERE status = 'IN_FLIGHT' AND claimed_at < :before
                        """)
                .param("before", Timestamp.from(claimedBefore))
                .param("reason", truncate(reason))
                .param("now", Timestamp.from(now))
                .update();
    }

    @Override
    public long countByStatus(OutboxStatus status) {
        return jdbc.sql("SELECT count(*) FROM outbox WHERE status = :status")
                .param("status", status.name())
                .query(Long.class)
                .single();
    }

    @Override
    public Optional<Instant> oldestPendingCreatedAt() {
        return jdbc.sql("SELECT created_at FROM outbox WHERE status = 'PENDING' ORDER BY id LIMIT 1")
                .query((rs, n) -> rs.getTimestamp(1).toInstant())
                .optional();
    }

    @Override
    public OutboxPage page(Optional<OutboxStatus> status, int page, int size) {
        String where = status.isPresent() ? " WHERE status = :status" : "";
        String statusName = status.map(Enum::name).orElse(null);
        var count = jdbc.sql("SELECT count(*) FROM outbox" + where);
        var select = jdbc.sql(
                        "SELECT " + COLUMNS + " FROM outbox" + where + " ORDER BY id DESC LIMIT :size OFFSET :offset")
                .param("size", size)
                .param("offset", (long) page * size);
        if (statusName != null) {
            count = count.param("status", statusName);
            select = select.param("status", statusName);
        }
        long total = count.query(Long.class).single();
        List<OutboxMessage> items = select.query(JdbcOutboxStore::map).list();
        int totalPages = (int) ((total + size - 1) / size);
        return new OutboxPage(items, page, size, total, totalPages);
    }

    @Override
    public List<OutboxMessage> forAggregate(UUID aggregateId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM outbox WHERE aggregate_id = :id ORDER BY id")
                .param("id", aggregateId)
                .query(JdbcOutboxStore::map)
                .list();
    }

    @Override
    public Optional<OutboxMessage> findByIdForUpdate(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM outbox WHERE id = :id FOR UPDATE")
                .param("id", id)
                .query(JdbcOutboxStore::map)
                .optional();
    }

    @Override
    public void requeue(long id, Instant now) {
        jdbc.sql("""
                        UPDATE outbox SET status = 'PENDING', attempts = 0, next_attempt_at = :now, last_error = NULL,
                                          claimed_at = NULL
                         WHERE id = :id AND status = 'DEAD'
                        """).param("id", id).param("now", Timestamp.from(now)).update();
    }

    static String truncate(String error) {
        if (error == null || error.length() <= MAX_ERROR_LENGTH) {
            return error;
        }
        return error.substring(0, MAX_ERROR_LENGTH);
    }

    private static OutboxMessage map(ResultSet rs, int row) throws SQLException {
        return new OutboxMessage(
                rs.getLong("id"),
                rs.getObject("aggregate_id", UUID.class),
                rs.getString("event_type"),
                rs.getString("payload"),
                OutboxStatus.valueOf(rs.getString("status")),
                rs.getInt("attempts"),
                instant(rs, "next_attempt_at"),
                rs.getString("last_error"),
                instant(rs, "created_at"),
                instant(rs, "delivered_at"),
                instant(rs, "claimed_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
