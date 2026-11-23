package com.specshield.ledgerline.web;

import com.specshield.ledgerline.application.OrderNotFoundException;
import com.specshield.ledgerline.application.OrderService;
import com.specshield.ledgerline.application.OutboxMessageNotFoundException;
import com.specshield.ledgerline.application.OutboxMessageNotReplayableException;
import com.specshield.ledgerline.domain.IllegalTransitionException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Maps every exception reaching Spring MVC to RFC 9457 problem+json with a stable type. */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(OrderNotFoundException.class)
    ResponseEntity<ProblemDetail> orderNotFound(OrderNotFoundException e, HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, ProblemTypes.ORDER_NOT_FOUND, "Order not found", e.getMessage(), request);
    }

    @ExceptionHandler(OutboxMessageNotFoundException.class)
    ResponseEntity<ProblemDetail> outboxMessageNotFound(OutboxMessageNotFoundException e, HttpServletRequest request) {
        return respond(
                HttpStatus.NOT_FOUND,
                ProblemTypes.OUTBOX_MESSAGE_NOT_FOUND,
                "Outbox message not found",
                e.getMessage(),
                request);
    }

    @ExceptionHandler(OutboxMessageNotReplayableException.class)
    ResponseEntity<ProblemDetail> outboxMessageNotReplayable(
            OutboxMessageNotReplayableException e, HttpServletRequest request) {
        ProblemDetail problem = problem(
                HttpStatus.CONFLICT,
                ProblemTypes.OUTBOX_MESSAGE_NOT_REPLAYABLE,
                "Outbox message not replayable",
                e.getMessage(),
                request);
        problem.setProperty("currentStatus", e.status().name());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(IllegalTransitionException.class)
    ResponseEntity<ProblemDetail> illegalTransition(IllegalTransitionException e, HttpServletRequest request) {
        ProblemDetail problem = problem(
                HttpStatus.CONFLICT,
                ProblemTypes.ILLEGAL_TRANSITION,
                "Illegal state transition",
                "The order is " + e.from() + "; " + e.event() + " is not allowed in that state.",
                request);
        problem.setProperty("currentStatus", e.from().name());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> concurrentModification(HttpServletRequest request) {
        return respond(
                HttpStatus.CONFLICT,
                ProblemTypes.CONCURRENT_MODIFICATION,
                "Concurrent modification",
                "The order was changed by another request. Read it again and retry.",
                request);
    }

    /** Domain invariant violations that passed request validation (messages contain no internals). */
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> invalidArgument(IllegalArgumentException e, HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, ProblemTypes.VALIDATION, "Invalid request", e.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ProblemDetail> typeMismatch(MethodArgumentTypeMismatchException e, HttpServletRequest request) {
        return respond(
                HttpStatus.BAD_REQUEST,
                ProblemTypes.INVALID_PARAMETER,
                "Invalid parameter",
                "Parameter '" + e.getName() + "' has an invalid value.",
                request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e, HttpServletRequest request) {
        log.error("unhandled exception for {} {}", request.getMethod(), request.getRequestURI(), e);
        return respond(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ProblemTypes.INTERNAL_ERROR,
                "Internal error",
                "An unexpected error occurred. Quote the correlationId when reporting it.",
                request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = new ArrayList<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            errors.add(Map.of("field", fe.getField(), "message", String.valueOf(fe.getDefaultMessage())));
        }
        ProblemDetail problem = ex.getBody();
        problem.setType(ProblemTypes.VALIDATION);
        problem.setTitle("Invalid request");
        problem.setDetail("The request body has " + errors.size() + " invalid field(s).");
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = new ArrayList<>();
        ex.getParameterValidationResults().forEach(result -> {
            String name = result.getMethodParameter().getParameterName();
            result.getResolvableErrors()
                    .forEach(err -> errors.add(Map.of(
                            "parameter", String.valueOf(name), "message", String.valueOf(err.getDefaultMessage()))));
        });
        ProblemDetail problem = ex.getBody();
        problem.setType(ProblemTypes.VALIDATION);
        problem.setTitle("Invalid request");
        problem.setDetail("The request has " + errors.size() + " invalid parameter(s) or header(s).");
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    /**
     * Gives every framework-generated problem a stable type, a detail and the correlation id. Spring often passes a
     * null body here and builds the ProblemDetail inside super, so the enrichment runs on the returned response.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, @Nullable Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, status, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            if (problem.getType() == null
                    || "about:blank".equals(problem.getType().toString())) {
                problem.setType(typeFor(ex, status.value()));
            }
            if (problem.getTitle() == null) {
                problem.setTitle(HttpStatus.valueOf(status.value()).getReasonPhrase());
            }
            if (problem.getDetail() == null) {
                problem.setDetail(HttpStatus.valueOf(status.value()).getReasonPhrase());
            }
            String correlationId = MDC.get(OrderService.CORRELATION_ID_MDC_KEY);
            if (correlationId != null) {
                problem.setProperty(ProblemResponses.CORRELATION_ID_PROPERTY, correlationId);
            }
        }
        return response;
    }

    private static URI typeFor(Exception ex, int status) {
        return switch (ex) {
            case MissingRequestHeaderException ignored -> ProblemTypes.MISSING_HEADER;
            case NoResourceFoundException ignored -> ProblemTypes.NOT_FOUND;
            case HttpRequestMethodNotSupportedException ignored -> ProblemTypes.METHOD_NOT_ALLOWED;
            case HttpMediaTypeNotSupportedException ignored -> ProblemTypes.UNSUPPORTED_MEDIA_TYPE;
            case HttpMediaTypeNotAcceptableException ignored -> ProblemTypes.NOT_ACCEPTABLE;
            default -> ProblemTypes.forStatus(status);
        };
    }

    private static ProblemDetail problem(
            HttpStatus status, URI type, String title, String detail, HttpServletRequest request) {
        return ProblemResponses.problem(status, type, title, detail, request.getRequestURI());
    }

    private static ResponseEntity<ProblemDetail> respond(
            HttpStatus status, URI type, String title, String detail, HttpServletRequest request) {
        return ResponseEntity.status(status).body(problem(status, type, title, detail, request));
    }
}
