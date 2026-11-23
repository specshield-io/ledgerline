package com.specshield.ledgerline.web;

import java.net.URI;

/**
 * Stable RFC 9457 problem type identifiers. They are URNs on purpose: they identify an error kind without pointing
 * at a web page that does not exist. Clients should branch on {@code type}, never on {@code title} or
 * {@code detail}.
 */
public final class ProblemTypes {

    public static final String PREFIX = "urn:ledgerline:problem:";

    /** OpenAPI component describing these problems (registered by config.OpenApiConfig). */
    public static final String SCHEMA_NAME = "Problem";

    public static final String SCHEMA_REF = "#/components/schemas/" + SCHEMA_NAME;

    public static final URI VALIDATION = of("validation-error");
    public static final URI MISSING_HEADER = of("missing-request-header");
    public static final URI INVALID_IDEMPOTENCY_KEY = of("invalid-idempotency-key");
    public static final URI MALFORMED_REQUEST = of("malformed-request");
    public static final URI INVALID_PARAMETER = of("invalid-parameter");
    public static final URI PAYLOAD_TOO_LARGE = of("payload-too-large");
    public static final URI FORBIDDEN = of("forbidden");
    public static final URI NOT_FOUND = of("not-found");
    public static final URI ORDER_NOT_FOUND = of("order-not-found");
    public static final URI METHOD_NOT_ALLOWED = of("method-not-allowed");
    public static final URI NOT_ACCEPTABLE = of("not-acceptable");
    public static final URI UNSUPPORTED_MEDIA_TYPE = of("unsupported-media-type");
    public static final URI ILLEGAL_TRANSITION = of("illegal-transition");
    public static final URI CONCURRENT_MODIFICATION = of("concurrent-modification");
    public static final URI IDEMPOTENCY_IN_PROGRESS = of("idempotency-request-in-progress");
    public static final URI IDEMPOTENCY_KEY_REUSE = of("idempotency-key-reuse");
    public static final URI INVALID_SIGNATURE = of("invalid-signature");
    public static final URI MALFORMED_EVENT = of("malformed-event");
    public static final URI OUTBOX_MESSAGE_NOT_FOUND = of("outbox-message-not-found");
    public static final URI OUTBOX_MESSAGE_NOT_REPLAYABLE = of("outbox-message-not-replayable");
    public static final URI INTERNAL_ERROR = of("internal-error");

    private ProblemTypes() {}

    static URI of(String slug) {
        return URI.create(PREFIX + slug);
    }

    /** The default type for a status when no specific kind applies. */
    static URI forStatus(int status) {
        return switch (status) {
            case 400 -> MALFORMED_REQUEST;
            case 401, 403 -> FORBIDDEN;
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 406 -> NOT_ACCEPTABLE;
            case 413 -> PAYLOAD_TOO_LARGE;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            default -> status >= 500 ? INTERNAL_ERROR : of("http-" + status);
        };
    }
}
