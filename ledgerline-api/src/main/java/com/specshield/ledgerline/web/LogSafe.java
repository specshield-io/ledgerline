package com.specshield.ledgerline.web;

/**
 * Makes request-supplied values safe to put in a log line. Anything outside printable ASCII (including CR, LF, tab and
 * other control characters) becomes {@code _}, so a value cannot start a forged log line or hide behind terminal escape
 * sequences, and the result is capped at {@value #MAX_LENGTH} characters. Request URIs and methods are printable ASCII
 * on the wire (non-ASCII is percent-encoded), so legitimate values pass through unchanged.
 *
 * <p>The {@code prod} JSON log format escapes control characters on its own; this also covers the plain-text format
 * used locally and in tests.
 */
final class LogSafe {

    static final int MAX_LENGTH = 512;

    private LogSafe() {}

    static String of(String value) {
        if (value == null) {
            return "null";
        }
        String capped = value.length() > MAX_LENGTH ? value.substring(0, MAX_LENGTH) + "..." : value;
        return capped.replaceAll("[^\\x20-\\x7E]", "_");
    }
}
