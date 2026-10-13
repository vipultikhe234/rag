package com.productrag.auth.dto;

import com.productrag.auth.AuthenticatedUser;
import com.productrag.auth.Role;
import java.util.UUID;

public record UserResponse(UUID id, String email, String displayName, Role role) {

    public static UserResponse from(AuthenticatedUser user) {
        return new UserResponse(user.id(), user.email(), user.displayName(), user.role());
    }
}
