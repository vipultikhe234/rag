package com.productrag.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.productrag.config.AppProperties;
import com.productrag.support.TestProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private static final AppProperties.Jwt JWT =
            new AppProperties.Jwt(TestProperties.JWT_SECRET, Duration.ofHours(1), "product-rag-admin");

    private final AppUser user = new AppUser(UUID.randomUUID(), "Admin@Example.com", "hash", "Admin", Role.ADMIN);

    @Test
    void issuedTokenValidatesToTheUserId() {
        JwtService service = new JwtService(JWT, Clock.systemUTC());

        JwtService.IssuedToken token = service.issue(user);

        assertThat(service.validate(token.token())).contains(user.getId());
        assertThat(user.getEmail()).isEqualTo("admin@example.com");
    }

    @Test
    void tamperedTokenIsRejected() {
        JwtService service = new JwtService(JWT, Clock.systemUTC());
        String token = service.issue(user).token();
        String tampered = token.substring(0, token.length() - 3) + (token.endsWith("AAA") ? "BBB" : "AAA");

        assertThat(service.validate(tampered)).isEmpty();
        assertThat(service.validate("not-a-jwt")).isEmpty();
        assertThat(service.validate("")).isEmpty();
    }

    @Test
    void tokenSignedWithAnotherSecretIsRejected() {
        JwtService other = new JwtService(new AppProperties.Jwt("another-secret-that-is-also-long-enough-123",
                Duration.ofHours(1), "product-rag-admin"), Clock.systemUTC());
        String foreign = other.issue(user).token();

        assertThat(new JwtService(JWT, Clock.systemUTC()).validate(foreign)).isEmpty();
    }

    @Test
    void expiredTokenIsRejected() {
        Instant issuedAt = Instant.parse("2026-01-01T00:00:00Z");
        JwtService issuer = new JwtService(JWT, Clock.fixed(issuedAt, ZoneOffset.UTC));
        String token = issuer.issue(user).token();

        JwtService later = new JwtService(JWT, Clock.fixed(issuedAt.plus(Duration.ofHours(2)), ZoneOffset.UTC));

        assertThat(later.validate(token)).isEmpty();
    }

    @Test
    void weakOrMissingSecretsAreRefusedAtStartup() {
        assertThatThrownBy(() -> new JwtService(new AppProperties.Jwt("", Duration.ofHours(1), "x"), Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new JwtService(new AppProperties.Jwt("short", Duration.ofHours(1), "x"),
                Clock.systemUTC())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new JwtService(new AppProperties.Jwt(
                "replace-with-a-random-secret-of-at-least-32-bytes", Duration.ofHours(1), "x"), Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class);
    }
}
