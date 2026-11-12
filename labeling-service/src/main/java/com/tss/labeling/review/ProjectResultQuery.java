package com.tss.labeling.review;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tss.labeling.common.BusinessException;
import com.tss.labeling.identity.ActorService.ActorContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
class ProjectResultQuery {
    private static final Set<String> FILTER_KEYS = Set.of("specVersionId", "packageId", "roundId",
            "executionId", "sourceKind", "reviewStatus", "hasResult", "confidencePolicyId", "band");
    private static final List<String> CANDIDATE_FIELDS = List.of("resultId", "result", "sourceKind",
            "sourceActor", "packageId", "assignmentId", "roundId", "executionId", "modelVersion", "confidence", "band");

    private static final String RESULT_SCOPE = """
            WITH scope AS (
                SELECT s.id, s.project_id, s.source_path, sr.source_kind, sr.submission_id, sr.execution_id,
                       COALESCE(sr.status, 'UNREVIEWED') AS review_status, COALESCE(sr.revision, 0) AS review_revision,
                       EXISTS (
                           SELECT 1 FROM sample_work_claim wc JOIN publication_round pr ON pr.id=wc.round_id
                           WHERE wc.project_id=s.project_id AND wc.sample_id=s.id AND pr.spec_version_id=:spec
                             AND (CAST(:packageId AS uuid) IS NULL OR pr.package_id=:packageId)
                             AND (CAST(:roundId AS uuid) IS NULL OR pr.id=:roundId)
                             AND (CAST(:executionId AS uuid) IS NULL OR pr.id=(SELECT round_id FROM ai_execution WHERE id=:executionId))
                       ) AS active_processing
                FROM sample s
                LEFT JOIN sample_review sr ON sr.project_id=s.project_id AND sr.sample_id=s.id AND sr.spec_version_id=:spec
                WHERE s.project_id=:project
                  AND (CAST(:packageId AS uuid) IS NULL OR EXISTS (
                      SELECT 1 FROM package_item pi WHERE pi.package_id=:packageId AND pi.sample_id=s.id))
                  AND (CAST(:roundId AS uuid) IS NULL OR EXISTS (
                      SELECT 1 FROM round_item ri WHERE ri.round_id=:roundId AND ri.sample_id=s.id))
                  AND (CAST(:executionId AS uuid) IS NULL OR EXISTS (
                      SELECT 1 FROM ai_execution e JOIN round_item ri ON ri.round_id=e.round_id
                      WHERE e.id=:executionId AND ri.sample_id=s.id))
                  AND (CAST(:include AS text) IS NULL OR s.id=ANY(CAST(string_to_array(:include, ',') AS uuid[])))
                  AND (CAST(:exclude AS text) IS NULL OR NOT (s.id=ANY(CAST(string_to_array(:exclude, ',') AS uuid[]))))
            ), candidates AS (
                SELECT x.id AS "sampleId", x.source_path AS "sourcePath", CAST(:spec AS uuid) AS "specVersionId",
                       x.review_status AS "reviewStatus", x.review_revision AS "reviewRevision",
                       x.active_processing AS "activeProcessing", selected.result_id AS "selectedResultId",
                       COALESCE(candidate.candidate_count, 0) AS "candidateCount",
                       candidate.result_id AS "resultId", candidate.result::text AS result,
                       candidate.source_kind AS "sourceKind", candidate.source_actor AS "sourceActor",
                       candidate.package_id AS "packageId", candidate.assignment_id AS "assignmentId",
                       candidate.round_id AS "roundId", candidate.execution_id AS "executionId",
                       candidate.model_version AS "modelVersion", candidate.confidence, candidate.band,
                       failed.error_code AS "errorCode"
                FROM scope x
                LEFT JOIN LATERAL (
                    SELECT c.result_id FROM project_result_candidate c
                    WHERE c.project_id=x.project_id AND c.sample_id=x.id AND c.spec_version_id=:spec AND c.current_version
                      AND ((x.source_kind='HUMAN' AND c.submission_id=x.submission_id)
                        OR (x.source_kind='AI' AND c.execution_id=x.execution_id))
                    ORDER BY c.result_id LIMIT 1
                ) selected ON true
                LEFT JOIN LATERAL (
                    SELECT c.*, count(*) OVER () AS candidate_count, tier.band
                    FROM project_result_candidate c
                    LEFT JOIN confidence_policy cp ON cp.id=:policyId AND cp.execution_id=c.execution_id
                    CROSS JOIN LATERAL (
                        SELECT CASE WHEN cp.id IS NULL THEN NULL
                            WHEN c.confidence IS NULL THEN 'UNKNOWN'
                            WHEN c.confidence >= cp.upper_threshold THEN 'HIGH'
                            WHEN c.confidence >= cp.lower_threshold THEN 'MEDIUM'
                            ELSE 'LOW' END AS band
                    ) tier
                    WHERE c.project_id=x.project_id AND c.sample_id=x.id AND c.spec_version_id=:spec
                      AND (CAST(:packageId AS uuid) IS NULL OR c.package_id=:packageId)
                      AND (CAST(:roundId AS uuid) IS NULL OR c.round_id=:roundId)
                      AND (CAST(:executionId AS uuid) IS NULL OR c.execution_id=:executionId)
                      AND (CAST(:sourceKind AS text) IS NULL OR c.source_kind=:sourceKind)
                      AND (CAST(:band AS text) IS NULL OR tier.band=:band)
                    ORDER BY (c.result_id=selected.result_id) DESC NULLS LAST, c.result_id
                    LIMIT 1
                ) candidate ON true
                LEFT JOIN ai_item_output failed ON failed.execution_id=:executionId
                    AND failed.sample_id=x.id AND failed.status='FAILED'
            ), filtered AS (
                SELECT * FROM candidates
                WHERE (CAST(:reviewStatus AS text) IS NULL OR "reviewStatus"=:reviewStatus)
                  AND (CAST(:hasResult AS boolean) IS NULL OR ("candidateCount" > 0)=:hasResult)
                  AND (CAST(:band AS text) IS NULL OR "candidateCount" > 0)
            )
            """;

