package com.specshield.ledgerline.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IdempotencyServiceFlowTest {

    static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");
    final IdempotencyKeys keys = mock(IdempotencyKeys.class);
    final IdempotencyService service = new IdempotencyService(keys, Clock.fixed(NOW, ZoneOffset.UTC));
    final StoredResponse stored = new StoredResponse(201, "{}".getBytes(), "application/json", "/api/v1/orders/x");

    StoredIdempotencyKey row(String hash, boolean completed, Instant expiresAt) {
        return new StoredIdempotencyKey("key-1", hash, completed, completed ? stored : null, NOW, expiresAt);
    }

    @Test
    @DisplayName("a new key is claimed for 24 hours and the request proceeds")
    void claims() {
        when(keys.insertIfAbsent("key-1", "h1", NOW, NOW.plus(IdempotencyService.TTL)))
                .thenReturn(true);

        assertThat(service.begin("key-1", "h1")).isEqualTo(new IdempotencyService.Decision.Proceed());
    }

    @Test
    @DisplayName("an existing key: other request 422, still running 409, completed replays")
    void existing() {
        when(keys.insertIfAbsent(anyString(), anyString(), any(), any())).thenReturn(false);
        Instant later = NOW.plusSeconds(60);

        when(keys.find("key-1")).thenReturn(Optional.of(row("other", false, later)));
        assertThat(service.begin("key-1", "h1")).isEqualTo(new IdempotencyService.Decision.KeyReused());

        when(keys.find("key-1")).thenReturn(Optional.of(row("h1", false, later)));
        assertThat(service.begin("key-1", "h1")).isEqualTo(new IdempotencyService.Decision.InProgress());

        when(keys.find("key-1")).thenReturn(Optional.of(row("h1", true, later)));
        assertThat(service.begin("key-1", "h1")).isEqualTo(new IdempotencyService.Decision.Replay(stored));
    }

    @Test
    @DisplayName("an expired key (expiry exactly now) is deleted and claimed again")
    void expired() {
        when(keys.insertIfAbsent(anyString(), anyString(), any(), any())).thenReturn(false, true);
        when(keys.find("key-1")).thenReturn(Optional.of(row("other", true, NOW)));

        assertThat(service.begin("key-1", "h1")).isEqualTo(new IdempotencyService.Decision.Proceed());
        verify(keys).deleteIfExpired("key-1", NOW);
    }

    @Test
    @DisplayName("a row that vanished between insert and select is claimed again; endless races end in 409")
    void races() {
        when(keys.insertIfAbsent(anyString(), anyString(), any(), any())).thenReturn(false, true);
        when(keys.find("key-1")).thenReturn(Optional.empty());
        assertThat(service.begin("key-1", "h1")).isEqualTo(new IdempotencyService.Decision.Proceed());

        IdempotencyKeys racing = mock(IdempotencyKeys.class);
        when(racing.insertIfAbsent(anyString(), anyString(), any(), any())).thenReturn(false);
        when(racing.find("key-1")).thenReturn(Optional.empty());
        IdempotencyService raced = new IdempotencyService(racing, Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(raced.begin("key-1", "h1")).isEqualTo(new IdempotencyService.Decision.InProgress());
        verify(racing, times(3)).insertIfAbsent(eq("key-1"), eq("h1"), any(), any());
    }

    @Test
    @DisplayName("complete stores the response, abandon releases the key, purge reports the count")
    void lifecycle() {
        when(keys.complete("key-1", stored)).thenReturn(true, false);
        service.complete("key-1", stored);
        service.complete("key-1", stored); // no longer IN_PROGRESS: logged, not thrown
        verify(keys, times(2)).complete("key-1", stored);

        service.abandon("key-1");
        verify(keys).deleteInProgress("key-1");

        when(keys.deleteExpired(NOW)).thenReturn(3, 0);
        assertThat(service.purgeExpired()).isEqualTo(3);
        assertThat(service.purgeExpired()).isZero();
        verify(keys, never()).deleteIfExpired(anyString(), any());
    }

    @Test
    @DisplayName("key format: 8 to 128 characters of [A-Za-z0-9_-]")
    void keyFormat() {
        assertThat(IdempotencyService.isValidKey("abcdefgh")).isTrue();
        assertThat(IdempotencyService.isValidKey("a".repeat(128))).isTrue();
        assertThat(IdempotencyService.isValidKey("abcdefg")).isFalse();
        assertThat(IdempotencyService.isValidKey("a".repeat(129))).isFalse();
        assertThat(IdempotencyService.isValidKey("abcd efgh")).isFalse();
        assertThat(IdempotencyService.isValidKey(null)).isFalse();
    }
}
