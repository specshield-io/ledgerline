package com.specshield.ledgerline.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LogSafeTest {

    @Test
    @DisplayName("ordinary request URIs and methods are logged unchanged")
    void printableAsciiUnchanged() {
        assertThat(LogSafe.of("/api/v1/orders/demo-1001/timeline?x=%0A%20y"))
                .isEqualTo("/api/v1/orders/demo-1001/timeline?x=%0A%20y");
        assertThat(LogSafe.of("POST")).isEqualTo("POST");
    }

    @Test
    @DisplayName("CR, LF and other control characters cannot forge a log line")
    void controlCharactersReplaced() {
        assertThat(LogSafe.of("/a\r\n2026-01-01 INFO forged\tline\u001b[31m"))
                .isEqualTo("/a__2026-01-01 INFO forged_line_[31m")
                .doesNotContain("\n", "\r", "\t", "\u001b");
    }

    @Test
    @DisplayName("non-ASCII characters are replaced")
    void nonAsciiReplaced() {
        assertThat(LogSafe.of("/café x")).isEqualTo("/caf__x");
    }

    @Test
    @DisplayName("long values are capped and marked as truncated")
    void longValuesCapped() {
        String result = LogSafe.of("a".repeat(LogSafe.MAX_LENGTH + 100));

        assertThat(result).hasSize(LogSafe.MAX_LENGTH + 3).endsWith("a...");
        assertThat(LogSafe.of("b".repeat(LogSafe.MAX_LENGTH))).isEqualTo("b".repeat(LogSafe.MAX_LENGTH));
    }

    @Test
    @DisplayName("a null value is logged as null")
    void nullValue() {
        assertThat(LogSafe.of(null)).isEqualTo("null");
    }
}
