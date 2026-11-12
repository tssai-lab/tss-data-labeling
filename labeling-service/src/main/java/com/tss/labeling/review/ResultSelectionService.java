package com.tss.labeling.review;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tss.labeling.identity.ActorService.ActorContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.*;

import static com.tss.labeling.review.ProjectResultQuery.error;
import static com.tss.labeling.review.ProjectResultQuery.invalid;

@Service
public class ResultSelectionService {
    private static final int MAX_ITEMS = 10_000;
    private static final Set<String> PURPOSES = Set.of("PUBLISH", "REVIEW", "FINALIZE");
    private static final Set<String> ACTIONS = Set.of("ACCEPT", "NEEDS_REVISION");

    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final ProjectResultQuery results;

    ResultSelectionService(JdbcTemplate db, ObjectMapper json, ProjectResultQuery results) {
        this.db = db;
        this.json = json;
        this.results = results;
    }

    @Transactional
    public JsonNode create(ActorContext actor, UUID project, CreateRequest request, String key) {
        results.requireOwner(actor, project, true);
        validate(request, key);
        var filter = filterValues(request.filter());
        var items = explicitItems(request.items());
        var excluded = new TreeSet<UUID>();
        if (request.excludeSampleIds() != null) excluded.addAll(request.excludeSampleIds());
        var scope = results.scope(project, request.specVersionId(), filter);
        String operation = "POST:/projects/" + project + "/result-selections";
        String requestHash = hash(new RequestFingerprint(request.specVersionId(), request.purpose(),
                request.reviewAction(), filter, List.copyOf(excluded), List.copyOf(items.values()), request.filter() != null));
        JsonNode replayed = replay(actor.actorId(), operation, key, requestHash);
        if (replayed != null) return replayed;

        // Scope, visible answers and review revisions are read together, independent of the UI's paging.
        var rows = results.rows(scope, null, MAX_ITEMS + 1, items.keySet(), excluded);
        if (rows.isEmpty() && items.isEmpty()) throw error(422, "EMPTY_SELECTION", "请选择至少一个样本");
        if (rows.size() > MAX_ITEMS) throw error(422, "SELECTION_TOO_LARGE", "一次最多选择 10000 个样本");
        if (!items.isEmpty() && rows.size() != items.size()) throw invalid("选择清单包含项目外或不存在的样本");
        var candidateIds = items.values().stream().map(ExplicitItem::resultId).filter(Objects::nonNull).toList();
        Map<UUID, UUID> candidates = results.candidateSamples(scope, candidateIds);

        var chosen = new ArrayList<FrozenItem>(rows.size());
        for (var row : rows) {
            UUID sample = (UUID) row.get("sampleId");
            long revision = ((Number) row.get("reviewRevision")).longValue();
            ExplicitItem item = items.get(sample);
            UUID result = item == null ? (UUID) row.get("resultId") : item.resultId();
            if (item != null) {
                if (item.reviewRevision() != revision) throw error(409, "REVISION_CONFLICT", "样本审核版本已变化，请重新选择");
                if (result != null && !sample.equals(candidates.get(result))) throw invalid("所选结果与样本或规范不匹配");
            }
            validateSelectionItem(request, row, result);
            chosen.add(new FrozenItem(sample, result, revision));
        }

        UUID id = UUID.randomUUID();
        String digest = hash(new SelectionFingerprint(project, request.specVersionId(), request.purpose(),
                request.reviewAction(), chosen));
        OffsetDateTime expiresAt = db.queryForObject("""
                INSERT INTO result_selection(id,project_id,spec_version_id,created_by,purpose,review_action,
                    filter_json,policy_id,statistics_json,selection_hash,item_count)
                VALUES (?,?,?,?,?,?,?::jsonb,?,?::jsonb,?,?) RETURNING expires_at
                """, (rs, row) -> rs.getObject(1, OffsetDateTime.class), id, project, request.specVersionId(),
                actor.actorId(), request.purpose(), request.reviewAction(),
                request.filter() == null ? null : write(Map.of("filter", filter, "excludeSampleIds", excluded)),
                scope.policy(), write(summarize(rows)), digest, chosen.size());
        List<Object[]> values = new ArrayList<>(chosen.size());
        for (int i = 0; i < chosen.size(); i++) {
            FrozenItem item = chosen.get(i);
            values.add(new Object[] { id, project, item.sampleId(), item.resultId(), item.reviewRevision(), i + 1 });
        }
        db.batchUpdate("""
                INSERT INTO result_selection_item(selection_id,project_id,sample_id,result_id,review_revision,ordinal)
                VALUES (?,?,?,?,?,?)
                """, values);

        var response = json.valueToTree(new SelectionResponse(id, digest, chosen.size(), expiresAt.toString(),
                request.purpose(), request.reviewAction()));
        db.update("""
                INSERT INTO idempotency_record(actor_id,operation,idempotency_key,request_hash,resource_id,response_body)
                VALUES (?,?,?,?,?,?::jsonb)
                """, actor.actorId(), operation, key, requestHash, id, write(response));
        return response;
    }

