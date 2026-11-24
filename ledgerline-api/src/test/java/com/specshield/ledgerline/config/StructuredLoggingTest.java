package com.specshield.ledgerline.config;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The prod profile logs ECS JSON ({@code logging.structured.format.console: ecs}). This checks, with the same encoder
 * Spring Boot uses, that a line carries the request correlation id, the trace and span ids and the order id.
 */
class StructuredLoggingTest {

    @Test
    @DisplayName("ECS JSON log lines include correlationId, traceId, spanId and orderId")
    void ecsLine() {
        LoggerContext context = new LoggerContext();
        context.putObject(
                Environment.class.getName(),
                new MockEnvironment().withProperty("spring.application.name", "ledgerline"));
        StructuredLogEncoder encoder = new StructuredLogEncoder();
        encoder.setContext(context);
        encoder.setFormat("ecs");
        encoder.start();
        LoggingEvent event = new LoggingEvent();
        event.setLoggerName("com.specshield.ledgerline.webhook.MockPayWebhookController");
        event.setLevel(Level.INFO);
        event.setMessage("mockpay webhook evt_1 (PAYMENT_SUCCEEDED) -> APPLIED");
        event.setTimeStamp(1_768_471_200_000L);
        event.setThreadName("http-nio-8080-exec-1");
        event.setMDCPropertyMap(Map.of(
                "correlationId", "3f2b9c1e-5d4a-4b8f-9a61-2c7d8e9f0a1b",
                "traceId", "4bf92f3577b34da6a3ce929d0e0e4736",
                "spanId", "00f067aa0ba902b7",
                "orderId", "0194a1b2-c3d4-7e5f-8a6b-7c8d9e0f1a2b"));

        String line = new String(encoder.encode(event), StandardCharsets.UTF_8);
        JsonNode json = JsonMapper.builder().build().readTree(line);

        assertThat(line).endsWith("\n").doesNotContain("\n{");
        assertThat(json.get("message").asString()).contains("APPLIED");
        assertThat(json.get("log").get("level").asString()).isEqualTo("INFO");
        assertThat(json.get("service").get("name").asString()).isEqualTo("ledgerline");
        assertThat(line)
                .contains("\"correlationId\":\"3f2b9c1e-5d4a-4b8f-9a61-2c7d8e9f0a1b\"")
                .contains("\"traceId\":\"4bf92f3577b34da6a3ce929d0e0e4736\"")
                .contains("\"spanId\":\"00f067aa0ba902b7\"")
                .contains("\"orderId\":\"0194a1b2-c3d4-7e5f-8a6b-7c8d9e0f1a2b\"");
    }
}
