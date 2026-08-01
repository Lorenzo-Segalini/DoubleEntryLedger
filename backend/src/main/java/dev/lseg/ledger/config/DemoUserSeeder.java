package dev.lseg.ledger.config;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import dev.lseg.ledger.security.AppRole;
import dev.lseg.ledger.security.UserRepository;

/**
 * Creates the three demo logins at boot.
 *
 * <p>Not a migration. bcrypt hashes belong to a password, and a password written
 * into a versioned SQL file is a credential in git forever — the hash would be
 * identical in every deployment that ever ran that migration. Seeding here means
 * a private deployment changes one environment variable instead of one file.
 *
 * <p>Disabled by default, so an environment that has not opted in cannot acquire
 * known logins by accident.
 */
@Component
class DemoUserSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoUserSeeder.class);

    private static final UUID OPERATOR_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID AUDITOR_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a3");

    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final boolean enabled;
    private final String operatorPassword;
    private final String auditorPassword;
    private final String adminPassword;

    DemoUserSeeder(
            UserRepository users,
            PasswordEncoder passwords,
            @Value("${ledger.demo.seed-users:false}") boolean enabled,
            @Value("${ledger.demo.operator-password:}") String operatorPassword,
            @Value("${ledger.demo.auditor-password:}") String auditorPassword,
            @Value("${ledger.demo.admin-password:}") String adminPassword) {
        this.users = users;
        this.passwords = passwords;
        this.enabled = enabled;
        this.operatorPassword = operatorPassword;
        this.auditorPassword = auditorPassword;
        this.adminPassword = adminPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        if (operatorPassword.isBlank() || auditorPassword.isBlank() || adminPassword.isBlank()) {
            throw new IllegalStateException(
                    "ledger.demo.seed-users is on but a demo password is missing; refusing to create "
                            + "accounts with a default or empty password");
        }

        users.upsertDemoUser(
                OPERATOR_ID,
                "operator@demo.local",
                "Demo Operator",
                passwords.encode(operatorPassword),
                AppRole.OPERATOR);
        users.upsertDemoUser(
                AUDITOR_ID, "auditor@demo.local", "Demo Auditor", passwords.encode(auditorPassword), AppRole.AUDITOR);
        users.upsertDemoUser(
                ADMIN_ID, "admin@demo.local", "Demo Admin", passwords.encode(adminPassword), AppRole.ADMIN);

        log.info("seeded 3 demo users (operator, auditor, admin)");
    }
}
