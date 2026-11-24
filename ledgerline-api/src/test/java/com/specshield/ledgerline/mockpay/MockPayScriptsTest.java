package com.specshield.ledgerline.mockpay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;

class MockPayScriptsTest {

    @Test
    @DisplayName("event ids stay unique when many threads draw them at once (chaos runs are concurrent)")
    void uniqueIdsUnderConcurrency() throws Exception {
        MockPayProperties properties = new MockPayProperties(
                true, "http://localhost/webhooks/mockpay", List.of("s".repeat(40)), null, null, null, 0, null);
        MockPayScripts scripts = new MockPayScripts(
                properties,
                mock(MockPayLedger.class),
                new MockEnvironment(),
                Clock.systemUTC(),
                JsonMapper.builder().build());
        Set<String> ids = ConcurrentHashMap.newKeySet();
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            for (int t = 0; t < 8; t++) {
                pool.submit(() -> {
                    for (int i = 0; i < 5_000; i++) {
                        ids.add(scripts.newEventId());
                    }
                });
            }
        }
        scripts.shutdown();

        assertThat(ids).hasSize(40_000).allMatch(id -> id.matches("evt_[0-9a-f]{24}"));
    }
}
