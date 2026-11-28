package com.tss.labeling.review;

import static com.tss.labeling.common.WorkflowStore.error;

import com.fasterxml.jackson.databind.JsonNode;
import com.tss.labeling.common.WorkflowStore;
import com.tss.labeling.identity.ActorService.ActorContext;
import com.tss.labeling.plugins.TaskPluginRegistry;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class AggregationService {
    private final WorkflowStore s;
    private final ResultScopeService scopes;
    private final ResultAggregationRegistry strategies;
    private final TaskPluginRegistry plugins;

    public AggregationService(WorkflowStore s, ResultScopeService scopes, ResultAggregationRegistry strategies,
                              TaskPluginRegistry plugins) {
        this.s = s;
        this.scopes = scopes;
        this.strategies = strategies;
        this.plugins = plugins;
    }

    public record SampleInput(UUID sampleId, List<UUID> resultIds) {}

    public record Input(UUID specVersionId, String algorithmId, List<SampleInput> items, JsonNode parameters) {
        public Input(UUID specVersionId, String algorithmId, List<SampleInput> items) {
            this(specVersionId, algorithmId, items, null);
        }
    }

    @Transactional
    public JsonNode create(ActorContext actor, UUID project, Input input, String key) {
        var projectState=s.owner(actor,project,true);
        String op = "POST:/projects/" + project + "/aggregations";
        var replay = s.replay(actor, op, key, input);
        if (replay != null) return replay;
        WorkflowStore.requireMutable(projectState);
        var spec =
                s.one(
                        "SELECT * FROM spec_version WHERE id=? AND project_id=?",
                        input.specVersionId(),
                        project);
        String resultFormat = plugins.frozen(spec).descriptor().resultFormat();
        ResultAggregationStrategy strategy = strategies.resolve(
                input.algorithmId(), (String) spec.get("task_type"), resultFormat);
        var descriptor = strategy.descriptor();
        JsonNode parameters = input.parameters() == null || input.parameters().isNull()
                ? s.json.createObjectNode() : input.parameters();
        try {
            strategy.validateParameters(parameters);
        } catch (IllegalArgumentException invalid) {
            throw error(422, "INVALID_AGGREGATION_PARAMETERS", invalid.getMessage());
        }
        if (input.items() == null || input.items().isEmpty() || input.items().size() > 10000)
            throw error(422, "INVALID_REQUEST", "集成范围应为 1 至 10000 个样本");
        UUID id = UUID.randomUUID();
        s.db.update(
                "INSERT INTO"
                    + " aggregation_job(id,project_id,spec_version_id,algorithm,input_json,created_by,status,parameters)"
                    + " VALUES (?,?,?,?,?::jsonb,?,'SUCCEEDED',?::jsonb)",
                id,
                project,
                input.specVersionId(),
                input.algorithmId(),
                s.write(input.items()),
                actor.actorId(),
                s.write(parameters));
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
            List<ResultAggregationStrategy.Candidate> candidates = new ArrayList<>();
            for (UUID result : item.resultIds()) {
                if (result == null || !ids.add(result))
                    throw error(422, "DUPLICATE_SOURCE", "答案不可重复");
                var c = scopes.candidate(project, input.specVersionId(), item.sampleId(), result);
                String sourceKind = (String) c.get("source_kind");
                UUID sourceActor = (UUID) c.get("source_actor");
                if (sourceActor == null
                        || !descriptor.acceptedSourceKinds().contains(sourceKind)
                        || !actors.add(sourceActor))
                    throw error(422, "DUPLICATE_SOURCE", "每个独立参与者最多贡献一个原始答案，修订和集成结果不能计票");
                candidates.add(new ResultAggregationStrategy.Candidate(
                        sourceActor, sourceKind, s.read((String) c.get("content"))));
            }
            ResultAggregationStrategy.Evaluation evaluation = strategy.aggregate(candidates, parameters);
            if (evaluation == null
                    || evaluation.outcome() == null
                    || !Set.of("SUGGESTED", "TIE", "INSUFFICIENT_INPUT").contains(evaluation.outcome())
                    || evaluation.evidence() == null) {
                throw error(500, "INVALID_AGGREGATION_RESULT", "集成策略返回了无效结果");
            }
            UUID resultId = null;
            if ("SUGGESTED".equals(evaluation.outcome())) {
                if (evaluation.result() == null || evaluation.result().isNull())
                    throw error(500, "INVALID_AGGREGATION_RESULT", "集成策略未返回建议结果");
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
                        s.write(evaluation.result()),
                        input.algorithmId() + ":" + id);
            }
            s.db.update(
                    "INSERT INTO aggregation_item(aggregation_id,sample_id,result_id,outcome,votes)"
                        + " VALUES (?,?,?,?,?::jsonb)",
                    id,
                    item.sampleId(),
                    resultId,
                    evaluation.outcome(),
                    s.write(evaluation.evidence()));
        }
        return s.remember(actor, op, key, input, id, view(actor, id));
    }

    @Transactional
    public JsonNode view(ActorContext actor, UUID id) {
        var job =
                s.one(
                        "SELECT id AS \"aggregationId\",project_id AS \"projectId\",spec_version_id"
                            + " AS \"specVersionId\",algorithm AS \"algorithmId\",parameters::text AS parameters,status,created_at"
                            + " AS \"createdAt\" FROM aggregation_job WHERE id=?",
                        id);
        s.owner(actor, (UUID) job.get("projectId"), false);
        job.put("parameters", s.read((String) job.get("parameters")));
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
