package com.specshield.ledgerline.mockpay;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Builds {@code MockPay-Signature: t=TIMESTAMP,v1=HEX[,v1=HEX]} with HEX = HMAC-SHA256(secret, TIMESTAMP + "." +
 * body), one v1 per configured secret (rotation). Deliberately independent of the receiver's verifier.
 */
public final class MockPaySigner {

    private final List<byte[]> secrets;

    public MockPaySigner(List<String> secrets) {
        if (secrets.isEmpty()) {
            throw new IllegalArgumentException("MockPay needs at least one signing secret");
        }
        this.secrets =
                secrets.stream().map(s -> s.getBytes(StandardCharsets.UTF_8)).toList();
    }

    public String sign(byte[] body, long timestampSeconds) {
        StringBuilder header = new StringBuilder("t=").append(timestampSeconds);
        for (byte[] secret : secrets) {
            header.append(",v1=").append(hmacHex(secret, timestampSeconds, body));
        }
        return header.toString();
    }

    static String hmacHex(byte[] secret, long timestampSeconds, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update((timestampSeconds + ".").getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
