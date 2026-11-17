package com.tss.labeling.review;

import static com.tss.labeling.common.WorkflowStore.error;

import com.fasterxml.jackson.databind.JsonNode;
import com.tss.labeling.common.WorkflowStore;
import com.tss.labeling.identity.ActorService.ActorContext;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class AggregationService {
    private final WorkflowStore s;
    private final ResultScopeService scopes;

    public AggregationService(WorkflowStore s, ResultScopeService scopes) {
        this.s = s;
        this.scopes = scopes;
    }

    public record SampleInput(UUID sampleId, List<UUID> resultIds) {}

    public record Input(UUID specVersionId, String algorithmId, List<SampleInput> items) {}

    @Transactional
    public JsonNode create(ActorContext actor, UUID project, Input input, String key) {
        var projectState=s.owner(actor,project,true);
        String op = "POST:/projects/" + project + "/aggregations";
        var replay = s.replay(actor, op, key, input);
        if (replay != null) return replay;
        WorkflowStore.requireMutable(projectState);
        if (!"majority-vote-v1".equals(input.algorithmId()))
            throw error(422, "UNSUPPORTED_ALGORITHM", "不支持该算法");
        var spec =
                s.one(
                        "SELECT task_type FROM spec_version WHERE id=? AND project_id=?",
                        input.specVersionId(),
                        project);
        if (!"IMAGE_CLASSIFICATION".equals(spec.get("task_type")))
            throw error(422, "UNSUPPORTED_FORMAT", "多数表决仅支持图片单标签分类");
        if (input.items() == null || input.items().isEmpty() || input.items().size() > 10000)
            throw error(422, "INVALID_REQUEST", "集成范围应为 1 至 10000 个样本");
        UUID id = UUID.randomUUID();
        s.db.update(
                "INSERT INTO"
                    + " aggregation_job(id,project_id,spec_version_id,algorithm,input_json,created_by,status)"
                    + " VALUES (?,?,?,?,?::jsonb,?,'SUCCEEDED')",
                id,
                project,
                input.specVersionId(),
                input.algorithmId(),
                s.write(input.items()),
                actor.actorId());
        Set<UUID> samples = new HashSet<>();
        for (var item : input.items()) {
            if (item == null
                    || item.sampleId() == null
                    || !samples.add(item.sampleId())
                    || item.resultIds() == null
                    || item.resultIds().size() > 100
                    || item.resultIds().isEmpty())
                throw error(422, "INVALID_REQUEST", "样本不可重复，每个样本需要 1 至 100 个明确答案");
            Set<UUID> actors = new HashSet<>(), ids = new HashSet<>();
            Map<String, Integer> votes = new TreeMap<>();
            for (UUID result : item.resultIds()) {
                if (result == null || !ids.add(result))
                    throw error(422, "DUPLICATE_SOURCE", "答案不可重复");
                var c = scopes.candidate(project, input.specVersionId(), item.sampleId(), result);
                if (!Set.of("HUMAN", "AI").contains(c.get("source_kind"))
                        || !actors.add((UUID) c.get("source_actor")))
                    throw error(422, "DUPLICATE_SOURCE", "每个独立参与者最多贡献一个原始答案，修订和集成结果不能计票");
                String label = s.read((String) c.get("content")).path("labelId").asText();
                votes.merge(label, 1, Integer::sum);
            }
            var winner =
                    votes.entrySet().stream()
                            .filter(e -> e.getValue() > actors.size() / 2)
                            .findFirst();
            String outcome =
                    actors.size() < 2
                            ? "INSUFFICIENT_INPUT"
                            : winner.isEmpty() ? "TIE" : "SUGGESTED";
            UUID resultId = null;
            if ("SUGGESTED".equals(outcome)) {
                resultId = UUID.randomUUID();
                s.db.update(
                        "INSERT INTO"
                            + " owner_result(id,project_id,spec_version_id,sample_id,source_kind,created_by,result,reason)"
                            + " VALUES (?,?,?,?,'AGGREGATION',?,?::jsonb,?)",
                        resultId,
                        project,
                        input.specVersionId(),
                        item.sampleId(),
                        actor.actorId(),
                        s.write(Map.of("labelId", winner.orElseThrow().getKey())),
                        "majority-vote-v1:" + id);
            }
            s.db.update(
                    "INSERT INTO aggregation_item(aggregation_id,sample_id,result_id,outcome,votes)"
                        + " VALUES (?,?,?,?,?::jsonb)",
                    id,
                    item.sampleId(),
                    resultId,
                    outcome,
                    s.write(votes));
        }
        return s.remember(actor, op, key, input, id, view(actor, id));
    }

    @Transactional
    public JsonNode view(ActorContext actor, UUID id) {
        var job =
                s.one(
                        "SELECT id AS \"aggregationId\",project_id AS \"projectId\",spec_version_id"
                            + " AS \"specVersionId\",algorithm AS \"algorithmId\",status,created_at"
                            + " AS \"createdAt\" FROM aggregation_job WHERE id=?",
                        id);
        s.owner(actor, (UUID) job.get("projectId"), false);
        return s.json.valueToTree(job);
    }

    @Transactional
    public JsonNode items(ActorContext actor, UUID id, int limit, String cursor) {
        view(actor, id);
        var after = s.cursor(limit, cursor);
        var rows =
                s.db.queryForList(
                        "SELECT i.sample_id AS \"sampleId\",i.result_id AS"
                            + " \"resultId\",i.outcome,i.votes::text AS votes,o.result::text AS"
                            + " result FROM aggregation_item i LEFT JOIN owner_result o ON"
                            + " o.id=i.result_id WHERE i.aggregation_id=? AND i.sample_id>? ORDER"
                            + " BY i.sample_id LIMIT ?",
                        id,
                        after,
                        limit + 1);
        rows.forEach(
                r -> {
                    r.put("votes", s.read((String) r.get("votes")));
                    if (r.get("result") != null) r.put("result", s.read((String) r.get("result")));
                });
        return s.page(rows, limit, "sampleId");
    }
}
