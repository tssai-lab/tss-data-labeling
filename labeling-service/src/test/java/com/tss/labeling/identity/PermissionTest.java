package com.tss.labeling.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tss.labeling.project.ProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PermissionTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class, withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS));
    private final UUID caller = UUID.randomUUID();
    private final UUID target = UUID.randomUUID();
    private final ActorAdminService admin = new ActorAdminService(jdbc);

    private void allowAdmin() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(caller))).thenReturn(true);
    }

    private void denied(int status, Runnable action) {
        assertEquals(status, assertThrows(ResponseStatusException.class, action::run).getStatusCode().value());
    }

    @Test void ordinaryUserCannotManageUsers() {
        denied(403, () -> admin.list(caller, 20, 0));
        denied(403, () -> admin.update(caller, target, "DISABLED", true));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test void adminCannotDisableOrDemoteSelf() {
        allowAdmin();
        denied(409, () -> admin.update(caller, caller, "DISABLED", null));
        denied(409, () -> admin.update(caller, caller, null, false));
    }

    @Test void rejectInvalidStatusAndPagination() {
        allowAdmin();
        denied(400, () -> admin.update(caller, target, "UNKNOWN", null));
        denied(400, () -> admin.update(caller, target, null, null));
        denied(400, () -> admin.list(caller, 101, 0));
    }

    @Test void cannotGrantUnknownActor() {
        allowAdmin();
        when(jdbc.query(anyString(), any(RowMapper.class), eq(target))).thenReturn(List.of());
        denied(404, () -> admin.update(caller, target, null, true));
    }

    @Test void adminCanDisableAndRevokeOtherUser() {
        allowAdmin();
        when(jdbc.query(anyString(), any(RowMapper.class), eq(target))).thenReturn(List.of("ENABLED"));
        admin.update(caller, target, "DISABLED", false);
        verify(jdbc).update(contains("UPDATE actor"), eq("DISABLED"), eq(target));
        verify(jdbc).update(contains("DELETE FROM actor_role_grant"), eq(target));
    }

    @Test void grantRecordsAdministrator() {
        allowAdmin();
        when(jdbc.query(anyString(), any(RowMapper.class), eq(target))).thenReturn(List.of("ENABLED"));
        admin.update(caller, target, null, true);
        verify(jdbc).update(contains("INSERT INTO actor_role_grant"), eq(target), eq(caller));
    }

    @Test void disabledActorCannotResolveAgain() {
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(new ActorService.ActorContext(target, "DISABLED", false));
        denied(403, () -> new ActorService(jdbc).resolve(new IdentityUser("2", "user02")));
    }

    @Test void projectAccessDoesNotBypassOwnerForAdmin() {
        when(jdbc.query(contains("SELECT status FROM actor"), any(ResultSetExtractor.class), eq(caller)))
                .thenReturn("ENABLED");
        ProjectService projects = new ProjectService(jdbc, new ObjectMapper());
        UUID projectId = UUID.randomUUID();
        denied(404, () -> projects.get(new ActorService.ActorContext(caller, "ENABLED", true), projectId.toString()));
        verify(jdbc).query(contains("WHERE id = ? AND owner_actor_id = ?"),
                any(ResultSetExtractor.class), eq(projectId), eq(caller));
    }
}
