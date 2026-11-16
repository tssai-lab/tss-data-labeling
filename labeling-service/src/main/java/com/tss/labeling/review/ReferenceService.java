package com.tss.labeling.review;

import static com.tss.labeling.common.WorkflowStore.error;

import com.fasterxml.jackson.databind.JsonNode;
import com.tss.labeling.common.WorkflowStore;
import com.tss.labeling.tasks.TaskResultValidator;

import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class ReferenceService {
    private final WorkflowStore s;
    private final TaskResultValidator validator;

    public ReferenceService(WorkflowStore s, TaskResultValidator validator) {
        this.s = s;
        this.validator = validator;
    }

    public JsonNode candidate(UUID project, UUID spec, UUID sample, UUID result) {
        var rows =
                s.db.queryForList(
                        "SELECT source_kind,result::text AS result FROM project_result_candidate"
                            + " WHERE project_id=? AND spec_version_id=? AND sample_id=? AND"
                            + " result_id=?",
                        project,
                        spec,
                        sample,
                        result);
        if (rows.isEmpty()) throw error(422, "INVALID_REFERENCE", "参考答案不属于样本、项目或规范");
        return s.json.valueToTree(
                Map.of(
                        "resultId",
                        result,
                        "resultFormat",
                        validator.format(spec),
                        "result",
                        s.read((String) rows.get(0).get("result")),
                        "sourceKind",
                        rows.get(0).get("source_kind")));
    }
}