    private final NamedParameterJdbcTemplate db;
    private final ObjectMapper json;

    ProjectResultQuery(NamedParameterJdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    @Transactional
    public JsonNode list(ActorContext actor, UUID project, Map<String, String> params) {
        requireOwner(actor, project, false);
        var filter = new TreeMap<>(params);
        int limit = parseLimit(filter.remove("limit"));
        UUID cursor = parseCursor(filter.remove("cursor"));
        Scope scope = scope(project, null, filter);
        return json.valueToTree(page(rows(scope, cursor, limit + 1, null, null), limit, "sampleId"));
    }

    @Transactional
    public JsonNode statistics(ActorContext actor, UUID project, Map<String, String> filter) {
        requireOwner(actor, project, false);
        Scope scope = scope(project, null, filter);
        return json.valueToTree(db.queryForMap(RESULT_SCOPE + """
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE "candidateCount">0) AS "hasResultCount",
                       count(*) FILTER (WHERE "candidateCount"=0) AS "noResultCount",
                       count(*) FILTER (WHERE "reviewStatus"='NEEDS_REVISION') AS "needsRevisionCount",
                       count(*) FILTER (WHERE "reviewStatus"='ACCEPTED') AS "acceptedCount",
                       count(*) FILTER (WHERE "activeProcessing") AS "activeCount"
                FROM filtered
                """, arguments(scope, null, null)));
    }

    @Transactional
    public JsonNode history(ActorContext actor, UUID project, UUID sample, Map<String, String> params) {
        requireOwner(actor, project, false);
        requireFound("SELECT id FROM sample WHERE id=:id AND project_id=:project", sample, project);
        var filter = new TreeMap<>(params);
        int limit = parseLimit(filter.remove("limit"));
        UUID cursor = parseCursor(filter.remove("cursor"));
        UUID spec = parseId(filter.remove("specVersionId"));
        if (!filter.isEmpty()) throw invalid("历史仅支持按规范版本和游标筛选");
        if (spec != null) requireFound("SELECT id FROM spec_version WHERE id=:id AND project_id=:project", spec, project);

        var rows = db.queryForList("""
                SELECT c.sample_id AS "sampleId", c.spec_version_id AS "specVersionId", s.source_path AS "sourcePath",
                       c.result_id AS "resultId", c.result::text AS result, c.source_kind AS "sourceKind",
                       c.source_actor AS "sourceActor", c.submission_id AS "submissionId", c.package_id AS "packageId",
                       c.assignment_id AS "assignmentId", c.round_id AS "roundId", c.execution_id AS "executionId",
                       c.model_version AS "modelVersion", c.confidence,
                       COALESCE(sr.status,'UNREVIEWED') AS "reviewStatus", COALESCE(sr.revision,0) AS "reviewRevision",
                       selected.result_id AS "selectedResultId", true AS "hasResult", true AS reviewable,
                       EXISTS (SELECT 1 FROM sample_work_claim wc JOIN publication_round pr ON pr.id=wc.round_id
                           WHERE wc.project_id=c.project_id AND wc.sample_id=c.sample_id
                             AND pr.spec_version_id=c.spec_version_id) AS "activeProcessing"
                FROM project_result_candidate c JOIN sample s ON s.id=c.sample_id AND s.project_id=c.project_id
                LEFT JOIN sample_review sr ON sr.project_id=c.project_id AND sr.sample_id=c.sample_id AND sr.spec_version_id=c.spec_version_id
                LEFT JOIN LATERAL (
                    SELECT d.result_id FROM project_result_candidate d
                    WHERE d.project_id=c.project_id AND d.sample_id=c.sample_id
                      AND d.spec_version_id=c.spec_version_id AND d.current_version
                      AND ((sr.source_kind='HUMAN' AND d.submission_id=sr.submission_id)
                        OR (sr.source_kind='AI' AND d.execution_id=sr.execution_id))
                    ORDER BY d.result_id LIMIT 1
                ) selected ON true
                WHERE c.project_id=:project AND c.sample_id=:sample
                  AND (CAST(:spec AS uuid) IS NULL OR c.spec_version_id=:spec)
                  AND (CAST(:after AS uuid) IS NULL OR c.result_id>:after)
                ORDER BY c.result_id LIMIT :limit
                """, new MapSqlParameterSource("project", project).addValue("sample", sample)
                .addValue("spec", spec).addValue("after", cursor).addValue("limit", limit + 1));
        rows.forEach(row -> row.put("result", read((String) row.get("result"))));
        return json.valueToTree(page(rows, limit, "resultId"));
    }

    List<Map<String, Object>> rows(Scope scope, UUID cursor, int limit, Collection<UUID> include,
            Collection<UUID> exclude) {
        var rows = db.queryForList(RESULT_SCOPE + """
                SELECT * FROM filtered
                WHERE CAST(:after AS uuid) IS NULL OR "sampleId">:after
                ORDER BY "sampleId" LIMIT :limit
                """, arguments(scope, include, exclude).addValue("after", cursor).addValue("limit", limit));
        for (var row : rows) {
            long count = ((Number) row.get("candidateCount")).longValue();
            boolean unresolved = count > 1 && !Objects.equals(row.get("selectedResultId"), row.get("resultId"));
            if (unresolved) CANDIDATE_FIELDS.forEach(key -> row.put(key, null));
            row.put("requiresResultSelection", unresolved);
            row.put("hasResult", count > 0);
            row.put("reviewable", row.get("resultId") != null);
            row.put("result", read((String) row.get("result")));
        }
        return rows;
    }

    Map<UUID, UUID> candidateSamples(Scope scope, Collection<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        return db.query("""
                SELECT result_id, sample_id FROM project_result_candidate
                WHERE project_id=:project AND spec_version_id=:spec
                  AND result_id=ANY(CAST(string_to_array(:ids, ',') AS uuid[]))
                """, new MapSqlParameterSource("project", scope.project()).addValue("spec", scope.spec())
                .addValue("ids", joined(ids)), rs -> {
            Map<UUID, UUID> found = new HashMap<>();
            while (rs.next()) found.put(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class));
            return found;
        });
    }

