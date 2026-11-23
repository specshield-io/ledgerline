package com.specshield.ledgerline.web;

import io.swagger.v3.oas.annotations.Hidden;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Test-only endpoint that fails with a 500 on its first call, then succeeds. Never in production or OpenAPI. */
@Hidden
@Profile("test")
@RestController
class TestOnlyFailureController {

    static final String PATH = "/api/v1/test-only/flaky";
    private static final AtomicInteger CALLS = new AtomicInteger();

    static void reset() {
        CALLS.set(0);
    }

    static int calls() {
        return CALLS.get();
    }

    @PostMapping(PATH)
    Map<String, Object> flaky() {
        if (CALLS.incrementAndGet() == 1) {
            throw new IllegalStateException("synthetic failure for tests");
        }
        return Map.of("ok", true, "call", CALLS.get());
    }
}
