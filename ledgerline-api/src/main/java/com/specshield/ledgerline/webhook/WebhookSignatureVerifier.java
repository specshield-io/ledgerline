package com.specshield.ledgerline.webhook;

import com.specshield.ledgerline.webhook.InvalidSignatureException.Reason;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Verifies {@code MockPay-Signature: t=TIMESTAMP,v1=HEX[,v1=HEX...]}, the Stripe scheme: {@code HEX =
 * HMAC-SHA256(secret, TIMESTAMP + "." + rawBody)}.
 *
 * <ul>
 *   <li>The header is parsed defensively: bounded length and element count, exactly one numeric {@code t}, at least
 *       one {@code v1} of 64 hex digits; unknown schemes (e.g. {@code v0}) are ignored. Anything else is
 *       {@link Reason#MALFORMED_HEADER}; no unchecked exception escapes.
 *   <li>{@code |now - t|} above the tolerance is rejected, which bounds replay of a captured delivery.
 *   <li>Every provided {@code v1} is compared with the expected MAC for every configured secret (rotation) using
 *       {@link MessageDigest#isEqual}, which takes time independent of where the bytes differ.
 * </ul>
 *
 * <p>Thread safe. Never includes secrets or signatures in messages.
 */
public final class WebhookSignatureVerifier {

    static final int MAX_HEADER_LENGTH = 1024;
    static final int MAX_ELEMENTS = 10;
    private static final int MAC_HEX_LENGTH = 64;
    private static final int MAX_TIMESTAMP_DIGITS = 12;
    private static final String ALGORITHM = "HmacSHA256";

    private final List<SecretKeySpec> keys;
    private final Duration tolerance;

    public WebhookSignatureVerifier(List<String> secrets, Duration tolerance) {
        if (secrets == null || secrets.isEmpty()) {
            throw new IllegalArgumentException("at least one webhook secret is required");
        }
        this.keys = secrets.stream()
                .map(s -> new SecretKeySpec(s.getBytes(StandardCharsets.UTF_8), ALGORITHM))
                .toList();
        this.tolerance = tolerance;
    }

    /** Returns normally if the signature is valid. */
    public void verify(String header, byte[] body, Instant now) throws InvalidSignatureException {
        if (header == null || header.isBlank()) {
            throw new InvalidSignatureException(Reason.MISSING_HEADER);
        }
        ParsedHeader parsed = parse(header);
        long ageSeconds = now.getEpochSecond() - parsed.timestamp();
        if (Math.abs(ageSeconds) > tolerance.toSeconds()) {
            throw new InvalidSignatureException(Reason.TIMESTAMP_OUT_OF_TOLERANCE);
        }
        byte[] prefix = signedPrefix(parsed.timestamp());
        for (SecretKeySpec key : keys) {
            byte[] expected = hmac(key, prefix, body);
            for (byte[] candidate : parsed.signatures()) {
                if (MessageDigest.isEqual(expected, candidate)) {
                    return;
                }
            }
        }
        throw new InvalidSignatureException(Reason.NO_MATCHING_SIGNATURE);
    }

    record ParsedHeader(long timestamp, List<byte[]> signatures) {}

    static ParsedHeader parse(String header) throws InvalidSignatureException {
        if (header.length() > MAX_HEADER_LENGTH) {
            throw new InvalidSignatureException(Reason.MALFORMED_HEADER);
        }
        String[] elements = header.split(",", MAX_ELEMENTS + 1);
        if (elements.length > MAX_ELEMENTS) {
            throw new InvalidSignatureException(Reason.MALFORMED_HEADER);
        }
        Long timestamp = null;
        List<byte[]> signatures = new ArrayList<>();
        for (String element : elements) {
            int eq = element.indexOf('=');
            if (eq <= 0) {
                throw new InvalidSignatureException(Reason.MALFORMED_HEADER);
            }
            String name = element.substring(0, eq).trim();
            String value = element.substring(eq + 1).trim();
            switch (name) {
                case "t" -> {
                    if (timestamp != null || !isDigits(value, MAX_TIMESTAMP_DIGITS)) {
                        throw new InvalidSignatureException(Reason.MALFORMED_HEADER);
                    }
                    timestamp = Long.parseLong(value);
                }
                case "v1" -> {
                    if (value.length() != MAC_HEX_LENGTH || !isHex(value)) {
                        throw new InvalidSignatureException(Reason.MALFORMED_HEADER);
                    }
                    signatures.add(HexFormat.of().parseHex(value));
                }
                default -> {
                    // Unknown schemes are ignored, as Stripe does, so new schemes can be added without breaking us.
                }
            }
        }
        if (timestamp == null || signatures.isEmpty()) {
            throw new InvalidSignatureException(Reason.MALFORMED_HEADER);
        }
        return new ParsedHeader(timestamp, signatures);
    }

    /** The {@code TIMESTAMP + "."} that precedes the raw body in the signed payload. */
    static byte[] signedPrefix(long timestamp) {
        return (timestamp + ".").getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * HMAC of {@code prefix + body}, fed to the MAC in two parts: the body is never copied and no length is computed
     * from it, so a large or hostile body cannot overflow a buffer size.
     */
    private static byte[] hmac(SecretKeySpec key, byte[] prefix, byte[] body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            mac.update(prefix);
            return mac.doFinal(body);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private static boolean isDigits(String s, int maxLength) {
        if (s.isEmpty() || s.length() > maxLength) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    private static boolean isHex(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                return false;
            }
        }
        return true;
    }
}
