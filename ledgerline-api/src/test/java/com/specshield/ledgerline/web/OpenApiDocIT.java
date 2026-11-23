package com.specshield.ledgerline.web;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * LL-C01. Writes the live OpenAPI document to docs/openapi.json (path from the failsafe system property
 * {@code ledgerline.openapi.output}). CI fails if the committed file differs from the generated one, and runs
 * openapi-diff against main on pull requests.
 */
class OpenApiDocIT extends ApiTestSupport {

    @Test
    @DisplayName("LL-C01 generate docs/openapi.json describing every endpoint")
    void generateOpenApiDocument() throws IOException {
        String document =
                given().get("/v3/api-docs").then().statusCode(200).extract().asString();

        Map<String, Object> paths = given().get("/v3/api-docs").path("paths");
        assertThat(paths)
                .containsOnlyKeys(
                        "/api/v1/orders",
                        "/api/v1/orders/{id}",
                        "/api/v1/orders/{id}/pay",
                        "/api/v1/orders/{id}/cancel",
                        "/api/v1/orders/{id}/timeline",
                        "/webhooks/mockpay",
                        "/api/v1/admin/outbox",
                        "/api/v1/admin/outbox/{id}/replay",
                        "/api/v1/admin/reset",
                        "/api/v1/chaos/scenarios",
                        "/api/v1/chaos/scenarios/{name}",
                        "/api/v1/chaos/runs/{id}",
                        "/api/v1/meta");
        List<Map<String, Object>> createParams =
                given().get("/v3/api-docs").path("paths.'/api/v1/orders'.post.parameters");
        assertThat(createParams).anySatisfy(p -> {
            assertThat(p).containsEntry("name", "Idempotency-Key").containsEntry("in", "header");
            assertThat(p).containsEntry("required", true);
        });
        assertThat(document).contains("application/problem+json").doesNotContain("test-only");
        assertThat(document).contains("\"url\" : \"/\"");

        String output = System.getProperty("ledgerline.openapi.output");
        assertThat(output).as("failsafe sets ledgerline.openapi.output").isNotBlank();
        Path file = Path.of(output).toAbsolutePath().normalize();
        Files.createDirectories(file.getParent());
        Files.writeString(file, document.endsWith("\n") ? document : document + "\n", StandardCharsets.UTF_8);
    }
}
