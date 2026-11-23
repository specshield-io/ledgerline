package com.specshield.ledgerline.application;

import java.time.Instant;

/**
 * One line of an order's timeline: an audit log entry or a provider event from the inbox.
 *
 * @param detailJson the audit detail or the provider payload, as a JSON object
 */
public record TimelineEntry(
        Instant at,
        Source source,
        String kind,
        String detailJson,
        String actor,
        String correlationId,
        String providerEventId,
        String outcome) {

    public enum Source {
        AUDIT,
        INBOX
    }
}
