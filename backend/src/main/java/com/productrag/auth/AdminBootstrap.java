package com.productrag.auth;

import com.productrag.config.AppProperties;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the initial admin from ADMIN_EMAIL / ADMIN_PASSWORD when that account does not exist yet.
 * An existing account is never modified, and no account is created when the variables are unset.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    static final int MIN_PASSWORD_LENGTH = 12;
    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties.Admin admin;

    public AdminBootstrap(AppUserRepository userRepository, PasswordEncoder passwordEncoder,
                          AppProperties properties) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.admin = properties.security().admin();
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String email = AppUser.normalizeEmail(admin.email());
        String password = admin.password();
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            log.warn("ADMIN_EMAIL / ADMIN_PASSWORD are not set: no initial admin account was bootstrapped.");
            return;
        }
        if (userRepository.findByEmail(email).isPresent()) {
            log.info("Initial admin account already exists; leaving it unchanged.");
            return;
        }
        if (password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalStateException("ADMIN_PASSWORD must be at least " + MIN_PASSWORD_LENGTH + " characters.");
        }
        if (password.toLowerCase(Locale.ROOT).startsWith("replace-with")) {
            throw new IllegalStateException("ADMIN_PASSWORD still contains the placeholder from .env.example.");
        }
        String displayName = admin.displayName() == null || admin.displayName().isBlank()
                ? "Administrator" : admin.displayName().trim();
        userRepository.save(new AppUser(UUID.randomUUID(), email, passwordEncoder.encode(password), displayName,
                Role.ADMIN));
        log.info("Created initial admin account.");
    }
}
