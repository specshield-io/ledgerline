package com.specshield.ledgerline.web;

import com.specshield.ledgerline.application.TimelineEntry;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import tools.jackson.databind.JsonNode;

@Schema(name = "TimelineEntry", description = "An audit log entry or a provider event, ordered by time")
public record TimelineEntryResponse(
        Instant at,

        @Schema(description = "AUDIT (our own commands) or INBOX (provider events)")
        TimelineEntry.Source source,

        @Schema(description = "Audit kind, e.g. ORDER_CREATED, or PROVIDER_EVENT", example = "ORDER_CREATED")
        String kind,

        @Schema(description = "Audit detail or provider payload", implementation = Object.class, types = "object")
        JsonNode detail,

        @Schema(description = "Who acted: 'api' or the provider name")
        String actor,

        @Schema(nullable = true) String correlationId,
        @Schema(nullable = true) String providerEventId,

        @Schema(nullable = true, description = "Inbox processing outcome")
        String outcome) {}
