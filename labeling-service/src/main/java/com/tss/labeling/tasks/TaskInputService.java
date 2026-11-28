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
    private final com.tss.labeling.plugins.TaskPluginRegistry plugins;
    private final com.tss.labeling.annotators.AnnotatorProviderRegistry annotators;

    public TaskInputService(WorkflowStore s, com.tss.labeling.plugins.TaskPluginRegistry plugins,
            com.tss.labeling.annotators.AnnotatorProviderRegistry annotators) {
        this.s = s; this.plugins = plugins; this.annotators = annotators;
    }

    @Transactional
    public JsonNode templates(ActorContext actor) {
        s.enabled(actor);
        return s.json.valueToTree(plugins.templates());
    }

    @Transactional
    public JsonNode annotatorProviders(ActorContext actor) {
        s.enabled(actor);
        return s.json.valueToTree(annotators.descriptors());
    }

    @Transactional
    public JsonNode owner(ActorContext actor, UUID project, UUID sample) {
        s.owner(actor, project, false);
        var binding=s.one("SELECT task_type,template_id,template_version FROM project WHERE id=?",project);
        return plugins.resolve((String)binding.get("task_type"),(String)binding.get("template_id"),(String)binding.get("template_version"))
                .readInput(project,sample,"/projects/"+project+"/samples/"+sample);
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
        var spec=s.one("SELECT v.* FROM assignment a JOIN publication_round r ON r.id=a.round_id JOIN spec_version v ON v.id=r.spec_version_id WHERE a.id=?",assignment);
        return plugins.frozen(spec).readInput((UUID)row.get("project_id"),sample,"/assignments/"+assignment+"/samples/"+sample);
    }

    public JsonNode data(UUID project, UUID sample) {
        var binding=s.one("SELECT task_type,template_id,template_version FROM project WHERE id=?",project);
        return plugins.resolve((String)binding.get("task_type"),(String)binding.get("template_id"),(String)binding.get("template_version"))
                .readInput(project,sample,"/projects/"+project+"/samples/"+sample);
    }
}
