package com.tss.labeling.identity;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.springframework.http.HttpStatus.FORBIDDEN;

@Service
public class ActorService {

    private final JdbcTemplate jdbcTemplate;

    public ActorService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public ActorContext resolve(IdentityUser user) {
        ActorContext actor = jdbcTemplate.queryForObject("""
                INSERT INTO actor (id, kind, identity_user_id, display_name)
                VALUES (?, 'HUMAN', ?, ?)
                ON CONFLICT (identity_user_id) DO UPDATE
                SET display_name = EXCLUDED.display_name,
                    updated_at = CURRENT_TIMESTAMP
                RETURNING id, status
                """, (result, rowNumber) -> new ActorContext(
                result.getObject("id", UUID.class),
                result.getString("status"),
                false
        ), UUID.randomUUID(), user.identityUserId(), user.displayName());

        if (!"ENABLED".equals(actor.status())) {
            throw new ResponseStatusException(FORBIDDEN);
        }
        Boolean admin = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM actor_role_grant
                    WHERE actor_id = ? AND role_code = 'LABELING_ADMIN'
                )
                """, Boolean.class, actor.actorId());
        return new ActorContext(actor.actorId(), actor.status(), Boolean.TRUE.equals(admin));
    }

    public record ActorContext(UUID actorId, String status, boolean labelingAdmin) {
    }
}
