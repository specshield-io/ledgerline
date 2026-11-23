package com.specshield.ledgerline.config;

import com.specshield.ledgerline.web.ProblemTypes;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    @Bean
    OpenAPI ledgerlineOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Ledgerline API")
                        .version("v1")
                        .description("""
                                Order and payment event processing. SpecShield Labs internal demonstration: \
                                synthetic data only, not a production payment system, not PCI DSS scoped.

                                State-changing POST requests require an `Idempotency-Key` header (8-128 characters, \
                                `[A-Za-z0-9_-]`). Repeating a request with the same key and body replays the stored \
                                response with `Idempotent-Replayed: true`. Every error is RFC 9457 \
                                `application/problem+json`.""")
                        .license(new License().name("Apache-2.0").url("https://www.apache.org/licenses/LICENSE-2.0")))
                // A fixed, relative server keeps the generated document identical across runs and environments.
                .servers(List.of(new Server().url("/").description("This deployment")));
    }

    /**
     * Declares the RFC 9457 problem schema as it is actually serialised (extension members such as correlationId at
     * the top level) and adds it as the {@code default} response of every operation.
     */
    @Bean
    OpenApiCustomizer problemDetailsCustomizer() {
        return openApi -> {
            Schema<Object> problem = new ObjectSchema()
                    .description("RFC 9457 problem details. Branch on `type`, never on `title` or `detail`.")
                    .addProperty(
                            "type", new StringSchema().format("uri").example("urn:ledgerline:problem:order-not-found"))
                    .addProperty("title", new StringSchema().example("Order not found"))
                    .addProperty("status", new IntegerSchema().format("int32").example(404))
                    .addProperty("detail", new StringSchema())
                    .addProperty("instance", new StringSchema().format("uri-reference"))
                    .addProperty(
                            "correlationId", new StringSchema().format("uuid").description("Echo of X-Request-Id"))
                    .required(List.of("type", "title", "status", "detail", "instance"));
            problem.setAdditionalProperties(true);
            openApi.getComponents().addSchemas(ProblemTypes.SCHEMA_NAME, problem);
            ApiResponse defaultError = problemResponse();
            openApi.getPaths()
                    .values()
                    .forEach(path -> path.readOperations()
                            .forEach(op -> op.getResponses().addApiResponse("default", defaultError)));
        };
    }

    private static ApiResponse problemResponse() {
        MediaType problemJson = new MediaType().schema(new Schema<>().$ref(ProblemTypes.SCHEMA_REF));
        return new ApiResponse()
                .description("Error")
                .content(new Content().addMediaType("application/problem+json", problemJson));
    }
}
