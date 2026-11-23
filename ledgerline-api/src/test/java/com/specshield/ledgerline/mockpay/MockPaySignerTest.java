package com.specshield.ledgerline.mockpay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.specshield.ledgerline.webhook.TestSigner;
import com.specshield.ledgerline.webhook.WebhookSignatureVerifier;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MockPaySignerTest {

    private static final byte[] BODY = "{\"id\":\"evt_1\"}".getBytes(StandardCharsets.UTF_8);
    private static final long T = 1_768_471_200L;

    @Test
    @DisplayName("MockPay's signature matches an independent HMAC computation and passes the receiver")
    void matchesIndependentImplementation() throws Exception {
        String header = new MockPaySigner(List.of(TestSigner.CURRENT_SECRET)).sign(BODY, T);

        assertThat(header).isEqualTo(TestSigner.header(TestSigner.CURRENT_SECRET, T, BODY));
        new WebhookSignatureVerifier(List.of(TestSigner.CURRENT_SECRET), Duration.ofSeconds(300))
                .verify(header, BODY, Instant.ofEpochSecond(T));
    }

    @Test
    @DisplayName("with two signing secrets MockPay sends two v1 entries, and either receiver secret accepts it")
    void rotation() {
        String header = new MockPaySigner(List.of(TestSigner.PREVIOUS_SECRET, TestSigner.CURRENT_SECRET)).sign(BODY, T);

        assertThat(header.split(","))
                .hasSize(3)
                .filteredOn(e -> e.startsWith("v1="))
                .hasSize(2);
        for (String receiverSecret : List.of(TestSigner.CURRENT_SECRET, TestSigner.PREVIOUS_SECRET)) {
            assertThatCode(() -> new WebhookSignatureVerifier(List.of(receiverSecret), Duration.ofSeconds(300))
                            .verify(header, BODY, Instant.ofEpochSecond(T)))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("MockPay refuses to run without a signing secret")
    void requiresSecret() {
        assertThatIllegalArgumentException().isThrownBy(() -> new MockPaySigner(List.of()));
    }
}
