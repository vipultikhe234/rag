package com.productrag.auth;

import java.util.List;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** Security principal placed in the SecurityContext for authenticated API requests. */
public record AuthenticatedUser(UUID id, String email, String displayName, Role role) {

    public static AuthenticatedUser from(AppUser user) {
        return new AuthenticatedUser(user.getId(), user.getEmail(), user.getDisplayName(), user.getRole());
    }

    public List<GrantedAuthority> authorities() {
        return List.of(new SimpleGrantedAuthority(role.authority()));
    }

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}
