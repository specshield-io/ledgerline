package com.specshield.ledgerline.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IdempotencyServiceTest {

    private final IdempotencyService service = new IdempotencyService(null, Clock.systemUTC());

    private String hash(String method, String path, String body) {
        return service.requestHash(method, path, body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("request hash ignores key order and whitespace, recursively")
    void canonicalJson() {
        String a = hash("POST", "/api/v1/orders", "{\"b\":1,\"a\":{\"y\":[1,2],\"x\":true}}");
        String b = hash("POST", "/api/v1/orders", " {\n \"a\" : { \"x\":true , \"y\":[ 1 , 2 ] }, \"b\" : 1 } ");

        assertThat(a).isEqualTo(b).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("request hash changes with method, path, values and array order")
    void sensitivity() {
        String base = hash("POST", "/api/v1/orders", "{\"a\":[1,2]}");

        assertThat(hash("PUT", "/api/v1/orders", "{\"a\":[1,2]}")).isNotEqualTo(base);
        assertThat(hash("POST", "/api/v1/orders/x", "{\"a\":[1,2]}")).isNotEqualTo(base);
        assertThat(hash("POST", "/api/v1/orders", "{\"a\":[2,1]}")).isNotEqualTo(base);
        assertThat(hash("POST", "/api/v1/orders", "{\"a\":[1,3]}")).isNotEqualTo(base);
        assertThat(hash("post", "/api/v1/orders", "{\"a\":[1,2]}")).isEqualTo(base);
    }

    @Test
    @DisplayName("large numbers keep full precision in the canonical form")
    void numbers() {
        assertThat(hash("POST", "/p", "{\"n\":12345678901234567890123}"))
                .isNotEqualTo(hash("POST", "/p", "{\"n\":12345678901234567890124}"));
        assertThat(hash("POST", "/p", "{\"n\":0.1000000000000000055511151231257827}"))
                .isNotEqualTo(hash("POST", "/p", "{\"n\":0.1}"));
    }

    @Test
    @DisplayName("an empty body and a non-JSON body are hashed as raw bytes")
    void nonJson() {
        assertThat(hash("POST", "/p", "")).isNotEqualTo(hash("POST", "/p", "{}"));
        assertThat(hash("POST", "/p", "{not json")).isEqualTo(hash("POST", "/p", "{not json"));
        assertThat(hash("POST", "/p", "{not json")).isNotEqualTo(hash("POST", "/p", "{not  json"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"abcdefgh", "A-Z_0-9-key", "0194a1b2-c3d4-7e5f-8a6b-7c8d9e0f1a2b"})
    @DisplayName("valid Idempotency-Key formats are accepted")
    void validKeys(String key) {
        assertThat(IdempotencyService.isValidKey(key)).isTrue();
        assertThat(IdempotencyService.isValidKey("x".repeat(128))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "short", "with space", "slash/key1", "emoji😀key", "dot.key.123"})
    @DisplayName("invalid Idempotency-Key formats are rejected")
    void invalidKeys(String key) {
        assertThat(IdempotencyService.isValidKey(key)).isFalse();
        assertThat(IdempotencyService.isValidKey(null)).isFalse();
        assertThat(IdempotencyService.isValidKey("x".repeat(129))).isFalse();
    }
}
