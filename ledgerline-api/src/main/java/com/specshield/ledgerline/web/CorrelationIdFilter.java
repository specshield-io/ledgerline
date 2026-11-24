package com.specshield.ledgerline.web;

import com.specshield.ledgerline.application.OrderService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Accepts {@code X-Request-Id} if it is a UUID, otherwise generates one; echoes it on the response, puts it in the
 * MDC as {@code correlationId} for every log line of the request, and logs one line per request.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String correlationId = parse(request.getHeader(HEADER));
        long start = System.nanoTime();
        MDC.put(OrderService.CORRELATION_ID_MDC_KEY, correlationId);
        response.setHeader(HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            log.info(
                    "{} {} -> {} ({} ms)",
                    LogSafe.of(request.getMethod()),
                    LogSafe.of(request.getRequestURI()),
                    response.getStatus(),
                    (System.nanoTime() - start) / 1_000_000);
            MDC.remove(OrderService.CORRELATION_ID_MDC_KEY);
        }
    }

    /** The header value if it is a UUID (normalised to lower case), otherwise a new random UUID. */
    static String parse(String header) {
        if (header != null && header.length() == 36) {
            try {
                return UUID.fromString(header).toString();
            } catch (IllegalArgumentException ignored) {
                // not a UUID: fall through
            }
        }
        return UUID.randomUUID().toString();
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }
}
