package com.specshield.ledgerline.web;

import com.specshield.ledgerline.application.IdempotencyService;
import com.specshield.ledgerline.application.IdempotencyService.Decision;
import com.specshield.ledgerline.application.StoredResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Applies {@link IdempotencyService} to every {@code POST /api/v1/**} carrying an {@code Idempotency-Key}. Runs
 * after Spring Security (order -100) and around Spring MVC, so it sees the exact request and response bytes.
 *
 * <p>A POST without the header passes through; the controller then rejects it with 400 missing-request-header.
 */
@Component
@Order(0)
public class IdempotencyFilter extends OncePerRequestFilter {

    public static final String HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";
    static final int MAX_BODY_BYTES = 64 * 1024;

    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);

    private final IdempotencyService idempotency;
    private final ProblemResponses problems;

    public IdempotencyFilter(IdempotencyService idempotency, ProblemResponses problems) {
        this.idempotency = idempotency;
        this.problems = problems;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod())
                || !request.getRequestURI().startsWith("/api/v1/")
                || request.getHeader(HEADER) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getHeader(HEADER);
        if (!IdempotencyService.isValidKey(key)) {
            problems.write(
                    request,
                    response,
                    ProblemResponses.problem(
                            HttpStatus.BAD_REQUEST,
                            ProblemTypes.INVALID_IDEMPOTENCY_KEY,
                            "Invalid Idempotency-Key",
                            "Header 'Idempotency-Key' must be 8 to 128 characters from [A-Za-z0-9_-].",
                            null));
            return;
        }
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            problems.write(
                    request,
                    response,
                    ProblemResponses.problem(
                            HttpStatus.CONTENT_TOO_LARGE,
                            ProblemTypes.PAYLOAD_TOO_LARGE,
                            "Payload too large",
                            "Request bodies are limited to " + MAX_BODY_BYTES + " bytes.",
                            null));
            return;
        }
        String hash = idempotency.requestHash(request.getMethod(), request.getRequestURI(), body);

        switch (idempotency.begin(key, hash)) {
            case Decision.Proceed() -> execute(key, new CachedBodyRequest(request, body), response, chain);
            case Decision.Replay(StoredResponse stored) -> replay(response, stored);
            case Decision.KeyReused() ->
                problems.write(
                        request,
                        response,
                        ProblemResponses.problem(
                                HttpStatus.UNPROCESSABLE_CONTENT,
                                ProblemTypes.IDEMPOTENCY_KEY_REUSE,
                                "Idempotency key reused",
                                "This Idempotency-Key was already used for a different request (method, path or body).",
                                null));
            case Decision.InProgress() -> {
                response.setHeader(HttpHeaders.RETRY_AFTER, "1");
                problems.write(
                        request,
                        response,
                        ProblemResponses.problem(
                                HttpStatus.CONFLICT,
                                ProblemTypes.IDEMPOTENCY_IN_PROGRESS,
                                "Request in progress",
                                "A request with this Idempotency-Key is still being processed. Retry shortly.",
                                null));
            }
        }
    }

    private void execute(String key, HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(request, wrapper);
        } catch (IOException | ServletException | RuntimeException e) {
            idempotency.abandon(key);
            throw e;
        }
        int status = wrapper.getStatus();
        try {
            if (status >= 500 || status == 429) {
                // Server errors and "too many requests" are not stored: the client may retry with the same key.
                idempotency.abandon(key);
            } else {
                idempotency.complete(
                        key,
                        new StoredResponse(
                                status,
                                wrapper.getContentAsByteArray(),
                                wrapper.getContentType(),
                                wrapper.getHeader(HttpHeaders.LOCATION)));
            }
        } catch (RuntimeException e) {
            log.error("could not record idempotent response; releasing the key", e);
            idempotency.abandon(key);
        }
        wrapper.copyBodyToResponse();
    }

    private static void replay(HttpServletResponse response, StoredResponse stored) throws IOException {
        response.setStatus(stored.status());
        response.setHeader(REPLAYED_HEADER, "true");
        if (stored.contentType() != null) {
            response.setContentType(stored.contentType());
        }
        if (stored.location() != null) {
            response.setHeader(HttpHeaders.LOCATION, stored.location());
        }
        byte[] body = stored.body();
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }

    /** Lets the rest of the chain read a body this filter has already consumed. */
    static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return in.read(b, off, len);
                }

                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("async reads are not supported");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
