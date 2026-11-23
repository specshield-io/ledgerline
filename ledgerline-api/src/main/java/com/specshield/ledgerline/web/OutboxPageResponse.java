package com.specshield.ledgerline.web;

import com.specshield.ledgerline.application.OutboxStore.OutboxPage;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

@Schema(name = "OutboxPage", description = "Outbox messages, newest first")
public record OutboxPageResponse(
        List<OutboxMessageResponse> items, int page, int size, long totalElements, int totalPages) {

    static OutboxPageResponse from(OutboxPage p, JsonMapper json) {
        return new OutboxPageResponse(
                p.items().stream().map(m -> OutboxMessageResponse.from(m, json)).toList(),
                p.page(),
                p.size(),
                p.totalElements(),
                p.totalPages());
    }
}