    Scope scope(UUID project, UUID requestedSpec, Map<String, String> filter) {
        if (!FILTER_KEYS.containsAll(filter.keySet())) throw invalid("含不支持的结果筛选字段");
        UUID spec = parseId(filter.get("specVersionId"));
        if (requestedSpec != null && spec != null && !requestedSpec.equals(spec)) throw invalid("筛选与快照的规范版本不一致");
        if (requestedSpec != null) spec = requestedSpec;

        UUID pkg = parseId(filter.get("packageId"));
        UUID round = parseId(filter.get("roundId"));
        UUID execution = parseId(filter.get("executionId"));
        UUID policy = parseId(filter.get("confidencePolicyId"));
        if (pkg != null) spec = matchSpec(spec, requireFound("""
                SELECT p.spec_version_id FROM task_package t JOIN package_plan p ON p.id=t.plan_id
                WHERE t.id=:id AND p.project_id=:project
                """, pkg, project));
        if (round != null) spec = matchSpec(spec, requireFound(
                "SELECT spec_version_id FROM publication_round WHERE id=:id AND project_id=:project", round, project));
        if (execution != null) spec = matchSpec(spec, requireFound(
                "SELECT spec_version_id FROM ai_execution WHERE id=:id AND project_id=:project", execution, project));
        if (spec != null) requireFound("SELECT id FROM spec_version WHERE id=:id AND project_id=:project", spec, project);
        else {
            spec = db.query("SELECT id FROM spec_version WHERE project_id=:project ORDER BY version_no DESC LIMIT 1",
                    new MapSqlParameterSource("project", project), rs -> rs.next() ? rs.getObject(1, UUID.class) : null);
        }

        String source = choice(filter.get("sourceKind"), Set.of("HUMAN", "AI", "OWNER", "AGGREGATION"));
        String status = choice(filter.get("reviewStatus"), Set.of("UNREVIEWED", "PENDING", "NEEDS_REVISION", "ACCEPTED"));
        String band = choice(filter.get("band"), Set.of("HIGH", "MEDIUM", "LOW", "UNKNOWN"));
        if (band != null && policy == null) throw invalid("置信度分档需要指定策略");
        if (policy != null) {
            UUID policyExecution = requireFound(
                    "SELECT execution_id FROM confidence_policy WHERE id=:id AND project_id=:project", policy, project);
            if (!policyExecution.equals(execution)) throw invalid("置信度策略必须属于指定执行");
        }
        String hasResult = choice(filter.get("hasResult"), Set.of("true", "false"));
        return new Scope(project, spec, pkg, round, execution, source, status,
                hasResult == null ? null : Boolean.valueOf(hasResult), policy, band);
    }

