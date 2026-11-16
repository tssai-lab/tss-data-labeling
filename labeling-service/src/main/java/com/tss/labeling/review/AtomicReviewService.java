package com.tss.labeling.review;

import static com.tss.labeling.common.WorkflowStore.error;

import com.fasterxml.jackson.databind.JsonNode;
import com.tss.labeling.common.WorkflowStore;
import com.tss.labeling.identity.ActorService.ActorContext;
import com.tss.labeling.tasks.TaskResultValidator;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class AtomicReviewService {
    private final WorkflowStore s;
    private final ResultScopeService scopes;
    private final TaskResultValidator validator;

    public AtomicReviewService(
            WorkflowStore s, ResultScopeService scopes, TaskResultValidator validator) {
        this.s = s;
        this.scopes = scopes;
        this.validator = validator;
    }

    public record ReviewInput(
            UUID specVersionId,
            UUID selectionId,
            String action,
            List<ResultScopeService.Item> items,
            String reason) {}

    public record RevisionInput(
            UUID specVersionId,
            UUID sampleId,
            Long baseReviewRevision,
            JsonNode result,
            String reason,
            String resultFormat) {}

    @Transactional
    public JsonNode review(ActorContext actor, UUID project, ReviewInput input, String key) {
        var projectState=s.owner(actor,project,true);
        String op = "POST:/projects/" + project + "/reviews";
        JsonNode previous = s.replay(actor, op, key, input);
        if (previous != null) return previous;
        WorkflowStore.requireMutable(projectState);
        if (input.action() == null || !Set.of("ACCEPT", "NEEDS_REVISION").contains(input.action()))
            throw error(422, "INVALID_REQUEST", "审核动作无效");
        if ("NEEDS_REVISION".equals(input.action())
                && (input.reason() == null || input.reason().isBlank()))
            throw error(422, "REASON_REQUIRED", "待修正需填写原因");
        if ((input.selectionId() == null) == (input.items() == null))
            throw error(422, "INVALID_REQUEST", "selectionId 与 items 必须二选一");
        var scope =
                input.selectionId() != null
                        ? scopes.selection(
                                actor, project, input.selectionId(), "REVIEW", input.action())
                        : new ResultScopeService.Scope(input.specVersionId(), input.items());
        if (input.specVersionId() != null && !input.specVersionId().equals(scope.specVersionId()))
            throw error(409, "SPEC_MISMATCH", "规范版本不一致");
        scopes.validate(
                project,
                scope.specVersionId(),
                scope.items(),
                "ACCEPT".equals(input.action()),
                true);
        for (var item : scope.items()) {
            boolean accept = "ACCEPT".equals(input.action());
            var c =
                    accept
                            ? scopes.candidate(
                                    project,
                                    scope.specVersionId(),
                                    item.sampleId(),
                                    item.resultId())
                            : null;
            s.db.update(
                    """
INSERT INTO sample_review(project_id,sample_id,spec_version_id,status,source_kind,submission_id,execution_id,result_id,reviewed_by,reason,revision)
VALUES (?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(project_id,sample_id,spec_version_id) DO UPDATE SET
status=EXCLUDED.status,source_kind=EXCLUDED.source_kind,submission_id=EXCLUDED.submission_id,execution_id=EXCLUDED.execution_id,
result_id=EXCLUDED.result_id,reviewed_by=EXCLUDED.reviewed_by,reason=EXCLUDED.reason,revision=EXCLUDED.revision,updated_at=CURRENT_TIMESTAMP
""",
                    project,
                    item.sampleId(),
                    scope.specVersionId(),
                    accept ? "ACCEPTED" : "NEEDS_REVISION",
                    accept ? c.get("source_kind") : null,
                    accept && "HUMAN".equals(c.get("source_kind")) ? c.get("submission_id") : null,
                    accept && "AI".equals(c.get("source_kind")) ? c.get("execution_id") : null,
                    accept ? item.resultId() : null,
                    actor.actorId(),
                    input.reason(),
                    item.baseReviewRevision() + 1);
            s.db.update(
                    """
INSERT INTO review_event(id,project_id,sample_id,spec_version_id,actor_id,action,result_id,reason,before_revision,after_revision)
VALUES (?,?,?,?,?,?,?,?,?,?)
""",
                    UUID.randomUUID(),
                    project,
                    item.sampleId(),
                    scope.specVersionId(),
                    actor.actorId(),
                    input.action(),
                    item.resultId(),
                    input.reason(),
                    item.baseReviewRevision(),
                    item.baseReviewRevision() + 1);
        }
        return s.remember(
                actor,
                op,
                key,
                input,
                project,
                Map.of("projectId", project, "reviewedCount", scope.items().size()));
    }

    @Transactional
    public JsonNode revise(
            ActorContext actor, UUID project, UUID parent, RevisionInput input, String key) {
        var projectState=s.owner(actor,project,true);
        String op = "POST:/projects/" + project + "/results/" + parent + "/revisions";
        JsonNode previous = s.replay(actor, op, key, input);
        if (previous != null) return previous;
        WorkflowStore.requireMutable(projectState);
        if (input.reason() == null || input.reason().isBlank())
            throw error(422, "REASON_REQUIRED", "修订需填写原因");
        scopes.validate(
                project,
                input.specVersionId(),
                List.of(
                        new ResultScopeService.Item(
                                input.sampleId(), parent, input.baseReviewRevision())),
                true,
                true);
        validator.validate(
                input.specVersionId(),
                input.sampleId(),
                input.resultFormat(),
                input.result(),
                true);
        UUID id = UUID.randomUUID();
        s.db.update(
                "INSERT INTO"
                    + " owner_result(id,project_id,spec_version_id,sample_id,parent_result_id,source_kind,created_by,result,reason)"
                    + " VALUES (?,?,?,?,?,'OWNER',?,?::jsonb,?)",
                id,
                project,
                input.specVersionId(),
                input.sampleId(),
                parent,
                actor.actorId(),
                s.write(input.result()),
                input.reason());
        // A revision is a candidate, never silently an accepted answer. Invalidate the current
        // approval.
        s.db.update(
                """
INSERT INTO sample_review(project_id,sample_id,spec_version_id,status,reviewed_by,reason,revision) VALUES (?,?,?,'PENDING',?,?,?)
ON CONFLICT(project_id,sample_id,spec_version_id) DO UPDATE SET status='PENDING',source_kind=NULL,submission_id=NULL,execution_id=NULL,result_id=NULL,
reviewed_by=EXCLUDED.reviewed_by,reason=EXCLUDED.reason,revision=EXCLUDED.revision,updated_at=CURRENT_TIMESTAMP
""",
                project,
                input.sampleId(),
                input.specVersionId(),
                actor.actorId(),
                input.reason(),
                input.baseReviewRevision() + 1);
        s.db.update(
                "INSERT INTO"
                    + " review_event(id,project_id,sample_id,spec_version_id,actor_id,action,result_id,reason,before_revision,after_revision)"
                    + " VALUES (?,?,?,?,?,'REVISED',?,?,?,?)",
                UUID.randomUUID(),
                project,
                input.sampleId(),
                input.specVersionId(),
                actor.actorId(),
                id,
                input.reason(),
                input.baseReviewRevision(),
                input.baseReviewRevision() + 1);
        return s.remember(
                actor,
                op,
                key,
                input,
                id,
                Map.of(
                        "resultId",
                        id,
                        "parentResultId",
                        parent,
                        "sourceKind",
                        "OWNER",
                        "reviewRevision",
                        input.baseReviewRevision() + 1));
    }
}
