package com.specshield.ledgerline.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Puts the order id of {@code /api/v1/orders/{id}/...} requests into the MDC as {@code orderId}, so log lines (and the
 * JSON logs in prod) can be filtered by order. Only well-formed UUIDs are copied; anything else is left out.
 */
public class OrderIdMdcInterceptor implements HandlerInterceptor {

    public static final String ORDER_ID_MDC_KEY = "orderId";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) instanceof Map<?, ?> variables
                && request.getRequestURI().startsWith("/api/v1/orders/")
                && variables.get("id") instanceof String id) {
            try {
                MDC.put(ORDER_ID_MDC_KEY, UUID.fromString(id).toString());
            } catch (IllegalArgumentException notAUuid) {
                // the controller answers 400; nothing to correlate
            }
        }
        return true;
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        MDC.remove(ORDER_ID_MDC_KEY);
    }
}
