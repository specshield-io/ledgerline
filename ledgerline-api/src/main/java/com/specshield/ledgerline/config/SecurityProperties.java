package com.specshield.ledgerline.config;

import java.util.List;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Users and CORS.
 *
 * @param users HTTP Basic users; the password is stored only as a BCrypt hash. A user with a blank hash is skipped
 *     (an optional account whose environment variable is unset).
 * @param corsAllowedOrigins exact origins allowed to call {@code /api/**} from a browser
 */
@ConfigurationProperties("ledgerline.security")
public record SecurityProperties(List<User> users, List<String> corsAllowedOrigins) {

    public enum Role {
        /** Everything, including replay and reset. */
        ADMIN,
        /** Read-only admin views. */
        VIEWER
    }

    private static final Pattern BCRYPT = Pattern.compile("\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}");

    public record User(String username, String passwordHash, Role role) {

        public User {
            if (username == null || username.isBlank()) {
                throw new IllegalArgumentException("ledgerline.security.users[].username is required");
            }
            if (role == null) {
                throw new IllegalArgumentException("ledgerline.security.users[].role is required for " + username);
            }
            if (passwordHash != null
                    && !passwordHash.isBlank()
                    && !BCRYPT.matcher(passwordHash).matches()) {
                // Never echo the value: it may be a plain password pasted by mistake.
                throw new IllegalArgumentException("password hash for " + username + " is not a BCrypt hash");
            }
        }

        public boolean enabled() {
            return passwordHash != null && !passwordHash.isBlank();
        }

        @Override
        public String toString() {
            return "User[" + username + ", " + role + ", hash=<redacted>]";
        }
    }

    public SecurityProperties {
        users = users == null ? List.of() : List.copyOf(users);
        corsAllowedOrigins = corsAllowedOrigins == null ? List.of() : List.copyOf(corsAllowedOrigins);
    }
}
