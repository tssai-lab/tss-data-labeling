package com.tss.labeling.review;

import static com.tss.labeling.common.WorkflowStore.error;

import com.tss.labeling.common.WorkflowStore;
import com.tss.labeling.identity.ActorService.ActorContext;

import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Exact-answer/CAS validation shared by review, dispatch and finalization. Project must be locked
 * first.
 */
@Service
public class ResultScopeService {
    private final WorkflowStore s;

    public ResultScopeService(WorkflowStore s) {
        this.s = s;
    }

    public record Item(UUID sampleId, UUID resultId, Long baseReviewRevision) {}

    public record Scope(UUID specVersionId, List<Item> items) {}

    public Scope selection(
            ActorContext actor, UUID project, UUID id, String purpose, String action) {
        var row =
                s.one(
                        "SELECT *,expires_at<=CURRENT_TIMESTAMP AS expired FROM result_selection"
                            + " WHERE id=? AND project_id=? AND created_by=?",
                        id,
                        project,
                        actor.actorId());
        if (Boolean.TRUE.equals(row.get("expired")))
            throw error(410, "SELECTION_EXPIRED", "选择范围已过期");
        if (!purpose.equals(row.get("purpose"))
                || !Objects.equals(action, row.get("review_action")))
            throw error(409, "SELECTION_PURPOSE_MISMATCH", "选择用途或审核动作不一致");
        var items =
                s.db.query(
                        "SELECT sample_id,result_id,review_revision FROM result_selection_item"
                            + " WHERE selection_id=? ORDER BY ordinal",
                        (r, n) ->
                                new Item(
                                        r.getObject(1, UUID.class),
                                        r.getObject(2, UUID.class),
                                        r.getLong(3)),
                        id);
        return new Scope((UUID) row.get("spec_version_id"), items);
    }

    public void validate(
            UUID project, UUID spec, List<Item> items, boolean requireResult, boolean idle) {
        s.one("SELECT id FROM spec_version WHERE id=? AND project_id=?", spec, project);
        if (items == null || items.isEmpty() || items.size() > 10000)
            throw error(422, "INVALID_REQUEST", "范围必须包含 1 至 10000 个样本");
        Set<UUID> seen = new HashSet<>();
        for (var item : items) {
            if (item == null
                    || item.sampleId() == null
                    || item.baseReviewRevision() == null
                    || item.baseReviewRevision() < 0
                    || !seen.add(item.sampleId()))
                throw error(422, "INVALID_REQUEST", "样本、版本不能为空或重复");
            s.one("SELECT id FROM sample WHERE id=? AND project_id=?", item.sampleId(), project);
            Long current =
                    s.db.query(
                            "SELECT revision FROM sample_review WHERE project_id=? AND sample_id=?"
                                + " AND spec_version_id=? FOR UPDATE",
                            r -> r.next() ? r.getLong(1) : 0L,
                            project,
                            item.sampleId(),
                            spec);
            if (!current.equals(item.baseReviewRevision()))
                throw error(409, "REVISION_CONFLICT", "审核版本已变化，请重新预览整个范围");
            if (requireResult && item.resultId() == null)
                throw error(422, "RESULT_SELECTION_REQUIRED", "请明确选择标注结果");
            if (item.resultId() != null) candidate(project, spec, item.sampleId(), item.resultId());
            if (idle
                    && Boolean.TRUE.equals(
                            s.db.queryForObject(
                                    "SELECT EXISTS(SELECT 1 FROM sample_work_claim WHERE"
                                        + " project_id=? AND sample_id=?)",
                                    Boolean.class,
                                    project,
                                    item.sampleId()))) throw error(409, "SAMPLE_BUSY", "所选样本仍在处理中");
        }
    }

    public void checkReopen(UUID project, UUID spec, Collection<UUID> samples, Boolean reopen) {
        if (!Boolean.FALSE.equals(reopen)) return;
        var accepted =
                s.db.query(
                        "SELECT sample_id FROM sample_review WHERE project_id=? AND"
                            + " spec_version_id=? AND status='ACCEPTED'",
                        (r, n) -> r.getObject(1, UUID.class),
                        project,
                        spec);
        if (accepted.stream().anyMatch(samples::contains))
            throw error(409, "ACCEPTED_REQUIRES_REOPEN", "范围含已确认样本，请明确允许重新处理");
    }

    public Map<String, Object> candidate(UUID project, UUID spec, UUID sample, UUID result) {
        var rows =
                s.db.queryForList(
                        "SELECT *,result::text AS content FROM project_result_candidate WHERE"
                            + " project_id=? AND spec_version_id=? AND sample_id=? AND result_id=?",
                        project,
                        spec,
                        sample,
                        result);
        if (rows.isEmpty()) throw error(422, "RESULT_NOT_REVIEWABLE", "答案不属于该项目、规范或样本");
        var row = rows.get(0);
        if (!Boolean.TRUE.equals(row.get("current_version")))
            throw error(409, "RESULT_VERSION_CHANGED", "结果已修订，请重新选择");
        return row;
    }
}
