package com.productrag.auth;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    /** Emails are stored lower-cased; pass a value normalized with {@link AppUser#normalizeEmail}. */
    Optional<AppUser> findByEmail(String email);
}