    @Transactional
    public JsonNode items(ActorContext actor, UUID id, int limit, String cursor) {
        if (limit < 1 || limit > 100) throw error(400, "INVALID_REQUEST", "分页大小应为1至100");
        UUID after = ProjectResultQuery.parseCursor(cursor);
        ownedSelection(actor, id);
        var rows = db.queryForList("""
                SELECT sample_id AS "sampleId", result_id AS "resultId", review_revision AS "reviewRevision"
                FROM result_selection_item
                WHERE selection_id=? AND (CAST(? AS uuid) IS NULL OR sample_id>?)
                ORDER BY sample_id LIMIT ?
                """, id, after, after, limit + 1);
        return json.valueToTree(ProjectResultQuery.page(rows, limit, "sampleId"));
    }

    @Transactional
    public JsonNode statistics(ActorContext actor, UUID id) {
        return read(ownedSelection(actor, id));
    }

    private String ownedSelection(ActorContext actor, UUID id) {
        return db.query("""
                SELECT s.statistics_json::text, s.expires_at <= CURRENT_TIMESTAMP AS expired
                FROM result_selection s JOIN project p ON p.id=s.project_id
                WHERE s.id=? AND p.owner_actor_id=? AND s.created_by=?
                """, rs -> {
            if (!rs.next()) throw error(404, "RESOURCE_NOT_FOUND", "选择快照不存在");
            if (rs.getBoolean("expired")) throw error(410, "SELECTION_EXPIRED", "选择快照已过期，请重新选择");
            return rs.getString(1);
        }, id, actor.actorId(), actor.actorId());
    }

    private JsonNode replay(UUID actor, String operation, String key, String expectedHash) {
        // create locks the project first, serializing writes for the same operation and idempotency key.
        return db.query("""
                SELECT request_hash,response_body::text FROM idempotency_record
                WHERE actor_id=? AND operation=? AND idempotency_key=?
                """, rs -> {
            if (!rs.next()) return null;
            if (!expectedHash.equals(rs.getString(1))) {
                throw error(409, "IDEMPOTENCY_CONFLICT", "同一幂等键请求内容不同");
            }
            return read(rs.getString(2));
        }, actor, operation, key);
    }

