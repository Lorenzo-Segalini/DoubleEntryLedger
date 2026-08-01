package dev.lseg.ledger.security;

import java.util.UUID;

public record AppUser(UUID id, String email, String displayName, String passwordHash, AppRole role, boolean enabled) {}
