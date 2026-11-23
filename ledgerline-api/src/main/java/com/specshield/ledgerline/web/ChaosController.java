package com.specshield.ledgerline.web;

import com.specshield.ledgerline.chaos.ChaosExceptions;
import com.specshield.ledgerline.chaos.ChaosService;
import com.specshield.ledgerline.chaos.Scenario;
import com.specshield.ledgerline.chaos.ScenarioRun;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/chaos")
@Tag(
        name = "Chaos",
        description =
                "Start a failure scenario on a fresh synthetic order and watch its timeline. Public, limited to 10"
                        + " starts per minute per client and 3 runs in progress at once.")
public class ChaosController {

    private static final String PROBLEM = "application/problem+json";

    private final ChaosService chaos;

    public ChaosController(ChaosService chaos) {
        this.chaos = chaos;
    }

    @Schema(name = "ChaosScenario")
    public record ScenarioResponse(
            @Schema(example = "duplicate-storm") String name, String description) {}

    @Schema(name = "ScenarioRunStarted")
    public record RunStarted(
            @Schema(description = "Poll GET /api/v1/chaos/runs/{scenarioRunId}")
            String scenarioRunId,

            @Schema(description = "The order this run created; poll GET /api/v1/orders/{orderId}/timeline")
            UUID orderId,

            @Schema(example = "duplicate-storm") String scenario) {}

    @Schema(name = "ScenarioRunStep")
    public record StepResponse(
            Instant at,
            String description,

            @Schema(nullable = true, description = "The webhook receiver's answer, for deliveries")
            Integer httpStatus,

            @Schema(
                    nullable = true,
                    description = "The receiver's problem type for an error answer",
                    example = "invalid-signature")
            String result) {}

    @Schema(name = "ScenarioRun")
    public record RunResponse(
            String scenarioRunId,
            String scenario,
            UUID orderId,
            ScenarioRun.Status status,
            Instant startedAt,
            @Schema(nullable = true) Instant finishedAt,

            @Schema(nullable = true, description = "Order status when the run finished")
            String orderStatus,

            @Schema(nullable = true) String error,
            List<StepResponse> steps) {

        static RunResponse from(ScenarioRun run) {
            return new RunResponse(
                    run.id(),
                    run.scenario().slug(),
                    run.orderId(),
                    run.status(),
                    run.startedAt(),
                    run.finishedAt(),
                    run.orderStatusAtEnd(),
                    run.error(),
                    run.steps().stream()
                            .map(s -> new StepResponse(s.at(), s.description(), s.httpStatus(), s.result()))
                            .toList());
        }
    }

    @GetMapping("/scenarios")
    @Operation(summary = "List the chaos scenarios")
    @ApiResponse(
            responseCode = "200",
            description = "Every scenario",
            content =
                    @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = ScenarioResponse.class))))
    List<ScenarioResponse> scenarios() {
        return Arrays.stream(Scenario.values())
                .map(s -> new ScenarioResponse(s.slug(), s.description()))
                .toList();
    }

    @PostMapping("/scenarios/{name}")
    @Operation(
            summary = "Start a chaos scenario",
            description = "Creates a new synthetic order and drives it through the scenario with real signed MockPay"
                    + " webhooks. Returns at once; the run continues in the background.")
    @ApiResponse(
            responseCode = "202",
            description = "Started (or the replayed response)",
            headers = @Header(name = "Location", description = "The run"),
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = RunStarted.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Unknown scenario",
            content = @Content(mediaType = PROBLEM, schema = @Schema(ref = ProblemTypes.SCHEMA_REF)))
    @ApiResponse(
            responseCode = "429",
            description = "Too many runs in progress, or too many starts from this client (Retry-After)",
            content = @Content(mediaType = PROBLEM, schema = @Schema(ref = ProblemTypes.SCHEMA_REF)))
    @ApiResponse(
            responseCode = "503",
            description = "The deployment lacks MockPay or the demo merchant this scenario needs",
            content = @Content(mediaType = PROBLEM, schema = @Schema(ref = ProblemTypes.SCHEMA_REF)))
    ResponseEntity<RunStarted> start(
            @Parameter(
                            in = ParameterIn.HEADER,
                            required = true,
                            description = "Unique key for this request (8-128 characters, [A-Za-z0-9_-]); repeating"
                                    + " it returns the same run")
                    @RequestHeader(IdempotencyFilter.HEADER)
                    @Size(min = 8, max = 128)
                    @Pattern(regexp = "[A-Za-z0-9_-]+")
                    String idempotencyKey,
            @Parameter(
                            description = "happy-path, duplicate-storm, tampered-webhook, replay-attack, out-of-order,"
                                    + " merchant-down, merchant-slow or refund-flow")
                    @PathVariable
                    String name) {
        Scenario scenario = Scenario.fromSlug(name).orElseThrow(() -> new ChaosExceptions.ScenarioNotFound(name));
        ScenarioRun run = chaos.start(scenario);
        return ResponseEntity.accepted()
                .location(URI.create("/api/v1/chaos/runs/" + run.id()))
                .body(new RunStarted(run.id(), run.orderId(), scenario.slug()));
    }

    @GetMapping("/runs/{id}")
    @Operation(summary = "A scenario run and its steps (kept for an hour)")
    @ApiResponse(
            responseCode = "200",
            description = "The run",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = RunResponse.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Unknown or expired run",
            content = @Content(mediaType = PROBLEM, schema = @Schema(ref = ProblemTypes.SCHEMA_REF)))
    RunResponse run(@PathVariable String id) {
        return chaos.find(id).map(RunResponse::from).orElseThrow(() -> new ChaosExceptions.RunNotFound(id));
    }
}
