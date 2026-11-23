package com.specshield.ledgerline.config;

import com.specshield.ledgerline.web.ProblemResponses;
import com.specshield.ledgerline.web.ProblemTypes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Skeleton security posture: deny by default.
 *
 * <p>Reachable: the public orders API (no authentication: SpecShield Labs demo with synthetic data), the exposed
 * actuator endpoints, the OpenAPI document and the error page. Everything else is 403 problem+json.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ProblemResponses problems) throws Exception {
        http.authorizeHttpRequests(auth -> auth.requestMatchers(
                                "/api/v1/**",
                                // Authenticated by the HMAC signature, not by Spring Security.
                                "/webhooks/mockpay",
                                "/actuator/health",
                                "/actuator/health/**",
                                "/actuator/info",
                                "/actuator/prometheus",
                                "/v3/api-docs",
                                "/v3/api-docs/**",
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/error")
                        .permitAll()
                        .anyRequest()
                        .denyAll())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                // Denied requests get problem+json like every other error (AGENTS.md section 4).
                .exceptionHandling(ex -> ex.authenticationEntryPoint(
                                (request, response, e) -> forbidden(problems, request, response))
                        .accessDeniedHandler((request, response, e) -> forbidden(problems, request, response)));
        return http.build();
    }

    private static void forbidden(ProblemResponses problems, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        problems.write(
                request,
                response,
                ProblemResponses.problem(
                        HttpStatus.FORBIDDEN,
                        ProblemTypes.FORBIDDEN,
                        "Forbidden",
                        "This resource is not available.",
                        null));
    }

    /** No users yet. Declaring this bean stops Boot from generating and logging a default password. */
    @Bean
    UserDetailsService userDetailsService() {
        return new InMemoryUserDetailsManager();
    }
}
