package com.specshield.ledgerline.web;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** LL-I23: body size and content type rules, for every endpoint that takes a body. */
class RequestLimitsIT extends ApiTestSupport {

    static final String BIG_JSON = "{\"merchantRef\":\"" + "x".repeat(70_000) + "\"}";

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/orders", "/webhooks/mockpay", "/demo/merchant-sink", "/api/v1/admin/reset"})
    @DisplayName("LL-I23 a body above 64 KB is 413, before authentication or idempotency")
    void tooLarge(String path) {
        given().contentType("application/json")
                .header(IdempotencyFilter.HEADER, "key-i23-big-0001")
                .body(BIG_JSON)
                .post(path)
                .then()
                .statusCode(413)
                .contentType("application/problem+json")
                .body("type", equalTo("urn:ledgerline:problem:payload-too-large"));
        assertThat(count("idempotency_keys")).isZero();
        assertThat(count("order_events_inbox")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/plain", "application/xml", "application/x-www-form-urlencoded", "not a type"})
    @DisplayName("LL-I23 a body that is not JSON is 415")
    void notJson(String contentType) throws Exception {
        try (HttpClient http = HttpClient.newHttpClient()) {
            for (String path : new String[] {"/api/v1/orders", "/webhooks/mockpay"}) {
                HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", contentType)
                        .header(IdempotencyFilter.HEADER, "key-i23-type-0001")
                        .POST(HttpRequest.BodyPublishers.ofString("hello"))
                        .build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).as(path).isEqualTo(415);
                assertThat(response.headers().firstValue("Content-Type"))
                        .hasValueSatisfying(v -> assertThat(v).startsWith("application/problem+json"));
                assertThat(response.body()).contains("urn:ledgerline:problem:unsupported-media-type");
            }
        }
    }

    @Test
    @DisplayName("LL-I23 a chunked body (no Content-Length) is held to the same 64 KB limit")
    void chunked() throws Exception {
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<String> big =
                    http.send(chunkedPost(BIG_JSON, "key-i23-chunk-big"), HttpResponse.BodyHandlers.ofString());
            assertThat(big.statusCode()).isEqualTo(413);

            HttpResponse<String> small =
                    http.send(chunkedPost(ORDER_JSON, "key-i23-chunk-small"), HttpResponse.BodyHandlers.ofString());
            assertThat(small.statusCode())
                    .as("a small chunked JSON body is read normally")
                    .isEqualTo(201);
        }
    }

    @Test
    @DisplayName("JSON variants (charset, +json) and bodiless POSTs are accepted")
    void accepted() {
        given().header("Content-Type", "application/json; charset=UTF-8")
                .header(IdempotencyFilter.HEADER, "key-i23-ok-0001")
                .body(ORDER_JSON)
                .post("/api/v1/orders")
                .then()
                .statusCode(201);
        String id = createOrder("key-i23-ok-0002");
        post("/api/v1/orders/" + id + "/cancel", "key-i23-ok-0003").then().statusCode(200);
    }

    private HttpRequest chunkedPost(String body, String key) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/orders"))
                .header("Content-Type", "application/json")
                .header(IdempotencyFilter.HEADER, key)
                // An input-stream publisher has no known length, so the client sends Transfer-Encoding: chunked.
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes)))
                .build();
    }
}
