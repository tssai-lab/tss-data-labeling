package com.tss.labeling.tasks;

import static com.tss.labeling.common.WorkflowStore.error;

import com.fasterxml.jackson.databind.JsonNode;
import com.tss.labeling.common.WorkflowStore;
import com.tss.labeling.identity.ActorService.ActorContext;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class TaskInputService {
    private final WorkflowStore s;

    public TaskInputService(WorkflowStore s) {
        this.s = s;
    }

    @Transactional
    public JsonNode templates(ActorContext actor) {
        s.enabled(actor);
        return s.json.valueToTree(
                List.of(
                        TaskTemplates.forType("IMAGE_CLASSIFICATION"),
                        TaskTemplates.forType("TIMESERIES_ANNOTATION")));
    }

    @Transactional
    public JsonNode owner(ActorContext actor, UUID project, UUID sample) {
        s.owner(actor, project, false);
        return data(project, sample);
    }

    @Transactional
    public JsonNode assignment(ActorContext actor, UUID assignment, UUID sample) {
        s.enabled(actor);
        var row =
                s.one(
                        """
SELECT r.project_id,p.status AS project_status,a.status FROM assignment a
JOIN publication_round r ON r.id=a.round_id JOIN project p ON p.id=r.project_id
JOIN round_item i ON i.round_id=r.id AND i.sample_id=?
WHERE a.id=? AND a.actor_id=? AND p.deleted_at IS NULL
""",
                        sample,
                        assignment,
                        actor.actorId());
        if (!Set.of("CLAIMED", "IN_PROGRESS", "SUBMITTED").contains(row.get("status")))
            throw error(409, "ASSIGNMENT_CLOSED", "领取已结束");
        if (!"SUBMITTED".equals(row.get("status")) && !"OPEN".equals(row.get("project_status")))
            throw error(409, "PROJECT_CLOSED", "项目当前不可标注");
        return data((UUID) row.get("project_id"), sample);
    }

    public JsonNode data(UUID project, UUID sample) {
        var row =
                s.one(
                        "SELECT input_payload::text AS input FROM sample_input WHERE sample_id=?"
                            + " AND project_id=?",
                        sample,
                        project);
        return s.read((String) row.get("input"));
    }
}
