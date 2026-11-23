package com.specshield.ledgerline.web;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Replaces Boot's whitelabel/JSON error page, so errors raised outside Spring MVC (for example by servlet filters or
 * the container) are problem+json too. Never exposes exception messages or stack traces.
 */
@Hidden
@RestController
public class ProblemErrorController implements ErrorController {

    @RequestMapping(value = "/error", produces = MediaType.APPLICATION_PROBLEM_JSON_VALUE)
    ResponseEntity<ProblemDetail> error(HttpServletRequest request) {
        Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        HttpStatus status = HttpStatus.resolve(code instanceof Integer i ? i : 500);
        if (status == null || !status.isError()) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        Object uri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        String detail = status.is5xxServerError()
                ? "An unexpected error occurred. Quote the correlationId when reporting it."
                : status.getReasonPhrase();
        ProblemDetail problem = ProblemResponses.problem(
                status,
                ProblemTypes.forStatus(status.value()),
                status.getReasonPhrase(),
                detail,
                uri == null ? request.getRequestURI() : uri.toString());
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }
}
