package com.specshield.ledgerline.web;

import com.specshield.ledgerline.application.OrderService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds problem details and writes them where Spring MVC is not involved (servlet filters, Spring Security,
 * the error page), so every error response has the same shape.
 */
@Component
public class ProblemResponses {

    static final String CORRELATION_ID_PROPERTY = "correlationId";

    private final JsonMapper json;

    public ProblemResponses(JsonMapper json) {
        this.json = json;
    }

    /** A problem with every RFC 9457 member set, plus the correlation id. */
    public static ProblemDetail problem(HttpStatus status, URI type, String title, String detail, String instance) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(type);
        problem.setTitle(title);
        if (instance != null) {
            problem.setInstance(URI.create(instance));
        }
        String correlationId = MDC.get(OrderService.CORRELATION_ID_MDC_KEY);
        if (correlationId != null) {
            problem.setProperty(CORRELATION_ID_PROPERTY, correlationId);
        }
        return problem;
    }

    public void write(HttpServletRequest request, HttpServletResponse response, ProblemDetail problem)
            throws IOException {
        if (problem.getInstance() == null) {
            problem.setInstance(URI.create(request.getRequestURI()));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", problem.getType().toString());
        body.put("title", problem.getTitle());
        body.put("status", problem.getStatus());
        body.put("detail", problem.getDetail());
        body.put("instance", problem.getInstance().toString());
        if (problem.getProperties() != null) {
            body.putAll(problem.getProperties());
        }
        response.setStatus(problem.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getOutputStream().write(json.writeValueAsBytes(body));
    }
}
