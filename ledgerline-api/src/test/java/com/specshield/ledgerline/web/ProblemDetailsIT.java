package com.specshield.ledgerline.web;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.specshield.ledgerline.application.IdempotencyService;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** LL-C02: every error, whichever layer produces it, is RFC 9457 problem+json with all five members. */
class ProblemDetailsIT extends ApiTestSupport {

    record Case(String name, int status, String type, String instance, Supplier<Response> call) {
        @Override
        public String toString() {
            return status + " " + name;
        }
    }

    static Stream<Case> errors() {
        UUID unknown = UUID.fromString("0194a1b2-c3d4-7e5f-8a6b-7c8d9e0f1a2b");
        return Stream.of(
                new Case(
                        "missing Idempotency-Key (MVC)",
                        400,
                        "missing-request-header",
                        "/api/v1/orders",
                        () -> postJson("/api/v1/orders", null, ORDER_JSON)),
                new Case(
                        "invalid Idempotency-Key (filter)",
                        400,
                        "invalid-idempotency-key",
                        "/api/v1/orders",
                        () -> postJson("/api/v1/orders", "bad", ORDER_JSON)),
                new Case(
                        "body validation",
                        400,
                        "validation-error",
                        "/api/v1/orders",
                        () -> postJson(
                                "/api/v1/orders",
                                "key-c02-0001",
                                "{\"merchantRef\":\"x\",\"amountMinor\":-5,\"currency\":\"USD\"}")),
                new Case(
                        "malformed JSON",
                        400,
                        "malformed-request",
                        "/api/v1/orders",
                        () -> postJson("/api/v1/orders", "key-c02-0002", "{not json")),
                new Case(
                        "bad UUID in path",
                        400,
                        "invalid-parameter",
                        "/api/v1/orders/not-a-uuid",
                        () -> given().get("/api/v1/orders/not-a-uuid")),
                new Case(
                        "page size too big",
                        400,
                        "validation-error",
                        "/api/v1/orders",
                        () -> given().queryParam("size", 1000).get("/api/v1/orders")),
                new Case("denied by security", 403, "forbidden", "/actuator/env", () -> given().get("/actuator/env")),
                new Case(
                        "unknown order",
                        404,
                        "order-not-found",
                        "/api/v1/orders/" + unknown,
                        () -> given().get("/api/v1/orders/" + unknown)),
                new Case(
                        "unknown API path",
                        404,
                        "not-found",
                        "/api/v1/nothing-here",
                        () -> given().get("/api/v1/nothing-here")),
                new Case(
                        "wrong method",
                        405,
                        "method-not-allowed",
                        "/api/v1/orders",
                        () -> given().delete("/api/v1/orders")),
                new Case(
                        "wrong media type",
                        415,
                        "unsupported-media-type",
                        "/api/v1/orders",
                        () -> given().contentType(ContentType.TEXT)
                                .header(IdempotencyFilter.HEADER, "key-c02-0003")
                                .body("hello")
                                .post("/api/v1/orders")),
                new Case(
                        "body above 64 KiB (filter)",
                        413,
                        "payload-too-large",
                        "/api/v1/orders",
                        () -> postJson(
                                "/api/v1/orders", "key-c02-0006", "{\"merchantRef\":\"" + "x".repeat(70_000) + "\"}")),
                new Case(
                        "error page reached directly (container errors)",
                        500,
                        "internal-error",
                        "/error",
                        () -> given().get("/error")),
                new Case(
                        "unexpected server error",
                        500,
                        "internal-error",
                        TestOnlyFailureController.PATH,
                        () -> post(TestOnlyFailureController.PATH, null)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("errors")
    @DisplayName("LL-C02 every 4xx/5xx response is problem+json with type, title, status, detail, instance")
    void problemShape(Case c) {
        Response response = c.call().get();

        assertThat(response.statusCode()).isEqualTo(c.status());
        assertThat(response.contentType()).startsWith("application/problem+json");
        assertThat(response.<String>path("type")).isEqualTo("urn:ledgerline:problem:" + c.type());
        assertThat(response.<String>path("title")).isNotBlank();
        assertThat(response.<Integer>path("status")).isEqualTo(c.status());
        assertThat(response.<String>path("detail")).isNotBlank();
        assertThat(response.<String>path("instance")).isEqualTo(c.instance());
        assertThat(response.<String>path("correlationId")).isEqualTo(response.header(CorrelationIdFilter.HEADER));
        assertThat(response.asString()).doesNotContainIgnoringCase("exception").doesNotContain("at com.");
    }

    @Test
    @DisplayName("LL-C02 the 409 in-progress and 422 key-reuse problems have the same shape")
    void idempotencyProblems() {
        createOrder("key-c02-0004");
        Response reuse = postJson(
                "/api/v1/orders", "key-c02-0004", "{\"merchantRef\":\"other\",\"amountMinor\":1,\"currency\":\"USD\"}");
        assertProblem(reuse, 422, "idempotency-key-reuse");

        jdbc.update(
                "insert into idempotency_keys (key, request_hash, state, created_at, expires_at)"
                        + " values ('key-c02-0005', ?, 'IN_PROGRESS', now(), now() + interval '1 hour')",
                hashOf("/api/v1/orders", ORDER_JSON));
        Response inProgress = postJson("/api/v1/orders", "key-c02-0005", ORDER_JSON);
        assertProblem(inProgress, 409, "idempotency-request-in-progress");
        assertThat(inProgress.header("Retry-After")).isEqualTo("1");
    }

    private String hashOf(String path, String body) {
        return new IdempotencyService(null, clock).requestHash("POST", path, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertProblem(Response response, int status, String type) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.contentType()).startsWith("application/problem+json");
        for (String member : List.of("type", "title", "detail", "instance")) {
            assertThat(response.<String>path(member)).as(member).isNotBlank();
        }
        assertThat(response.<String>path("type")).endsWith(type);
        assertThat(response.<Integer>path("status")).isEqualTo(status);
    }
}
