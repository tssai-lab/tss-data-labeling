package com.tss.labeling.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tss.labeling.identity.ActorService.ActorContext;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Shared persistence primitives. Mutations are called inside a service transaction. */
@Component
public class WorkflowStore {
    public final JdbcTemplate db;
    public final ObjectMapper json;

    public WorkflowStore(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    public Map<String, Object> one(String sql, Object... args) {
        var rows = db.queryForList(sql, args);
        if (rows.isEmpty()) throw error(404, "RESOURCE_NOT_FOUND", "资源不存在");
        return rows.get(0);
    }

    public void enabled(ActorContext actor) {
        if (!"ENABLED"
                .equals(
                        one("SELECT status FROM actor WHERE id=? FOR SHARE", actor.actorId())
                                .get("status"))) throw error(403, "LABELING_DISABLED", "标注账号已停用");
    }

    public Map<String, Object> owner(ActorContext actor, UUID project, boolean lock) {
        enabled(actor);
        return one(
                "SELECT * FROM project WHERE id=? AND owner_actor_id=? AND deleted_at IS NULL"
                        + (lock ? " FOR UPDATE" : ""),
                project,
                actor.actorId());
    }

    public static void requireMutable(Map<String,Object> row){
        if("ARCHIVED".equals(row.get("status")))throw error(409,"PROJECT_ARCHIVED","归档项目不可修改结果");
    }

    public JsonNode replay(ActorContext actor, String operation, String key, Object request) {
        if (key == null || key.isBlank() || key.length() > 200)
            throw error(400, "INVALID_REQUEST", "需要有效幂等键");
        db.queryForObject(
                "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
                Object.class,
                actor.actorId() + ":" + operation + ":" + key);
        return db.query(
                "SELECT request_hash,response_body::text FROM idempotency_record WHERE actor_id=?"
                    + " AND operation=? AND idempotency_key=?",
                rs -> {
                    if (!rs.next()) return null;
                    if (!hash(request).equals(rs.getString(1)))
                        throw error(409, "IDEMPOTENCY_CONFLICT", "同一幂等键请求内容不同");
                    return read(rs.getString(2));
                },
                actor.actorId(),
                operation,
                key);
    }

    public JsonNode remember(
            ActorContext actor,
            String operation,
            String key,
            Object request,
            UUID resource,
            Object response) {
        JsonNode value = json.valueToTree(response);
        db.update(
                "INSERT INTO"
                    + " idempotency_record(actor_id,operation,idempotency_key,request_hash,resource_id,response_body)"
                    + " VALUES (?,?,?,?,?,?::jsonb)",
                actor.actorId(),
                operation,
                key,
                hash(request),
                resource,
                write(value));
        return value;
    }

    public JsonNode page(List<Map<String, Object>> rows, int limit, String field) {
        var items = rows.subList(0, Math.min(limit, rows.size()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put(
                "nextCursor",
                rows.size() > limit ? items.get(limit - 1).get(field).toString() : null);
        return json.valueToTree(out);
    }

    public UUID cursor(int limit, String value) {
        if (limit < 1 || limit > 100) throw error(400, "INVALID_REQUEST", "limit 应为 1 至 100");
        try {
            return value == null ? new UUID(0, 0) : UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw error(400, "INVALID_CURSOR", "分页游标无效");
        }
    }

    public String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public JsonNode read(String value) {
        try {
            return json.readTree(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public String hash(Object value) {
        return digest(write(value).getBytes(StandardCharsets.UTF_8));
    }

    public static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static BusinessException error(int status, String code, String message) {
        return new BusinessException(HttpStatus.valueOf(status), code, message);
    }
}
