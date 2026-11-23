package com.specshield.ledgerline.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Lets a UI check credentials and learn the user's roles, so it shows only the actions they may take. */
@RestController
@Tag(name = "Demo administration", description = "Reset the synthetic demo data")
@SecurityRequirement(name = OpenApiSecurity.BASIC)
public class AdminSessionController {

    @Schema(name = "AdminUser")
    public record MeResponse(
            String username,
            @Schema(example = "[\"ADMIN\"]") List<String> roles) {}

    @GetMapping("/api/v1/admin/me")
    @Operation(summary = "The authenticated user and roles (VIEWER or ADMIN)")
    MeResponse me(Authentication authentication) {
        return new MeResponse(
                authentication.getName(),
                authentication.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .filter(a -> a.startsWith("ROLE_"))
                        .map(a -> a.substring("ROLE_".length()))
                        .sorted()
                        .toList());
    }
}
