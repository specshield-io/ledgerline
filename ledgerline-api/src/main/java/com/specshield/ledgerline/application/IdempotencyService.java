package com.specshield.ledgerline.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stripe-style idempotency for state-changing requests.
 *
 * <ol>
 *   <li>{@link #begin}: claim the key with an IN_PROGRESS row (its own transaction). If the key exists, decide:
 *       different request hash: {@link Decision.KeyReused}; still running: {@link Decision.InProgress}; completed:
 *       {@link Decision.Replay}; expired: delete it and claim it again.
 *   <li>The caller executes the request.
 *   <li>{@link #complete} stores 2xx-4xx responses; {@link #abandon} deletes the row after a 5xx or an exception so
 *       the client may retry.
 * </ol>
 *
 * <p>Known limitation: the claim, the business transaction and the completion are three separate transactions. If
 * the process dies after the business commit but before {@link #complete}, the key stays IN_PROGRESS (409) until it
 * expires, and a retry after expiry would execute again.
 */
@Service
public class IdempotencyService {

    public static final Duration TTL = Duration.ofHours(24);
    public static final Pattern KEY_FORMAT = Pattern.compile("[A-Za-z0-9_-]{8,128}");

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);
    private static final int MAX_CLAIM_ATTEMPTS = 3;

    private final IdempotencyKeys keys;
    private final Clock clock;
    private final JsonMapper canonicalMapper = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .build();

    public IdempotencyService(IdempotencyKeys keys, Clock clock) {
        this.keys = keys;
        this.clock = clock;
    }

    public sealed interface Decision {
        /** The key is claimed: execute the request, then call complete or abandon. */
        record Proceed() implements Decision {}

        record Replay(StoredResponse response) implements Decision {}

        record KeyReused() implements Decision {}

        record InProgress() implements Decision {}
    }

    public static boolean isValidKey(String key) {
        return key != null && KEY_FORMAT.matcher(key).matches();
    }

    public Decision begin(String key, String requestHash) {
        for (int attempt = 0; attempt < MAX_CLAIM_ATTEMPTS; attempt++) {
            Instant now = clock.instant();
            if (keys.insertIfAbsent(key, requestHash, now, now.plus(TTL))) {
                return new Decision.Proceed();
            }
            var existing = keys.find(key);
            if (existing.isEmpty()) {
                continue; // deleted between our insert and select (abandoned or purged): claim again
            }
            StoredIdempotencyKey row = existing.get();
            if (!row.expiresAt().isAfter(now)) {
                keys.deleteIfExpired(key, now);
                continue; // expired keys are treated as new
            }
            if (!row.requestHash().equals(requestHash)) {
                return new Decision.KeyReused();
            }
            return row.completed() ? new Decision.Replay(row.response()) : new Decision.InProgress();
        }
        // Repeated races with other requests for the same key: tell the client to retry.
        return new Decision.InProgress();
    }

    public void complete(String key, StoredResponse response) {
        if (!keys.complete(key, response)) {
            log.warn("idempotency key was no longer IN_PROGRESS when completing; response not stored");
        }
    }

    public void abandon(String key) {
        keys.deleteInProgress(key);
    }

    /** Deletes expired keys. Called hourly by {@code IdempotencyCleanupJob}. */
    public int purgeExpired() {
        int deleted = keys.deleteExpired(clock.instant());
        if (deleted > 0) {
            log.info("purged {} expired idempotency keys", deleted);
        }
        return deleted;
    }

    /**
     * SHA-256 (hex) of {@code METHOD\npath\ncanonical-json}. Canonical JSON sorts object keys recursively and drops
     * insignificant whitespace, so semantically identical bodies hash the same. A body that is not JSON is hashed as
     * raw bytes (the request will fail validation anyway).
     */
    public String requestHash(String method, String path, byte[] body) {
        byte[] canonicalBody = canonicalJson(body);
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            sha256.update((method.toUpperCase(Locale.ROOT) + "\n" + path + "\n").getBytes(StandardCharsets.UTF_8));
            sha256.update(canonicalBody);
            return HexFormat.of().formatHex(sha256.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }

    byte[] canonicalJson(byte[] body) {
        if (body == null || body.length == 0) {
            return new byte[0];
        }
        try {
            Object tree = canonicalMapper.readValue(body, Object.class);
            return canonicalMapper.writeValueAsBytes(tree);
        } catch (JacksonException notJson) {
            return body;
        }
    }
}
