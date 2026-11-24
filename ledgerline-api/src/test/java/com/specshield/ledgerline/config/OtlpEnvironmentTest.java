package com.specshield.ledgerline.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

class OtlpEnvironmentTest {

    @Test
    @DisplayName("no OTEL_EXPORTER_OTLP_ENDPOINT: no export properties at all (no-op)")
    void noEndpoint() {
        assertThat(OtlpEnvironment.properties(null, "Authorization=x")).isEmpty();
        assertThat(OtlpEnvironment.properties("  ", null)).isEmpty();
    }

    @Test
    @DisplayName("an endpoint maps to the traces and metrics URLs; headers are URL-decoded for both")
    void endpointAndHeaders() {
        Map<String, Object> properties = OtlpEnvironment.properties(
                "https://otlp.example.net/otlp/", "Authorization=Basic%20abc%3D,X-Scope=demo, broken");

        assertThat(properties)
                .containsEntry(
                        "management.opentelemetry.tracing.export.otlp.endpoint",
                        "https://otlp.example.net/otlp/v1/traces")
                .containsEntry("management.otlp.metrics.export.enabled", "true")
                .containsEntry("management.otlp.metrics.export.url", "https://otlp.example.net/otlp/v1/metrics")
                .containsEntry("management.opentelemetry.tracing.export.otlp.headers[Authorization]", "Basic abc=")
                .containsEntry("management.otlp.metrics.export.headers[X-Scope]", "demo")
                .hasSize(7);
    }

    @Test
    @DisplayName("the mapping sits just below the system environment, so explicit properties still win")
    void precedence() {
        StandardEnvironment environment = new StandardEnvironment();
        environment
                .getPropertySources()
                .replace(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        new SystemEnvironmentPropertySource(
                                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                                Map.of(
                                        "OTEL_EXPORTER_OTLP_ENDPOINT", "http://collector:4318",
                                        "MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED", "false")));

        new OtlpEnvironment().postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("management.opentelemetry.tracing.export.otlp.endpoint"))
                .isEqualTo("http://collector:4318/v1/traces");
        assertThat(environment.getProperty("management.otlp.metrics.export.enabled"))
                .isEqualTo("false");

        StandardEnvironment without = new StandardEnvironment();
        int sources = without.getPropertySources().size();
        new OtlpEnvironment().postProcessEnvironment(without, new SpringApplication());
        assertThat(without.getPropertySources().size()).isEqualTo(sources);
    }
}
