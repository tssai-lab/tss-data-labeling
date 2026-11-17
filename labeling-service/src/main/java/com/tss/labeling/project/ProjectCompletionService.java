package com.tss.labeling.project;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tss.labeling.identity.ActorService.ActorContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static com.tss.labeling.tasks.TaskService.error;

@Service
public class ProjectCompletionService {
    private final JdbcTemplate db;private final ObjectMapper json;
    public ProjectCompletionService(JdbcTemplate db,ObjectMapper json){this.db=db;this.json=json;}
    @Transactional
    public JsonNode complete(ActorContext actor,UUID project,UUID snapshot,long baseRevision){
        if(!"ENABLED".equals(one("SELECT status FROM actor WHERE id=? FOR SHARE",actor.actorId()).get("status")))throw error(403,"LABELING_DISABLED","账号已停用");
        var row=one("SELECT * FROM project WHERE id=? AND owner_actor_id=? FOR UPDATE",project,actor.actorId());
        if("COMPLETED".equals(row.get("status")) && snapshot.equals(row.get("completed_snapshot_id")))return view(row,snapshot);
        if(!List.of("DRAFT","OPEN").contains(row.get("status")))throw error(409,"PROJECT_CLOSED","项目当前不可完成");
        if(((Number)row.get("revision")).longValue()!=baseRevision)throw error(409,"REVISION_CONFLICT","项目已变化，请刷新后重试");
        one("SELECT id FROM final_snapshot WHERE id=? AND project_id=?",snapshot,project);
        if(Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM publication_round WHERE project_id=? AND status='OPEN')",Boolean.class,project)))
            throw error(409,"ACTIVE_ROUNDS","仍有开放轮次，请先提交或取消");
        if(Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM ai_execution WHERE project_id=? AND status NOT IN ('SUCCEEDED','PARTIAL','FAILED','CANCELLED'))",Boolean.class,project)))
            throw error(409,"ACTIVE_AI","仍有 AI 执行未结束");
        Long count=db.queryForObject("SELECT count(*) FROM final_snapshot_item WHERE snapshot_id=?",Long.class,snapshot);
        if(count==null || count==0)throw error(422,"EMPTY_SNAPSHOT","成果不能为空");
        boolean stale=Boolean.TRUE.equals(db.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM final_snapshot_item f LEFT JOIN sample_review r
                  ON r.project_id=f.project_id AND r.sample_id=f.sample_id AND r.spec_version_id=f.spec_version_id
                  WHERE f.snapshot_id=? AND (r.status IS DISTINCT FROM 'ACCEPTED' OR r.revision IS DISTINCT FROM f.review_revision))
                """,Boolean.class,snapshot));
        if(stale)throw error(409,"SNAPSHOT_STALE","审核结论已变化，请重新固化成果");
        boolean omitted=Boolean.TRUE.equals(db.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM package_item i JOIN task_package t ON t.id=i.package_id
                  JOIN package_plan p ON p.id=t.plan_id WHERE p.project_id=? AND p.status='PUBLISHED'
                  AND NOT EXISTS(SELECT 1 FROM final_snapshot_item f WHERE f.snapshot_id=? AND f.sample_id=i.sample_id))
                """,Boolean.class,project,snapshot));
        if(omitted)throw error(409,"INCOMPLETE_OUTCOME","已发布的样本尚未全部进入确认成果");
        db.update("UPDATE project SET status='COMPLETED',completed_snapshot_id=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",snapshot,project);
        row.put("status","COMPLETED");row.put("revision",baseRevision+1);return view(row,snapshot);
    }
    private JsonNode view(Map<String,Object> row,UUID snapshot){return json.valueToTree(Map.of("projectId",row.get("id"),"status",row.get("status"),"revision",row.get("revision"),"finalSnapshotId",snapshot));}
    private Map<String,Object> one(String sql,Object...args){var rows=db.queryForList(sql,args);if(rows.isEmpty())throw error(404,"RESOURCE_NOT_FOUND","请求资源不存在");return rows.get(0);}
}
