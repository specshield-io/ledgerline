package com.specshield.ledgerline.webhook;

/** The webhook signature is missing, malformed, outside the tolerance window or does not match. */
public final class InvalidSignatureException extends Exception {

    public enum Reason {
        MISSING_HEADER("missing_header"),
        MALFORMED_HEADER("malformed_header"),
        TIMESTAMP_OUT_OF_TOLERANCE("timestamp_out_of_tolerance"),
        NO_MATCHING_SIGNATURE("no_matching_signature");

        private final String tag;

        Reason(String tag) {
            this.tag = tag;
        }

        /** Stable, low-cardinality value for metrics and logs. */
        public String tag() {
            return tag;
        }
    }

    private final Reason reason;

    public InvalidSignatureException(Reason reason) {
        super(reason.tag(), null, false, false); // no stack trace: this is an expected outcome, not a bug
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
