package com.specshield.ledgerline.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Global request-body rules, before any controller or authentication work:
 *
 * <ul>
 *   <li>a body above {@value #MAX_BODY_BYTES} bytes is 413, whether it declares a Content-Length or is chunked (a
 *       chunked body is buffered up to the limit and handed on);
 *   <li>a body that is not JSON ({@code application/json} or {@code application/*+json}) is 415.
 * </ul>
 *
 * <p>Requests without a body (GET, the chaos POST) pass. Not a Spring bean; registered in the security chain.
 */
public class RequestLimitsFilter extends OncePerRequestFilter {

    public static final int MAX_BODY_BYTES = 64 * 1024;

    private final ProblemResponses problems;

    public RequestLimitsFilter(ProblemResponses problems) {
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long length = request.getContentLengthLong();
        String transferEncoding = request.getHeader("Transfer-Encoding");
        boolean chunked =
                transferEncoding != null && transferEncoding.toLowerCase().contains("chunked");
        if (length <= 0 && !chunked) {
            chain.doFilter(request, response);
            return;
        }
        if (length > MAX_BODY_BYTES) {
            tooLarge(request, response);
            return;
        }
        if (!isJson(request.getContentType())) {
            problems.write(
                    request,
                    response,
                    ProblemResponses.problem(
                            HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                            ProblemTypes.UNSUPPORTED_MEDIA_TYPE,
                            "Unsupported media type",
                            "Request bodies must be application/json.",
                            null));
            return;
        }
        if (length < 0) {
            byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
            if (body.length > MAX_BODY_BYTES) {
                tooLarge(request, response);
                return;
            }
            chain.doFilter(new IdempotencyFilter.CachedBodyRequest(request, body), response);
            return;
        }
        chain.doFilter(request, response);
    }

    static boolean isJson(String contentType) {
        if (contentType == null) {
            return false;
        }
        try {
            MediaType type = MediaType.parseMediaType(contentType);
            return "application".equals(type.getType())
                    && ("json".equals(type.getSubtype()) || type.getSubtype().endsWith("+json"));
        } catch (InvalidMediaTypeException e) {
            return false;
        }
    }

    private void tooLarge(HttpServletRequest request, HttpServletResponse response) throws IOException {
        problems.write(
                request,
                response,
                ProblemResponses.problem(
                        HttpStatus.CONTENT_TOO_LARGE,
                        ProblemTypes.PAYLOAD_TOO_LARGE,
                        "Payload too large",
                        "Request bodies are limited to " + MAX_BODY_BYTES + " bytes.",
                        null));
    }
}
