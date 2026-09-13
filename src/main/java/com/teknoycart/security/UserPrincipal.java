package com.teknoycart.security;

import java.util.UUID;

public class UserPrincipal {
    private final UUID id;
    private final String email;

    public UserPrincipal(UUID id, String email) {
        this.id = id;
        this.email = email;
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    @Override
    public String toString() {
        return id != null ? id.toString() : "";
    }
}
