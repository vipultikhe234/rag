package com.productrag.auth;

import com.productrag.auth.dto.LoginRequest;
import com.productrag.auth.dto.LoginResponse;
import com.productrag.auth.dto.UserResponse;
import com.productrag.common.exception.ApiException;
import com.productrag.common.ratelimit.RateLimiter;
import com.productrag.config.AppProperties;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RateLimiter rateLimiter;
    private final int loginPerMinute;
    /** Used to spend comparable BCrypt time when the email is unknown (prevents user enumeration by timing). */
    private final String dummyHash;

    public AuthService(AppUserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService,
                       RateLimiter rateLimiter, AppProperties properties) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.rateLimiter = rateLimiter;
        this.loginPerMinute = properties.rateLimit().loginPerMinute();
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    public LoginResponse login(LoginRequest request, String clientAddress) {
        String email = AppUser.normalizeEmail(request.email());
        rateLimiter.acquire("login-ip:" + clientAddress, loginPerMinute, WINDOW);
        rateLimiter.acquire("login-email:" + email, loginPerMinute, WINDOW);

        Optional<AppUser> candidate = userRepository.findByEmail(email);
        if (candidate.isEmpty()) {
            passwordEncoder.matches(request.password(), dummyHash);
            throw invalidCredentials();
        }
        AppUser user = candidate.get();
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash()) || !user.isEnabled()) {
            log.info("Failed login attempt for an existing account");
            throw invalidCredentials();
        }
        JwtService.IssuedToken token = jwtService.issue(user);
        return new LoginResponse(token.token(), "Bearer", token.expiresAt(),
                UserResponse.from(AuthenticatedUser.from(user)));
    }

    private static ApiException invalidCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid email or password");
    }
}
