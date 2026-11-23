package com.specshield.ledgerline.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientIpResolverTest {

    private final ClientIpResolver resolver = new ClientIpResolver(List.of("10.0.0.0/8", "fdaa::/16"));

    private static MockHttpServletRequest request(String peer, String... forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(peer);
        for (String header : forwardedFor) {
            request.addHeader("X-Forwarded-For", header);
        }
        return request;
    }

    @Test
    @DisplayName("X-Forwarded-For from an untrusted peer is ignored: the peer is the client")
    void untrustedPeer() {
        assertThat(resolver.resolve(request("203.0.113.5", "198.51.100.1"))).isEqualTo("203.0.113.5");
    }

    @Test
    @DisplayName("from a trusted proxy, the rightmost untrusted X-Forwarded-For entry is the client")
    void trustedProxy() {
        assertThat(resolver.resolve(request("10.1.2.3", "198.51.100.1"))).isEqualTo("198.51.100.1");
        // The client prepended a fake address; the proxy appended the real one.
        assertThat(resolver.resolve(request("10.1.2.3", "1.2.3.4, 198.51.100.1")))
                .isEqualTo("198.51.100.1");
        // Two trusted hops (edge proxy, then internal proxy).
        assertThat(resolver.resolve(request("fdaa:0:1::2", "198.51.100.1, 10.9.9.9")))
                .isEqualTo("198.51.100.1");
        // Several headers are read in order.
        assertThat(resolver.resolve(request("10.1.2.3", "1.2.3.4", "198.51.100.7")))
                .isEqualTo("198.51.100.7");
    }

    @Test
    @DisplayName(
            "from a trusted proxy without X-Forwarded-For, or with only trusted hops, no client address is invented")
    void trustedWithoutHeader() {
        assertThat(resolver.resolve(request("10.1.2.3"))).isEqualTo("10.1.2.3");
        assertThat(resolver.resolve(request("10.1.2.3", "10.4.4.4"))).isEqualTo("10.4.4.4");
    }

    @Test
    @DisplayName("a non-IP entry stops the walk; header content never causes a DNS lookup")
    void garbage() {
        assertThat(resolver.resolve(request("10.1.2.3", "198.51.100.1, evil.example")))
                .isEqualTo("10.1.2.3");
        assertThat(resolver.resolve(request("10.1.2.3", "198.51.100.1, 10.4.4.4, unknown")))
                .isEqualTo("10.1.2.3");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "1.2.3", "1.2.3.4.5", "256.1.1.1", "1.2.3.x", "example.com", "::g", "a b"})
    @DisplayName("only IP literals are accepted as addresses")
    void ipLiterals(String value) {
        assertThat(ClientIpResolver.isIpLiteral(value)).isFalse();
        assertThat(ClientIpResolver.isIpLiteral(null)).isFalse();
        assertThat(ClientIpResolver.isIpLiteral("192.0.2.1")).isTrue();
        assertThat(ClientIpResolver.isIpLiteral("2001:db8::1")).isTrue();
    }

    @Test
    @DisplayName("with no trusted proxies configured, X-Forwarded-For is always ignored")
    void noTrustedProxies() {
        ClientIpResolver none = new ClientIpResolver(List.of());
        assertThat(none.resolve(request("10.1.2.3", "198.51.100.1"))).isEqualTo("10.1.2.3");
    }
}
