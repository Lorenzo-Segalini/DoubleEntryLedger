package dev.lseg.ledger.security;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository {

    Optional<AppUser> findByEmail(String email);

    Optional<AppUser> findById(UUID id);

    void upsertDemoUser(UUID id, String email, String displayName, String passwordHash, AppRole role);
}
