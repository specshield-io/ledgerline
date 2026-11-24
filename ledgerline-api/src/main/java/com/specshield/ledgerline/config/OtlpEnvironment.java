package com.specshield.ledgerline.config;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * Maps the standard OpenTelemetry variables onto Spring Boot's properties, so the usual setup works:
 *
 * <pre>
 * OTEL_EXPORTER_OTLP_ENDPOINT=https://otlp-gateway-prod-eu-west-2.grafana.net/otlp
 * OTEL_EXPORTER_OTLP_HEADERS=Authorization=Basic%20...
 * </pre>
 *
 * <p>With an endpoint, spans go to {@code <endpoint>/v1/traces} and metrics to {@code <endpoint>/v1/metrics} (OTLP over
 * HTTP). Without one nothing is exported: Boot creates no span exporter without an endpoint, and OTLP metric export
 * stays off ({@code management.otlp.metrics.export.enabled=false} in application.yml). Properties set explicitly (in
 * the environment or on the command line) still win: this source sits just below the system environment.
 */
public class OtlpEnvironment implements EnvironmentPostProcessor {

    static final String ENDPOINT = "OTEL_EXPORTER_OTLP_ENDPOINT";
    static final String HEADERS = "OTEL_EXPORTER_OTLP_HEADERS";
    static final String SOURCE_NAME = "otelExporterEnvironment";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> properties =
                properties(environment.getProperty(ENDPOINT), environment.getProperty(HEADERS));
        if (properties.isEmpty()) {
            return;
        }
        MapPropertySource source = new MapPropertySource(SOURCE_NAME, properties);
        if (environment.getPropertySources().contains(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)) {
            environment
                    .getPropertySources()
                    .addAfter(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, source);
        } else {
            environment.getPropertySources().addFirst(source);
        }
    }

    /** The Boot properties for an OTLP base endpoint and optional {@code key=value,key=value} headers. */
    static Map<String, Object> properties(String endpoint, String headers) {
        Map<String, Object> properties = new LinkedHashMap<>();
        if (endpoint == null || endpoint.isBlank()) {
            return properties;
        }
        String base = endpoint.strip().replaceAll("/+$", "");
        properties.put("management.opentelemetry.tracing.export.otlp.endpoint", base + "/v1/traces");
        properties.put("management.otlp.metrics.export.enabled", "true");
        properties.put("management.otlp.metrics.export.url", base + "/v1/metrics");
        if (headers != null && !headers.isBlank()) {
            for (String pair : headers.split(",")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = pair.substring(0, eq).strip();
                String value = URLDecoder.decode(pair.substring(eq + 1).strip(), StandardCharsets.UTF_8);
                properties.put("management.opentelemetry.tracing.export.otlp.headers[" + key + "]", value);
                properties.put("management.otlp.metrics.export.headers[" + key + "]", value);
            }
        }
        return properties;
    }
}
