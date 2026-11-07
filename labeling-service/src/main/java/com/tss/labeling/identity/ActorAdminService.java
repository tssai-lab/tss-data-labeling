package com.tss.labeling.identity;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.UUID;
import static org.springframework.http.HttpStatus.*;

@Service
public class ActorAdminService {
    private final JdbcTemplate jdbc;

    public ActorAdminService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private void requireAdmin(UUID caller) {
        Boolean allowed = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM actor a JOIN actor_role_grant r ON r.actor_id = a.id
                WHERE a.id = ? AND a.kind = 'HUMAN' AND a.status = 'ENABLED'
                  AND r.role_code = 'LABELING_ADMIN')
                """, Boolean.class, caller);
        if (!Boolean.TRUE.equals(allowed)) throw new ResponseStatusException(FORBIDDEN);
    }

    @Transactional(readOnly = true)
    public List<UserView> list(UUID caller, int limit, int offset) {
        requireAdmin(caller);
        if (limit < 1 || limit > 100 || offset < 0) throw new ResponseStatusException(BAD_REQUEST);
        return jdbc.query("""
                SELECT a.id, a.identity_user_id, a.display_name, a.status,
                  EXISTS (SELECT 1 FROM actor_role_grant r WHERE r.actor_id = a.id
                    AND r.role_code = 'LABELING_ADMIN') AS admin
                FROM actor a WHERE a.kind = 'HUMAN' ORDER BY a.created_at, a.id LIMIT ? OFFSET ?
                """, (rs, row) -> new UserView(rs.getObject("id", UUID.class),
                rs.getString("identity_user_id"), rs.getString("display_name"),
                rs.getString("status"), rs.getBoolean("admin")), limit, offset);
    }

    @Transactional
    public void update(UUID caller, UUID target, String status, Boolean admin) {
        if (status == null && admin == null) throw new ResponseStatusException(BAD_REQUEST);
        if (status != null && !List.of("ENABLED", "DISABLED").contains(status))
            throw new ResponseStatusException(BAD_REQUEST);
        // ponytail: 管理操作低频，统一串行化授权变更；规模增大后改用专用锁行。
        jdbc.execute("LOCK TABLE actor_role_grant IN SHARE ROW EXCLUSIVE MODE");
        requireAdmin(caller);
        // 禁止自停用、自撤权，防止把平台管理员全部移除。
        if (caller.equals(target) && ("DISABLED".equals(status) || Boolean.FALSE.equals(admin)))
            throw new ResponseStatusException(CONFLICT);
        List<String> targets = jdbc.query("SELECT status FROM actor WHERE id = ? AND kind = 'HUMAN' FOR UPDATE",
                (rs, row) -> rs.getString(1), target);
        if (targets.isEmpty()) throw new ResponseStatusException(NOT_FOUND);
        if (status != null) jdbc.update("UPDATE actor SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?", status, target);
        if (Boolean.TRUE.equals(admin)) {
            jdbc.update("""
                    INSERT INTO actor_role_grant (actor_id, role_code, granted_by)
                    VALUES (?, 'LABELING_ADMIN', ?) ON CONFLICT (actor_id, role_code) DO NOTHING
                    """, target, caller);
        } else if (Boolean.FALSE.equals(admin)) {
            jdbc.update("DELETE FROM actor_role_grant WHERE actor_id = ? AND role_code = 'LABELING_ADMIN'", target);
        }
    }

    public record UserView(UUID actorId, String identityUserId, String displayName,
                           String status, boolean labelingAdmin) {}
}