    private static void validate(CreateRequest request, String key) {
        if (key.isBlank() || key.length() > 200) throw error(400, "INVALID_REQUEST", "需要有效幂等键");
        if (!PURPOSES.contains(request.purpose())) throw invalid("选择用途不受支持");
        if ("REVIEW".equals(request.purpose())) {
            if (request.reviewAction() == null || !ACTIONS.contains(request.reviewAction())) {
                throw invalid("审核选择必须指定 ACCEPT 或 NEEDS_REVISION");
            }
        } else if (request.reviewAction() != null) {
            throw invalid("仅审核选择可指定审核动作");
        }
        if ((request.filter() == null) == (request.items() == null)
                || (request.items() != null && request.excludeSampleIds() != null)) {
            throw invalid("请使用筛选和排除项，或单独提供明确的样本清单");
        }
        if (request.items() != null && request.items().isEmpty()) {
            throw error(422, "EMPTY_SELECTION", "请选择至少一个样本");
        }
    }

    private static void validateSelectionItem(CreateRequest request, Map<String, Object> row, UUID result) {
        boolean finalize = "FINALIZE".equals(request.purpose());
        if (result == null && (finalize || "ACCEPT".equals(request.reviewAction()))) {
            throw error(422, "RESULT_SELECTION_REQUIRED", "请为样本明确选择一份标注结果");
        }
        if (finalize && (!"ACCEPTED".equals(row.get("reviewStatus")) || !result.equals(row.get("selectedResultId")))) {
            throw error(422, "RESULT_NOT_REVIEWABLE", "成果选择只接受当前已确认的结果");
        }
    }

    private static Map<String, String> filterValues(Map<String, JsonNode> input) {
        var result = new TreeMap<String, String>();
        if (input == null) return result;
        input.forEach((name, value) -> {
            if (value == null || (!value.isTextual() && !value.isBoolean())) throw invalid("筛选值必须是字符串或布尔值");
            result.put(name, value.asText());
        });
        return result;
    }

    private static Map<UUID, ExplicitItem> explicitItems(List<ExplicitItem> list) {
        var result = new TreeMap<UUID, ExplicitItem>();
        if (list == null) return result;
        for (ExplicitItem item : list) {
            if (result.putIfAbsent(item.sampleId(), item) != null) throw invalid("选择清单不能包含重复样本");
        }
        return result;
    }

    private static Map<String, Long> summarize(List<Map<String, Object>> rows) {
        long hasResult = 0, needsRevision = 0, accepted = 0, active = 0;
        for (var row : rows) {
            if (Boolean.TRUE.equals(row.get("hasResult"))) hasResult++;
            if ("NEEDS_REVISION".equals(row.get("reviewStatus"))) needsRevision++;
            if ("ACCEPTED".equals(row.get("reviewStatus"))) accepted++;
            if (Boolean.TRUE.equals(row.get("activeProcessing"))) active++;
        }
        return Map.of("total", (long) rows.size(), "hasResultCount", hasResult, "noResultCount", rows.size() - hasResult,
                "needsRevisionCount", needsRevision, "acceptedCount", accepted, "activeCount", active);
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot encode result selection", ex);
        }
    }

    private JsonNode read(String value) {
        try {
            return json.readTree(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Invalid stored result selection", ex);
        }
    }

    private String hash(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(write(value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public record CreateRequest(@NotNull UUID specVersionId, @NotNull String purpose, String reviewAction,
            Map<String, JsonNode> filter, @Size(max = MAX_ITEMS) List<@NotNull UUID> excludeSampleIds,
            @Size(max = MAX_ITEMS) List<@Valid @NotNull ExplicitItem> items) {}
    public record ExplicitItem(@NotNull UUID sampleId, UUID resultId, @NotNull @PositiveOrZero Long reviewRevision) {}
    private record FrozenItem(UUID sampleId, UUID resultId, long reviewRevision) {}
    private record RequestFingerprint(UUID specVersionId, String purpose, String reviewAction, Map<String, String> filter,
            List<UUID> excluded, List<ExplicitItem> explicit, boolean filtered) {}
    private record SelectionFingerprint(UUID projectId, UUID specVersionId, String purpose, String reviewAction,
            List<FrozenItem> items) {}
    private record SelectionResponse(UUID selectionId, String selectionHash, int count, String expiresAt,
            String purpose, String reviewAction) {}
}
