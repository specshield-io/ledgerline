package com.specshield.ledgerline.web;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.restassured.response.Response;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class CorrelationIdIT extends ApiTestSupport {

    @Test
    @DisplayName("LL-C05 a valid X-Request-Id is echoed and appears in the request's log line")
    void validIdIsEchoedAndLogged(CapturedOutput output) {
        String id = "3f2c1b7e-8d4a-4c6b-9e1f-2a3b4c5d6e7f";

        Response response = given().header(CorrelationIdFilter.HEADER, id).get("/api/v1/orders");

        assertThat(response.header(CorrelationIdFilter.HEADER)).isEqualTo(id);
        assertThat(output.getOut().lines())
                .anySatisfy(line -> assertThat(line).contains("[" + id + "]").contains("GET /api/v1/orders -> 200"));
    }

    @Test
    @DisplayName("LL-C05 an invalid X-Request-Id is replaced by a generated UUID, which is logged")
    void invalidIdIsReplaced(CapturedOutput output) {
        Response response = given().header(CorrelationIdFilter.HEADER, "not-a-uuid; DROP TABLE")
                .get("/api/v1/orders");

        String generated = response.header(CorrelationIdFilter.HEADER);
        assertThat(UUID.fromString(generated).toString()).isEqualTo(generated);
        assertThat(output.getOut()).contains("[" + generated + "]").doesNotContain("DROP TABLE");
    }

    @Test
    @DisplayName("LL-C05 without X-Request-Id a UUID is generated; upper case input is normalised")
    void generatedAndNormalised() {
        String generated = given().get("/api/v1/orders").header(CorrelationIdFilter.HEADER);
        assertThat(UUID.fromString(generated)).isNotNull();

        String upper = "3F2C1B7E-8D4A-4C6B-9E1F-2A3B4C5D6E7F";
        assertThat(given().header(CorrelationIdFilter.HEADER, upper)
                        .get("/api/v1/orders")
                        .header(CorrelationIdFilter.HEADER))
                .isEqualTo(upper.toLowerCase(Locale.ROOT));
    }

    @Test
    @DisplayName("LL-C05 the correlation id is stored on audit rows written during the request")
    void auditRowsCarryCorrelationId() {
        String id = "11111111-2222-4333-8444-555555555555";

        given().header(CorrelationIdFilter.HEADER, id)
                .header(IdempotencyFilter.HEADER, "key-c05-0001")
                .contentType("application/json")
                .body(ORDER_JSON)
                .post("/api/v1/orders")
                .then()
                .statusCode(201);

        assertThat(jdbc.queryForObject("select correlation_id from audit_log", String.class))
                .isEqualTo(id);
    }
}
