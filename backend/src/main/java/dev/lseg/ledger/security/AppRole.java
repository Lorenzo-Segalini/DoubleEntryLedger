package dev.lseg.ledger.security;

/**
 * The three roles.
 *
 * <p>A flat enum rather than a permission matrix: three roles fit the domain
 * today, and a permission model that nobody needs is a model that drifts out of
 * sync with what is actually enforced.
 *
 * <p>{@code AUDITOR} is the one that makes the append-only argument concrete. It
 * sees every entry, every reversal and every audit event, and it is structurally
 * incapable of changing any of them.
 */
public enum AppRole {
    OPERATOR,
    AUDITOR,
    ADMIN;

    /** Spring Security expects the {@code ROLE_} prefix for {@code hasRole()}. */
    public String authority() {
        return "ROLE_" + name();
    }
}
