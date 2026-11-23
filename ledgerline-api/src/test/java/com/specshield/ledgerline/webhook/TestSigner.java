package com.specshield.ledgerline.webhook;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Test-side signer, written independently of WebhookSigner and the verifier so a shared bug cannot hide. */
public final class TestSigner {

    public static final String CURRENT_SECRET = "test-only-mockpay-signing-secret-current-0001";
    public static final String PREVIOUS_SECRET = "test-only-mockpay-signing-secret-previous-0002";

    /** The relay's Ledgerline-Signature secret in the test profile (separate from MockPay's). */
    public static final String MERCHANT_SECRET = "test-only-merchant-signing-secret-current-0001";

    private TestSigner() {}

    public static String v1(String secret, long timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] prefix = (timestamp + ".").getBytes(StandardCharsets.UTF_8);
            byte[] signed = new byte[prefix.length + body.length];
            System.arraycopy(prefix, 0, signed, 0, prefix.length);
            System.arraycopy(body, 0, signed, prefix.length, body.length);
            return HexFormat.of().formatHex(mac.doFinal(signed));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String header(String secret, long timestamp, byte[] body) {
        return "t=" + timestamp + ",v1=" + v1(secret, timestamp, body);
    }
}
