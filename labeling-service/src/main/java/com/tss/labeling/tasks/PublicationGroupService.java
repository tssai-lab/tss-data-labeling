package com.tss.labeling.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.tss.labeling.ai.AiService;
import com.tss.labeling.common.WorkflowStore;
import com.tss.labeling.identity.ActorService.ActorContext;
import com.tss.labeling.review.ResultScopeService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class PublicationGroupService {
    private final WorkflowStore store;
    private final ResultScopeService scopes;
    private final TaskService tasks;
    private final AiService ai;

    public PublicationGroupService(WorkflowStore store, ResultScopeService scopes, TaskService tasks, AiService ai) {
        this.store = store; this.scopes = scopes; this.tasks = tasks; this.ai = ai;
    }

    public record Input(UUID specVersionId, UUID selectionId, Boolean human, Integer packageSize,
                        Integer annotatorsPerSample, List<UUID> agentIds, Boolean reopenAccepted,
                        String instructions) {}

    @Transactional
    public JsonNode publish(ActorContext actor, UUID project, Input input, String key) {
        var p = store.owner(actor, project, true);
        if (!List.of("DRAFT", "OPEN").contains(p.get("status")))
            throw WorkflowStore.error(409, "PROJECT_CLOSED", "项目当前不可发布");
        if (input.specVersionId() == null || input.selectionId() == null || input.reopenAccepted() == null)
            throw WorkflowStore.error(400, "INVALID_REQUEST", "需要规范、固定范围和重开选项");
        var agents = input.agentIds() == null ? List.<UUID>of() : input.agentIds();
        if ((!Boolean.TRUE.equals(input.human()) && agents.isEmpty()) || agents.size() > 10
                || agents.contains(null) || new HashSet<>(agents).size() != agents.size())
            throw WorkflowStore.error(422, "INVALID_REQUEST", "请选择人工或不超过十个不同的模型");
        if (input.instructions() != null && input.instructions().length() > 500)
            throw WorkflowStore.error(422, "INVALID_REQUEST", "任务说明不能超过500字");
        String op = "POST:/projects/" + project + "/publication-groups";
        JsonNode previous = store.replay(actor, op, key, input);
        if (previous != null) return previous;
        var scope = scopes.selection(actor, project, input.selectionId(), "PUBLISH", null);
        if (!scope.specVersionId().equals(input.specVersionId()))
            throw WorkflowStore.error(409, "SPEC_MISMATCH", "范围规范不一致");
        scopes.validate(project, input.specVersionId(), scope.items(), false, true);
        var ids = scope.items().stream().map(ResultScopeService.Item::sampleId).toList();
        scopes.checkReopen(project, input.specVersionId(), ids, input.reopenAccepted());
        UUID group = UUID.randomUUID();
        store.db.update("INSERT INTO publication_group(id,project_id,spec_version_id,selection_id,created_by) VALUES (?,?,?,?,?)",
                group, project, input.specVersionId(), input.selectionId(), actor.actorId());
        JsonNode plan = null;
        if (Boolean.TRUE.equals(input.human())) {
            var samples = ids.stream().map(id -> new TaskService.SelectionSample(id, null)).toList();
            var created = tasks.createPlan(actor, project, new TaskService.PlanInput(null, input.specVersionId(),
                    input.packageSize(), input.annotatorsPerSample(), samples, input.instructions(), null, null, input.reopenAccepted()), "group-plan-" + group);
            plan = tasks.publishForGroup(actor, UUID.fromString(created.path("planId").asText()), created.path("selectionHash").asText(), "group-publish-" + group, group);
        }
        var executions = new ArrayList<JsonNode>();
        for (UUID agent : agents) {
            var samples = ids.stream().map(id -> new AiService.SelectionSample(id, null)).toList();
            var execution = ai.createExecutionForGroup(actor, project,
                    new AiService.CreateInput(input.specVersionId(), agent, samples, input.instructions(), null, "NONE", input.reopenAccepted()),
                    "group-ai-" + group + "-" + agent, group);
            store.db.update("INSERT INTO publication_group_execution(publication_group_id,execution_id,agent_id) VALUES (?,?,?)", group, UUID.fromString(execution.path("executionId").asText()), agent);
            ((com.fasterxml.jackson.databind.node.ObjectNode) execution).put("agentConfigId", agent.toString());
            executions.add(execution);
        }
        Map<String,Object> receipt = new LinkedHashMap<>();
        receipt.put("publicationGroupId", group); receipt.put("selectionId", input.selectionId());
        receipt.put("specVersionId", input.specVersionId()); receipt.put("sampleCount", ids.size());
        receipt.put("humanPlan", plan); receipt.put("humanStatus", plan == null ? null : "待领取"); receipt.put("executions", executions);
        return store.remember(actor, op, key, input, group, receipt);
    }

    @Transactional
    public JsonNode get(ActorContext actor, UUID project, UUID group) {
        store.owner(actor, project, false);
        var row = store.one("SELECT * FROM publication_group WHERE id=? AND project_id=?", group, project);
        var plans = store.db.queryForList("SELECT DISTINCT tp.plan_id FROM publication_round r JOIN task_package tp ON tp.id=r.package_id WHERE r.publication_group_id=?", UUID.class, group);
        var executions = store.db.queryForList("SELECT e.id, ge.agent_id FROM ai_execution e JOIN publication_group_execution ge ON ge.execution_id=e.id WHERE ge.publication_group_id=? ORDER BY e.created_at,e.id", group);
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("publicationGroupId", group); result.put("selectionId", row.get("selection_id"));
        result.put("specVersionId", row.get("spec_version_id"));
        var sampleIds = store.db.queryForList("SELECT sample_id FROM result_selection_item WHERE selection_id=? ORDER BY ordinal", UUID.class, row.get("selection_id"));
        result.put("sampleIds", sampleIds); result.put("sampleCount", sampleIds.size());
        result.put("humanPlan", plans.isEmpty() ? null : tasks.getPlan(actor, plans.get(0)));
        if (!plans.isEmpty()) {
            var counts = store.db.queryForMap("""
                SELECT COUNT(*) AS total,
                  COUNT(*) FILTER (WHERE r.status='CANCELLED') AS cancelled,
                  COUNT(*) FILTER (WHERE EXISTS (SELECT 1 FROM assignment a WHERE a.slot_id=s.id AND a.status IN ('CLAIMED','IN_PROGRESS','SUBMITTED'))) AS started,
                  COUNT(*) FILTER (WHERE EXISTS (SELECT 1 FROM assignment a WHERE a.slot_id=s.id AND a.status='SUBMITTED')) AS submitted
                FROM package_slot s JOIN publication_round r ON r.id=s.round_id WHERE r.publication_group_id=?
                """, group);
            long total = ((Number)counts.get("total")).longValue(), started = ((Number)counts.get("started")).longValue(), submitted = ((Number)counts.get("submitted")).longValue(), cancelled = ((Number)counts.get("cancelled")).longValue();
            result.put("humanStatus", total > 0 && cancelled == total ? "已取消" : total > 0 && submitted == total ? "已提交" : total > 0 && submitted + cancelled == total ? "已结束" : started > 0 || cancelled > 0 ? "进行中" : "待领取");
        } else result.put("humanStatus", null);
        result.put("executions", executions.stream().map(item -> { var value = (com.fasterxml.jackson.databind.node.ObjectNode) ai.execution(actor, (UUID) item.get("id")); value.put("agentConfigId", item.get("agent_id").toString()); return value; }).toList());
        return store.json.valueToTree(result);
    }

    @Transactional
    public JsonNode list(ActorContext actor, UUID project) {
        store.owner(actor, project, false);
        return store.json.valueToTree(store.db.queryForList("SELECT id AS \"publicationGroupId\", spec_version_id AS \"specVersionId\", created_at::text AS \"createdAt\" FROM publication_group WHERE project_id=? ORDER BY created_at DESC,id DESC LIMIT 20", project));
    }
}
