package com.specshield.ledgerline.config;

import com.specshield.ledgerline.web.ClientIpResolver;
import com.specshield.ledgerline.web.ProblemResponses;
import com.specshield.ledgerline.web.ProblemTypes;
import com.specshield.ledgerline.web.RateLimitFilter;
import com.specshield.ledgerline.web.RateLimitProperties;
import com.specshield.ledgerline.web.RateLimiter;
import com.specshield.ledgerline.web.RequestLimitsFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * Deny by default.
 *
 * <ul>
 *   <li>Public (rate limited): the orders API, {@code /api/v1/meta}, {@code /api/v1/stats}, the chaos API, health and
 *       info, the OpenAPI document and Swagger UI, and the operations console's static files. The MockPay webhook and the demo merchant are public too; they authenticate requests
 *       by HMAC signature.
 *   <li>VIEWER or ADMIN (HTTP Basic): {@code GET /api/v1/admin/**} and {@code /actuator/prometheus}.
 *   <li>ADMIN: every other {@code /api/v1/admin/**} request (replay, reset).
 * </ul>
 *
 * <p>Anonymous requests to a protected path get 401 with {@code WWW-Authenticate}; authenticated requests without the
 * role, and every request to a path outside these lists, get 403. Both are problem+json. Users come from
 * configuration with BCrypt hashes only (in prod from environment variables).
 *
 * <p>CSRF protection stays on except for the API, webhook and demo-merchant paths. Those use no cookies or sessions,
 * and every state-changing API call needs a custom header ({@code Idempotency-Key}). A browser cannot send that
 * cross-origin without a CORS preflight, and the preflight succeeds only for the configured origins.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({SecurityProperties.class, RateLimitProperties.class})
public class SecurityConfig {

    static final String REALM = "ledgerline";

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http, ProblemResponses problems, RateLimiter rateLimiter, RateLimitProperties rateLimits)
            throws Exception {
        http.authorizeHttpRequests(auth -> auth.requestMatchers(HttpMethod.GET, "/api/v1/admin/**")
                        .hasAnyRole("VIEWER", "ADMIN")
                        .requestMatchers("/api/v1/admin/**")
                        .hasRole("ADMIN")
                        .requestMatchers("/actuator/prometheus")
                        .hasAnyRole("VIEWER", "ADMIN")
                        .requestMatchers(
                                "/api/v1/**",
                                "/webhooks/mockpay",
                                "/demo/merchant-sink",
                                "/actuator/health",
                                "/actuator/health/**",
                                "/actuator/info",
                                "/v3/api-docs",
                                "/v3/api-docs/**",
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/error",
                                // The operations console: its page, its routes and its hashed assets.
                                "/",
                                "/index.html",
                                "/outbox",
                                "/assets/**",
                                "/favicon.svg")
                        .permitAll()
                        .anyRequest()
                        .denyAll())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**", "/webhooks/**", "/demo/merchant-sink"))
                .cors(cors -> {})
                .httpBasic(basic -> basic.realmName(REALM)
                        .authenticationEntryPoint((request, response, e) -> denied(problems, request, response)))
                .formLogin(form -> form.disable())
                .headers(headers -> headers
                        // Sent on every response, not only over HTTPS: TLS ends at the proxy in front of us.
                        .httpStrictTransportSecurity(hsts -> hsts.requestMatcher(AnyRequestMatcher.INSTANCE)
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31_536_000))
                        .contentTypeOptions(options -> {})
                        .frameOptions(frame -> frame.deny())
                        .contentSecurityPolicy(csp -> csp.policyDirectives("frame-ancestors 'none'"))
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)))
                .exceptionHandling(
                        ex -> ex.authenticationEntryPoint((request, response, e) -> denied(problems, request, response))
                                .accessDeniedHandler((request, response, e) -> forbidden(problems, request, response)));
        if (rateLimits.enabled()) {
            http.addFilterAfter(
                    new RateLimitFilter(rateLimiter, new ClientIpResolver(rateLimits.trustedProxies()), problems),
                    CorsFilter.class);
            http.addFilterAfter(new RequestLimitsFilter(problems), RateLimitFilter.class);
        } else {
            http.addFilterAfter(new RequestLimitsFilter(problems), CorsFilter.class);
        }
        return http.build();
    }

    @Bean
    RateLimiter rateLimiter(RateLimitProperties properties, Clock clock) {
        return new RateLimiter(properties, clock);
    }

    /** Paths where credentials can help; anonymous requests there get 401 rather than 403. */
    static boolean authenticatable(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/api/v1/admin/") || path.equals("/api/v1/admin") || path.equals("/actuator/prometheus");
    }

    private static void denied(ProblemResponses problems, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        if (!authenticatable(request)) {
            forbidden(problems, request, response);
            return;
        }
        // A script (the console) marks its requests; a challenge would make the browser open its own login prompt.
        if (!"XMLHttpRequest".equals(request.getHeader("X-Requested-With"))) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"" + REALM + "\", charset=\"UTF-8\"");
        }
        problems.write(
                request,
                response,
                ProblemResponses.problem(
                        HttpStatus.UNAUTHORIZED,
                        ProblemTypes.UNAUTHORIZED,
                        "Authentication required",
                        "This resource needs HTTP Basic credentials with a suitable role.",
                        null));
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

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** Users from configuration; with none configured no one can authenticate (and Boot logs no generated password). */
    @Bean
    UserDetailsService userDetailsService(SecurityProperties properties) {
        List<org.springframework.security.core.userdetails.UserDetails> users = properties.users().stream()
                .filter(SecurityProperties.User::enabled)
                .map(u -> User.withUsername(u.username())
                        .password(u.passwordHash())
                        .roles(u.role().name())
                        .build())
                .toList();
        return new InMemoryUserDetailsManager(users);
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(SecurityProperties properties) {
        CorsConfiguration api = new CorsConfiguration();
        api.setAllowedOrigins(properties.corsAllowedOrigins());
        api.setAllowedMethods(List.of("GET", "POST"));
        api.setAllowedHeaders(
                List.of("Content-Type", "Idempotency-Key", "X-Request-Id", "Authorization", "X-Requested-With"));
        api.setExposedHeaders(List.of("X-Request-Id", "Idempotent-Replayed", "Retry-After", "Location"));
        api.setAllowCredentials(false);
        api.setMaxAge(3_600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", api);
        return source;
    }
}
