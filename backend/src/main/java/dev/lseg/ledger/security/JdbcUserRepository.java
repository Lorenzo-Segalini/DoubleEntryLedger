package dev.lseg.ledger.security;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcUserRepository implements UserRepository {

    private static final RowMapper<AppUser> MAPPER = (rs, rowNum) -> new AppUser(
            rs.getObject("id", UUID.class),
            rs.getString("email"),
            rs.getString("display_name"),
            rs.getString("password_hash"),
            AppRole.valueOf(rs.getString("role")),
            rs.getBoolean("enabled"));

    private static final String SELECT = "SELECT id, email, display_name, password_hash, role, enabled FROM app_user ";

    private final JdbcClient jdbc;

    JdbcUserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<AppUser> findByEmail(String email) {
        return jdbc.sql(SELECT + "WHERE email = :email")
                .param("email", email)
                .query(MAPPER)
                .optional();
    }

    @Override
    public Optional<AppUser> findById(UUID id) {
        return jdbc.sql(SELECT + "WHERE id = :id").param("id", id).query(MAPPER).optional();
    }

    @Override
    public void upsertDemoUser(UUID id, String email, String displayName, String passwordHash, AppRole role) {
        jdbc.sql(
                        """
                        INSERT INTO app_user (id, email, display_name, password_hash, role, enabled)
                        VALUES (:id, :email, :displayName, :passwordHash, CAST(:role AS app_role), TRUE)
                        ON CONFLICT (email) DO UPDATE
                            SET password_hash = EXCLUDED.password_hash,
                                display_name  = EXCLUDED.display_name,
                                role          = EXCLUDED.role,
                                enabled       = TRUE
                        """)
                .param("id", id)
                .param("email", email)
                .param("displayName", displayName)
                .param("passwordHash", passwordHash)
                .param("role", role.name())
                .update();
    }
}
