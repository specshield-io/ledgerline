package com.specshield.ledgerline.web;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the operations console (built from {@code ops-console/} into {@code static/}) for its client-side routes, so
 * a reload or shared link of {@code /outbox} works. {@code /} is Spring Boot's welcome page.
 */
@Hidden
@Controller
public class ConsoleController {

    @GetMapping("/outbox")
    String outbox() {
        return "forward:/index.html";
    }
}
