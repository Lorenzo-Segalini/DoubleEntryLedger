package dev.lseg.ledger.security;

import java.util.UUID;

/**
 * The authenticated caller, as the application thinks of them.
 *
 * <p>Kept separate from Spring's {@code Authentication} so that everything below
 * the security package deals in a user id and a role rather than in framework
 * types.
 */
public record LedgerUserDetails(UUID id, String email, AppRole role) {}
