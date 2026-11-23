package com.specshield.ledgerline.webhook;

import static com.specshield.ledgerline.webhook.TestSigner.CURRENT_SECRET;
import static com.specshield.ledgerline.webhook.TestSigner.PREVIOUS_SECRET;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.specshield.ledgerline.webhook.InvalidSignatureException.Reason;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WebhookSignatureVerifierTest {

    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");
    private static final long T = NOW.getEpochSecond();
    private static final byte[] BODY =
            "{\"id\":\"evt_1\",\"type\":\"PAYMENT_SUCCEEDED\"}".getBytes(StandardCharsets.UTF_8);

    private final WebhookSignatureVerifier verifier =
            new WebhookSignatureVerifier(List.of(CURRENT_SECRET), Duration.ofSeconds(300));

    @Test
    @DisplayName("LL-U08 a valid signature passes")
    void validSignature() {
        assertThatCode(() -> verifier.verify(TestSigner.header(CURRENT_SECRET, T, BODY), BODY, NOW))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("LL-U09 a tampered body fails")
    void tamperedBody() {
        String header = TestSigner.header(CURRENT_SECRET, T, BODY);
        byte[] tampered = Arrays.copyOf(BODY, BODY.length);
        tampered[tampered.length - 3] ^= 1;

        assertFails(header, tampered, NOW, Reason.NO_MATCHING_SIGNATURE);
        assertFails(
                header,
                (new String(BODY, StandardCharsets.UTF_8) + " ").getBytes(StandardCharsets.UTF_8),
                NOW,
                Reason.NO_MATCHING_SIGNATURE);
    }

    @Test
    @DisplayName("LL-U10 a signature made with the wrong secret fails")
    void wrongSecret() {
        assertFails(
                TestSigner.header("an-attacker-guessed-secret-of-enough-length", T, BODY),
                BODY,
                NOW,
                Reason.NO_MATCHING_SIGNATURE);
    }

    @Test
    @DisplayName("LL-U10 changing the signed timestamp without re-signing fails")
    void timestampIsSigned() {
        String v1 = TestSigner.v1(CURRENT_SECRET, T, BODY);

        assertFails("t=" + (T - 1) + ",v1=" + v1, BODY, NOW, Reason.NO_MATCHING_SIGNATURE);
    }

    @Test
    @DisplayName("LL-U11 timestamp 301 s in the past fails; 301 s in the future fails; 299 s passes")
    void toleranceWindow() {
        assertFails(TestSigner.header(CURRENT_SECRET, T - 301, BODY), BODY, NOW, Reason.TIMESTAMP_OUT_OF_TOLERANCE);
        assertFails(TestSigner.header(CURRENT_SECRET, T + 301, BODY), BODY, NOW, Reason.TIMESTAMP_OUT_OF_TOLERANCE);
        assertThatCode(() -> verifier.verify(TestSigner.header(CURRENT_SECRET, T - 299, BODY), BODY, NOW))
                .doesNotThrowAnyException();
        assertThatCode(() -> verifier.verify(TestSigner.header(CURRENT_SECRET, T + 299, BODY), BODY, NOW))
                .doesNotThrowAnyException();
        assertThatCode(() -> verifier.verify(TestSigner.header(CURRENT_SECRET, T - 300, BODY), BODY, NOW))
                .as("exactly 300 s is inside the window")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("LL-U12 a header with two v1 values passes when either matches (rotation)")
    void rotationHeader() {
        String current = TestSigner.v1(CURRENT_SECRET, T, BODY);
        String other = TestSigner.v1("some-other-secret-that-is-long-enough-0003", T, BODY);

        assertThatCode(() -> verifier.verify("t=" + T + ",v1=" + other + ",v1=" + current, BODY, NOW))
                .doesNotThrowAnyException();
        assertThatCode(() -> verifier.verify("t=" + T + ",v1=" + current + ",v1=" + other, BODY, NOW))
                .doesNotThrowAnyException();
        assertFails("t=" + T + ",v1=" + other + ",v1=" + other, BODY, NOW, Reason.NO_MATCHING_SIGNATURE);
    }

    @Test
    @DisplayName("LL-U12 a receiver configured with two secrets accepts signatures from either")
    void rotationSecrets() {
        WebhookSignatureVerifier rotating =
                new WebhookSignatureVerifier(List.of(CURRENT_SECRET, PREVIOUS_SECRET), Duration.ofSeconds(300));

        assertThatCode(() -> rotating.verify(TestSigner.header(PREVIOUS_SECRET, T, BODY), BODY, NOW))
                .doesNotThrowAnyException();
        assertThatCode(() -> rotating.verify(TestSigner.header(CURRENT_SECRET, T, BODY), BODY, NOW))
                .doesNotThrowAnyException();
    }

    /**
     * LL-U12, code review note: MACs must be compared in constant time. String.equals and Arrays.equals return at
     * the first differing byte, which lets an attacker learn a valid MAC byte by byte from response timings.
     * MessageDigest.isEqual compares every byte regardless. This test pins that choice in the bytecode so a
     * "harmless refactor" fails the build: the verifier must call isEqual, must not call Arrays.equals/compare on
     * byte arrays, and must never format a MAC as hex (which is the first step of any String comparison; the
     * verifier only parses hex). String.equals itself cannot be banned outright: switch on a String compiles to it.
     */
    @Test
    @DisplayName("LL-U12 MACs are compared with MessageDigest.isEqual, never equals()")
    void constantTimeCompare() {
        var verifierClass = new ClassFileImporter().importClasses(WebhookSignatureVerifier.class);

        classes()
                .should()
                .callMethod(MessageDigest.class, "isEqual", byte[].class, byte[].class)
                .check(verifierClass);
        noClasses()
                .should()
                .callMethod(Arrays.class, "equals", byte[].class, byte[].class)
                .orShould()
                .callMethod(Arrays.class, "compare", byte[].class, byte[].class)
                .orShould()
                .callMethod(HexFormat.class, "formatHex", byte[].class)
                .check(verifierClass);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(
            strings = {
                "",
                "   ",
                "t=abc",
                "v1=only",
                "t=1768471200",
                "t=,v1=",
                "=,=",
                ",,,",
                "t=1768471200,v1=zz",
                "t=1768471200,v1=abcd",
                "t=-5,v1=0000000000000000000000000000000000000000000000000000000000000000",
                "t=99999999999999999999,v1=0000000000000000000000000000000000000000000000000000000000000000",
                "t=1768471200,t=1768471200,v1=0000000000000000000000000000000000000000000000000000000000000000",
                "garbage without equals",
                "t=1768471200;v1=0000000000000000000000000000000000000000000000000000000000000000"
            })
    @DisplayName("LL-U13 malformed headers fail with InvalidSignatureException, never an unchecked exception")
    void malformedHeaders(String header) {
        assertThatThrownBy(() -> verifier.verify(header, BODY, NOW)).isInstanceOf(InvalidSignatureException.class);
    }

    @Test
    @DisplayName("LL-U13 a null header and a huge 10 KB header fail with InvalidSignatureException")
    void nullAndHugeHeaders() {
        assertFails(null, BODY, NOW, Reason.MISSING_HEADER);
        String huge = "t=" + T + ",v1=" + "a".repeat(10 * 1024);
        assertFails(huge, BODY, NOW, Reason.MALFORMED_HEADER);
        String manyElements = "t=" + T + ",v0=x".repeat(2_000) + ",v1=" + TestSigner.v1(CURRENT_SECRET, T, BODY);
        assertFails(manyElements, BODY, NOW, Reason.MALFORMED_HEADER);
    }

    @Test
    @DisplayName("unknown schemes such as v0 are ignored, as Stripe does")
    void unknownSchemesIgnored() {
        String header = "t=" + T + ",v0=deadbeef,v1=" + TestSigner.v1(CURRENT_SECRET, T, BODY);

        assertThatCode(() -> verifier.verify(header, BODY, NOW)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("upper-case hex and spaces around elements are accepted")
    void lenientFormatting() {
        String header = " t = " + T + " , v1 = "
                + TestSigner.v1(CURRENT_SECRET, T, BODY).toUpperCase();

        assertThatCode(() -> verifier.verify(header, BODY, NOW)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a verifier needs at least one secret")
    void requiresSecret() {
        assertThatThrownBy(() -> new WebhookSignatureVerifier(List.of(), Duration.ofSeconds(300)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WebhookSignatureVerifier(null, Duration.ofSeconds(300)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("failure messages never contain the secret or the signature")
    void noSecretsInMessages() {
        String header = TestSigner.header("an-attacker-guessed-secret-of-enough-length", T, BODY);
        assertThatThrownBy(() -> verifier.verify(header, BODY, NOW))
                .hasMessageNotContaining(CURRENT_SECRET)
                .hasMessageNotContaining(header.substring(header.indexOf("v1=") + 3));
    }

    private void assertFails(String header, byte[] body, Instant now, Reason reason) {
        assertThatThrownBy(() -> verifier.verify(header, body, now))
                .isInstanceOfSatisfying(
                        InvalidSignatureException.class,
                        e -> org.assertj.core.api.Assertions.assertThat(e.reason())
                                .isEqualTo(reason));
    }
}