    void requireOwner(ActorContext actor, UUID project, boolean lock) {
        if (lock) {
            String actorStatus = db.query("SELECT status FROM actor WHERE id=:id FOR SHARE",
                    new MapSqlParameterSource("id", actor.actorId()), rs -> rs.next() ? rs.getString(1) : null);
            if (!"ENABLED".equals(actorStatus)) throw error(403, "LABELING_DISABLED", "标注账号已停用");
        }
        requireFound("SELECT id FROM project WHERE id=:id AND owner_actor_id=:project" + (lock ? " FOR UPDATE" : ""),
                project, actor.actorId());
    }

    private UUID requireFound(String sql, UUID id, UUID project) {
        UUID found = db.query(sql, new MapSqlParameterSource("id", id).addValue("project", project),
                rs -> rs.next() ? rs.getObject(1, UUID.class) : null);
        if (found == null) throw error(404, "RESOURCE_NOT_FOUND", "资源不存在");
        return found;
    }

    private static UUID matchSpec(UUID current, UUID dimension) {
        if (current != null && !current.equals(dimension)) throw invalid("筛选范围使用不同的规范版本");
        return dimension;
    }

    private static MapSqlParameterSource arguments(Scope s, Collection<UUID> include, Collection<UUID> exclude) {
        return new MapSqlParameterSource("project", s.project()).addValue("spec", s.spec())
                .addValue("packageId", s.packageId()).addValue("roundId", s.roundId()).addValue("executionId", s.executionId())
                .addValue("sourceKind", s.sourceKind()).addValue("reviewStatus", s.reviewStatus()).addValue("hasResult", s.hasResult())
                .addValue("policyId", s.policy()).addValue("band", s.band())
                .addValue("include", joined(include)).addValue("exclude", joined(exclude));
    }

    private static String joined(Collection<UUID> values) {
        return values == null || values.isEmpty() ? null : values.stream().map(UUID::toString).collect(Collectors.joining(","));
    }

    private JsonNode read(String value) {
        if (value == null) return null;
        try {
            return json.readTree(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Invalid stored result", ex);
        }
    }

    static Map<String, Object> page(List<Map<String, Object>> rows, int limit, String id) {
        var items = rows.subList(0, Math.min(rows.size(), limit));
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("items", items);
        page.put("nextCursor", rows.size() > limit ? items.get(limit - 1).get(id).toString() : null);
        return page;
    }

    static int parseLimit(String value) {
        if (value == null) return 20;
        try {
            int limit = Integer.parseInt(value);
            if (limit >= 1 && limit <= 100) return limit;
        } catch (NumberFormatException ignored) {
            // Return the same client error as an out-of-range value.
        }
        throw error(400, "INVALID_REQUEST", "分页大小应为1至100");
    }

    static UUID parseCursor(String value) {
        if (value == null) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            throw error(400, "INVALID_CURSOR", "游标无效");
        }
    }

    private static UUID parseId(String value) {
        if (value == null) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            throw invalid("筛选编号必须是 UUID");
        }
    }

    private static String choice(String value, Set<String> supported) {
        if (value != null && !supported.contains(value)) throw invalid("筛选值不受支持");
        return value;
    }

    static BusinessException invalid(String message) {
        return error(422, "INVALID_REQUEST", message);
    }

    static BusinessException error(int status, String code, String message) {
        return new BusinessException(HttpStatus.valueOf(status), code, message);
    }

    record Scope(UUID project, UUID spec, UUID packageId, UUID roundId, UUID executionId, String sourceKind,
                 String reviewStatus, Boolean hasResult, UUID policy, String band) {}
}
