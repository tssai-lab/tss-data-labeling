package com.tss.labeling.project;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tss.labeling.identity.ActorService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class ProjectService {

    private static final Pattern TASK_TYPE_PATTERN = Pattern.compile("[A-Z][A-Z0-9_]{1,79}");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ProjectService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ProjectView create(
            ActorService.ActorContext actor,
            ProjectController.CreateProjectRequest request,
            String idempotencyKey
    ) {
        String taskType = normalizeTaskType(request.taskType());
        UUID projectId = UUID.randomUUID();
        String requestHash = hash(toJson(objectMapper.valueToTree(request)));
        boolean created = beginIdempotency(
                actor.actorId(), "POST:/projects", idempotencyKey, requestHash, projectId);
        lockEnabledActor(actor.actorId());
        if (!created) {
            return findProject(projectIdFromIdempotency(
                    actor.actorId(), "POST:/projects", idempotencyKey), actor.actorId());
        }
        jdbcTemplate.update("""
                INSERT INTO project (id, owner_actor_id, name, description, task_type)
                VALUES (?, ?, ?, ?, ?)
                """, projectId, actor.actorId(), request.name().trim(),
                blankToNull(request.description()), taskType);
        return findProject(projectId, actor.actorId());
    }

    @Transactional(readOnly = true)
    public ProjectPage list(ActorService.ActorContext actor, int limit, String cursor) {
        lockEnabledActor(actor.actorId());
        String cursorClause = "";
        if (cursor != null && !cursor.isBlank()) {
            decodeCursor(cursor);
            cursorClause = " AND (created_at, id) < (?, ?)";
        }

        // Arguments are appended in SQL order below for the optional cursor clause.
        List<Object> orderedArguments = new ArrayList<>();
        orderedArguments.add(actor.actorId());
        if (!cursorClause.isEmpty()) {
            CursorValue value = decodeCursor(cursor);
            orderedArguments.add(Timestamp.from(value.createdAt()));
            orderedArguments.add(value.id());
        }
        orderedArguments.add(limit + 1);

        List<ProjectRow> rows = jdbcTemplate.query("""
                SELECT id, owner_actor_id, name, description, task_type, status,
                       revision, created_at, updated_at
                FROM project
                WHERE owner_actor_id = ?
                """ + cursorClause + " ORDER BY created_at DESC, id DESC LIMIT ?",
                (result, rowNumber) -> projectRow(result), orderedArguments.toArray());

        boolean hasMore = rows.size() > limit;
        if (hasMore) {
            rows.remove(rows.size() - 1);
        }
        List<ProjectSummary> items = rows.stream()
                .map(ProjectService::summary)
                .toList();
        String nextCursor = hasMore && !rows.isEmpty()
                ? encodeCursor(rows.get(rows.size() - 1))
                : null;
        return new ProjectPage(items, nextCursor);
    }

    @Transactional(readOnly = true)
    public ProjectView get(ActorService.ActorContext actor, String projectId) {
        lockEnabledActor(actor.actorId());
        return findProject(parseId(projectId), actor.actorId());
    }

    @Transactional
    public SpecVersionView createSpecVersion(
            ActorService.ActorContext actor,
            String projectIdValue,
            ProjectController.CreateSpecVersionRequest request,
            String idempotencyKey
    ) {
        UUID projectId = parseId(projectIdValue);
        String taskType = normalizeTaskType(request.taskType());
        validateLabels(request.labels());
        JsonNode editorConfig = request.editorConfig();
        if (editorConfig == null) {
            editorConfig = objectMapper.createObjectNode();
        }
        if (!editorConfig.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }

        String operation = "POST:/projects/" + projectId + "/spec-versions";
        String requestHash = hash(toJson(objectMapper.valueToTree(request)));
        UUID specVersionId = UUID.randomUUID();
        boolean created = beginIdempotency(
                actor.actorId(), operation, idempotencyKey, requestHash, specVersionId);
        lockEnabledActor(actor.actorId());
        if (!created) {
            return jdbcTemplate.queryForObject("""
                    SELECT id, project_id, version_no, task_type, labels, instructions,
                           editor_config, schema_version, created_at
                    FROM spec_version WHERE id = ? AND project_id = ?
                    """, (result, rowNumber) -> specVersion(result),
                    projectIdFromIdempotency(actor.actorId(), operation, idempotencyKey), projectId);
        }

        String projectTaskType = jdbcTemplate.query(
                "SELECT task_type FROM project WHERE id = ? AND owner_actor_id = ? FOR UPDATE",
                result -> result.next() ? result.getString(1) : null,
                projectId,
                actor.actorId()
        );
        if (projectTaskType == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (!projectTaskType.equals(taskType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }

        Integer versionNo = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(version_no), 0) + 1 FROM spec_version WHERE project_id = ?",
                Integer.class,
                projectId
        );
        jdbcTemplate.update("""
                INSERT INTO spec_version
                    (id, project_id, version_no, task_type, labels, instructions, editor_config, schema_version)
                VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?, CAST(? AS jsonb), ?)
                """, specVersionId, projectId, versionNo, taskType,
                toJson(request.labels()), blankToEmpty(request.instructions()),
                toJson(editorConfig), request.schemaVersion().trim());
        return jdbcTemplate.queryForObject("""
                SELECT id, project_id, version_no, task_type, labels, instructions,
                       editor_config, schema_version, created_at
                FROM spec_version WHERE id = ? AND project_id = ?
                """, (result, rowNumber) -> specVersion(result), specVersionId, projectId);
    }

    private ProjectView findProject(UUID projectId, UUID ownerActorId) {
        ProjectRow project = jdbcTemplate.query(
                """
                SELECT id, owner_actor_id, name, description, task_type, status,
                       revision, created_at, updated_at
                FROM project WHERE id = ? AND owner_actor_id = ?
                """,
                result -> result.next() ? projectRow(result) : null,
                projectId,
                ownerActorId
        );
        if (project == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        List<SpecVersionView> specs = jdbcTemplate.query("""
                SELECT id, project_id, version_no, task_type, labels, instructions,
                       editor_config, schema_version, created_at
                FROM spec_version WHERE project_id = ? ORDER BY version_no DESC
                """, (result, rowNumber) -> specVersion(result), projectId);
        return new ProjectView(
                project.id().toString(),
                project.ownerActorId().toString(),
                project.name(),
                project.description(),
                project.taskType(),
                project.status(),
                project.revision(),
                project.createdAt(),
                project.updatedAt(),
                specs
        );
    }

    private static ProjectRow projectRow(ResultSet result) throws SQLException {
        return new ProjectRow(
                result.getObject("id", UUID.class),
                result.getObject("owner_actor_id", UUID.class),
                result.getString("name"),
                result.getString("description"),
                result.getString("task_type"),
                result.getString("status"),
                result.getLong("revision"),
                result.getTimestamp("created_at").toInstant(),
                result.getTimestamp("updated_at").toInstant()
        );
    }

    private SpecVersionView specVersion(ResultSet result) throws SQLException {
        return new SpecVersionView(
                result.getObject("id", UUID.class).toString(),
                result.getObject("project_id", UUID.class).toString(),
                result.getInt("version_no"),
                result.getString("task_type"),
                readJson(result.getString("labels")),
                result.getString("instructions"),
                readJson(result.getString("editor_config")),
                result.getString("schema_version"),
                result.getTimestamp("created_at").toInstant()
        );
    }

    private static ProjectSummary summary(ProjectRow row) {
        return new ProjectSummary(
                row.id().toString(), row.name(), row.description(), row.taskType(),
                row.status(), row.revision(), row.createdAt(), row.updatedAt()
        );
    }

    private static String normalizeTaskType(String taskType) {
        String normalized = taskType.trim().toUpperCase(Locale.ROOT);
        if (!TASK_TYPE_PATTERN.matcher(normalized).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        return normalized;
    }

    private void validateLabels(JsonNode labels) {
        if (labels == null || !labels.isArray() || labels.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        Set<String> ids = new HashSet<>();
        for (JsonNode label : labels) {
            if (!label.isObject()
                    || !label.path("id").isTextual()
                    || label.path("id").asText().isBlank()
                    || !label.path("name").isTextual()
                    || label.path("name").asText().isBlank()
                    || !ids.add(label.path("id").asText())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            }
        }
    }

    private String toJson(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("JSON无法序列化", exception);
        }
    }

    private JsonNode readJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("数据库中的JSON格式无效", exception);
        }
    }

    private static UUID parseId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String encodeCursor(ProjectRow row) {
        String value = row.createdAt() + "#" + row.id();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static CursorValue decodeCursor(String cursor) {
        try {
            String decoded = new String(
                    Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int separator = decoded.lastIndexOf('#');
            if (separator < 1) {
                throw new IllegalArgumentException();
            }
            return new CursorValue(
                    Instant.parse(decoded.substring(0, separator)),
                    UUID.fromString(decoded.substring(separator + 1))
            );
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
    }

    private boolean beginIdempotency(
            UUID actorId,
            String operation,
            String key,
            String requestHash,
            UUID resourceId
    ) {
        if (key == null || key.isBlank() || key.length() > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        int inserted = jdbcTemplate.update("""
                INSERT INTO idempotency_record (actor_id, operation, idempotency_key, request_hash, resource_id)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (actor_id, operation, idempotency_key) DO NOTHING
                """, actorId, operation, key, requestHash, resourceId);
        if (inserted == 1) {
            return true;
        }
        String existingHash = jdbcTemplate.queryForObject("""
                SELECT request_hash FROM idempotency_record
                WHERE actor_id = ? AND operation = ? AND idempotency_key = ?
                """, String.class, actorId, operation, key);
        if (!requestHash.equals(existingHash)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        }
        return false;
    }

    private void lockEnabledActor(UUID actorId) {
        String status = jdbcTemplate.query(
                "SELECT status FROM actor WHERE id = ? FOR SHARE",
                result -> result.next() ? result.getString(1) : null,
                actorId
        );
        if (!"ENABLED".equals(status)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }

    private UUID projectIdFromIdempotency(UUID actorId, String operation, String key) {
        return jdbcTemplate.queryForObject("""
                SELECT resource_id FROM idempotency_record
                WHERE actor_id = ? AND operation = ? AND idempotency_key = ?
                """, UUID.class, actorId, operation, key);
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256不可用", exception);
        }
    }

    public record ProjectPage(List<ProjectSummary> items, String nextCursor) {
    }

    public record ProjectSummary(
            String projectId,
            String name,
            String description,
            String taskType,
            String status,
            long revision,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record ProjectView(
            String projectId,
            String ownerActorId,
            String name,
            String description,
            String taskType,
            String status,
            long revision,
            Instant createdAt,
            Instant updatedAt,
            List<SpecVersionView> specVersions
    ) {
    }

    public record SpecVersionView(
            String specVersionId,
            String projectId,
            int versionNo,
            String taskType,
            JsonNode labels,
            String instructions,
            JsonNode editorConfig,
            String schemaVersion,
            Instant createdAt
    ) {
    }

    private record ProjectRow(
            UUID id,
            UUID ownerActorId,
            String name,
            String description,
            String taskType,
            String status,
            long revision,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    private record CursorValue(Instant createdAt, UUID id) {
    }
}
