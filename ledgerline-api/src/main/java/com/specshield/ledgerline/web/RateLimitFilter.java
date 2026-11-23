package com.specshield.ledgerline.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Applies {@link RateLimiter}: starting a chaos scenario uses the chaos plan (10/min); every other request to the API
 * and its documentation uses the public plan (240/min, room for the operations console polling). Webhooks (HMAC-authenticated provider traffic), the demo
 * merchant and actuator endpoints are not limited here.
 *
 * <p>Runs inside the Spring Security chain, after CORS and the security headers and before authentication, so a
 * flood with bogus credentials is cut off before any BCrypt work. Not a Spring bean, so Boot does not register it a
 * second time as a servlet filter.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final RateLimiter limiter;
    private final ClientIpResolver clients;
    private final ProblemResponses problems;

    public RateLimitFilter(RateLimiter limiter, ClientIpResolver clients, ProblemResponses problems) {
        this.limiter = limiter;
        this.clients = clients;
        this.problems = problems;
    }

    static RateLimiter.Plan planFor(HttpServletRequest request) {
        String path = request.getRequestURI();
        if ("POST".equals(request.getMethod()) && path.startsWith("/api/v1/chaos/scenarios/")) {
            return RateLimiter.Plan.CHAOS;
        }
        if (path.startsWith("/api/") || path.startsWith("/v3/api-docs") || path.startsWith("/swagger-ui")) {
            return RateLimiter.Plan.PUBLIC;
        }
        return null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        RateLimiter.Plan plan = planFor(request);
        if (plan == null) {
            chain.doFilter(request, response);
            return;
        }
        String client = clients.resolve(request);
        RateLimiter.Decision decision = limiter.tryConsume(plan, client);
        if (decision.allowed()) {
            chain.doFilter(request, response);
            return;
        }
        log.info("rate limit ({}) exceeded by {}", plan, client);
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(decision.retryAfterSeconds()));
        problems.write(
                request,
                response,
                ProblemResponses.problem(
                        HttpStatus.TOO_MANY_REQUESTS,
                        ProblemTypes.RATE_LIMITED,
                        "Too many requests",
                        plan == RateLimiter.Plan.CHAOS
                                ? "Chaos scenarios are limited per client. Retry after the Retry-After delay."
                                : "Requests are limited per client. Retry after the Retry-After delay.",
                        null));
    }
}
