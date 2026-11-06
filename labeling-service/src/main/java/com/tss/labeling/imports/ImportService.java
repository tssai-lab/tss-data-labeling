package com.tss.labeling.imports;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tss.labeling.common.BusinessException;
import com.tss.labeling.identity.ActorService.ActorContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import org.springframework.core.io.Resource;
import org.springframework.core.io.FileSystemResource;

@Service
public class ImportService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final ImportSettings settings;
    private final ImportStorage storage;
    private final ArchiveParser parser;

    public ImportService(JdbcTemplate db, ObjectMapper json, ImportSettings settings, ImportStorage storage, ArchiveParser parser) {
        this.db = db;
        this.json = json;
        this.settings = settings;
        this.storage = storage;
        this.parser = parser;
    }

    @Transactional
    public BatchView create(ActorContext actor, String projectValue, ImportController.CreateImport body, String key) {
        UUID project = id(projectValue);
        String state = authorize(actor.actorId(), project);
        String operation = "POST:/projects/" + project + "/imports";
        String hash = digest(writeJson(body).getBytes(StandardCharsets.UTF_8));
        BatchView cached = replay(actor.actorId(), operation, key, hash);
        if (cached != null) return cached;
        writable(state);
        String task = db.queryForObject("SELECT task_type FROM project WHERE id = ?", String.class, project);
        if (!"IMAGE_CLASSIFICATION".equals(task)) throw failure(422, "UNSUPPORTED_FORMAT", "当前仅支持图片分类项目");
        if (!body.filename().toLowerCase(Locale.ROOT).endsWith(".zip")
                || body.filename().contains("/") || body.filename().contains("\\")) {
            throw failure(422, "UNSUPPORTED_FORMAT", "请上传 ZIP 图片压缩包");
        }
        if (body.sizeBytes() > settings.maxArchiveBytes()) throw failure(413, "UPLOAD_TOO_LARGE", "压缩包超过部署配置上限");
        UUID batch = UUID.randomUUID();
        db.update("""
                INSERT INTO import_batch(id, project_id, original_name, storage_key, size_bytes, chunk_size_bytes, sha256)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, batch, project, body.filename(), batch + "/archive.zip", body.sizeBytes(), settings.chunkSizeBytes(), body.sha256().toLowerCase(Locale.ROOT));
        BatchView response = view(batch);
        remember(actor.actorId(), operation, key, hash, batch, response);
        return response;
    }

    @Transactional
    public BatchView get(ActorContext actor, String value) {
        UUID batch = id(value);
        authorize(actor.actorId(), projectOf(batch));
        return view(batch);
    }

    @Transactional
    public long chunk(ActorContext actor, String value, long offset, String checksum, InputStream input) {
        UUID batch = id(value);
        writable(authorize(actor.actorId(), projectOf(batch)));
        lockBatch(batch);
        BatchView current = view(batch);
        if (!"UPLOADING".equals(current.status())) throw failure(409, "UPLOAD_CLOSED", "该批次已结束上传");
        if (offset < 0 || checksum == null || !checksum.matches("[a-fA-F0-9]{64}")) throw failure(400, "INVALID_REQUEST", "分片参数不合法");
        byte[] bytes;
        try { bytes = input.readNBytes(current.chunkSizeBytes() + 1); }
        catch (IOException exception) { throw failure(400, "UPLOAD_INTERRUPTED", "分片传输中断，请重试"); }
        if (bytes.length > current.chunkSizeBytes()) throw failure(413, "UPLOAD_TOO_LARGE", "分片超过服务器指定尺寸");
        String actual = digest(bytes);
        if (!actual.equalsIgnoreCase(checksum)) throw failure(422, "CHECKSUM_MISMATCH", "分片摘要不一致");
        if (offset < current.receivedBytes()) {
            Map<String, Object> previous = db.query("SELECT size_bytes, sha256 FROM import_chunk WHERE batch_id = ? AND byte_offset = ?",
                    result -> result.next() ? Map.of("size", result.getInt(1), "sha", result.getString(2)) : null, batch, offset);
            if (previous == null) throw failure(409, "OFFSET_MISMATCH", "分片 offset 不在已接收边界");
            if (!previous.get("size").equals(bytes.length) || !actual.equals(previous.get("sha"))) {
                throw failure(409, "UPLOAD_CONFLICT", "已上传分片与本次内容不一致");
            }
            return current.receivedBytes();
        }
        if (offset != current.receivedBytes()) throw failure(409, "OFFSET_MISMATCH", "分片 offset 与服务器进度不一致");
        long expected = Math.min(current.chunkSizeBytes(), current.sizeBytes() - offset);
        if (expected <= 0 || bytes.length != expected) throw failure(422, "CHUNK_SIZE_MISMATCH", "分片长度不符合服务器要求");
        try { storage.append(batch, offset, bytes); }
        catch (IOException exception) { throw failure(503, "STORAGE_UNAVAILABLE", "上传存储不可用，请检查服务端文件并重试"); }
        db.update("INSERT INTO import_chunk(batch_id, byte_offset, size_bytes, sha256) VALUES (?, ?, ?, ?)", batch, offset, bytes.length, actual);
        long received = offset + bytes.length;
        db.update("UPDATE import_batch SET received_bytes = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?", received, batch);
        return received;
    }

    private void lockBatch(UUID batch) {
        db.queryForObject("SELECT id FROM import_batch WHERE id = ? FOR UPDATE", UUID.class, batch);
    }

    @Transactional
    public BatchView finish(ActorContext actor, String value, String key, boolean retry) {
        UUID batch = id(value);
        String state = authorize(actor.actorId(), projectOf(batch));
        String operation = "POST:/imports/" + batch + (retry ? "/retry" : "/complete");
        String hash = digest(new byte[0]);
        BatchView cached = replay(actor.actorId(), operation, key, hash);
        if (cached != null) return cached;
        writable(state);
        lockBatch(batch);
        BatchView current = view(batch);
        if (retry && (!"FAILED".equals(current.status()) || !current.originalComplete())) {
            throw failure(409, "IMPORT_NOT_RETRYABLE", "只有原始文件完整的失败批次可以重试");
        }
        if (!retry && "FAILED".equals(current.status())) throw failure(409, "IMPORT_FAILED", "失败批次请使用 retry");
        if (retry || "UPLOADING".equals(current.status())) {
            verifyArchive(batch, current);
            db.update("""
                    UPDATE import_batch SET status = 'PROCESSING', original_complete = TRUE,
                    valid_sample_count = 0, error_count = 0, errors = '[]'::jsonb, error_code = NULL,
                    updated_at = CURRENT_TIMESTAMP WHERE id = ?
                    """, batch);
        }
        BatchView response = view(batch);
        remember(actor.actorId(), operation, key, hash, batch, response);
        return response;
    }

    private void verifyArchive(UUID batch, BatchView current) {
        if (current.receivedBytes() != current.sizeBytes()) throw failure(422, "UPLOAD_INCOMPLETE", "压缩包尚未上传完整");
        try {
            Path archive = storage.archive(batch);
            if (!Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS) || Files.size(archive) != current.sizeBytes()) {
                throw failure(422, "UPLOAD_INCOMPLETE", "原始文件长度与批次不一致");
            }
            String expected = db.queryForObject("SELECT sha256 FROM import_batch WHERE id = ?", String.class, batch);
            if (!ImportStorage.sha256(archive).equals(expected)) throw failure(422, "CHECKSUM_MISMATCH", "压缩包总摘要不一致，请重新创建批次上传");
        } catch (IOException exception) { throw failure(503, "STORAGE_UNAVAILABLE", "无法读取原始压缩包"); }
    }

    @Transactional
    public void processBatch(UUID batch) {
        String state = db.query("""
                SELECT p.status FROM project p JOIN import_batch b ON b.project_id = p.id
                WHERE b.id = ? FOR SHARE OF p
                """, result -> result.next() ? result.getString(1) : null, batch);
        if (!"DRAFT".equals(state) && !"OPEN".equals(state)) return;
        UUID locked = db.query("SELECT id FROM import_batch WHERE id = ? AND status = 'PROCESSING' FOR UPDATE SKIP LOCKED",
                result -> result.next() ? result.getObject(1, UUID.class) : null, batch);
        if (locked == null) return;
        ArchiveParser.ParsedArchive parsed;
        try {
            verifyArchive(batch, view(batch));
            parsed = parser.parse(batch);
        } catch (ArchiveParser.Rejected exception) {
            failed(batch, exception.code, exception.getMessage()); return;
        } catch (BusinessException exception) {
            failed(batch, exception.code(), exception.getMessage()); return;
        } catch (IOException exception) {
            failed(batch, "ARCHIVE_READ_FAILED", "压缩包损坏或存储读取失败"); return;
        }
        UUID project = projectOf(batch);
        for (var sample : parsed.samples()) {
            db.update("""
                    INSERT INTO sample(id, project_id, batch_id, source_path, storage_key, content_sha256, media_type, metadata)
                    VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb)) ON CONFLICT (batch_id, source_path) DO NOTHING
                    """, sample.id(), project, batch, sample.sourcePath(), sample.storageKey(), sample.sha256(), sample.mediaType(), writeJson(sample.metadata()));
        }
        db.update("""
                UPDATE import_batch SET status = ?, valid_sample_count = ?, error_count = ?, errors = CAST(? AS jsonb),
                error_code = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?
                """, parsed.samples().isEmpty() ? "FAILED" : "READY", parsed.samples().size(), parsed.errorCount(),
                writeJson(parsed.errors()), parsed.samples().isEmpty() ? "NO_VALID_SAMPLES" : null, batch);
    }

    private void failed(UUID batch, String code, String message) {
        db.update("""
                UPDATE import_batch SET status = 'FAILED', valid_sample_count = 0, error_count = 1,
                errors = CAST(? AS jsonb), error_code = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?
                """, writeJson(List.of(Map.of("sourcePath", "", "code", code, "message", message))), code, batch);
    }

    @Transactional
    public SamplePage samples(ActorContext actor, String value, int limit, String cursor) {
        UUID batch = id(value);
        authorize(actor.actorId(), projectOf(batch));
        if (limit < 1 || limit > 100) throw failure(400, "INVALID_REQUEST", "limit 应为 1 到 100");
        if (!"READY".equals(view(batch).status())) throw failure(409, "IMPORT_NOT_READY", "图片尚未解析完成");
        UUID after = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("/", -1);
                if (parts.length != 2 || !batch.toString().equals(parts[0])) throw new IllegalArgumentException();
                after = UUID.fromString(parts[1]);
            } catch (IllegalArgumentException exception) { throw failure(400, "INVALID_CURSOR", "分页游标无效或不属于该批次"); }
        }
        List<Object> args = new ArrayList<>(); args.add(batch);
        if (after != null) args.add(after);
        args.add(limit + 1);
        var rows = db.query("SELECT id, project_id, batch_id, source_path, media_type, content_sha256, metadata FROM sample WHERE batch_id = ?"
                        + (after == null ? "" : " AND id > ?") + " ORDER BY id LIMIT ?",
                (row, number) -> new SampleView(row.getObject("id").toString(), row.getObject("project_id").toString(),
                        row.getObject("batch_id").toString(), row.getString("source_path"), row.getString("media_type"),
                        row.getString("content_sha256"), readJson(row.getString("metadata")),
                        "/api/labeling/v1/projects/" + row.getObject("project_id") + "/samples/" + row.getObject("id") + "/file"), args.toArray());
        String next = null;
        if (rows.size() > limit) {
            rows.remove(rows.size() - 1);
            next = Base64.getUrlEncoder().withoutPadding().encodeToString((batch + "/" + rows.get(rows.size() - 1).sampleId()).getBytes(StandardCharsets.UTF_8));
        }
        return new SamplePage(rows, next);
    }

    @Transactional
    public SampleFile file(ActorContext actor, String projectValue, String sampleValue) {
        UUID project = id(projectValue), sample = id(sampleValue);
        String state = authorize(actor.actorId(), project);
        if ("PAUSED".equals(state)) throw failure(409, "PROJECT_PAUSED", "项目已暂停");
        SampleFileRow row = db.query("""
                SELECT s.batch_id, s.media_type, s.metadata FROM sample s JOIN import_batch b ON b.id = s.batch_id
                WHERE s.id = ? AND s.project_id = ? AND b.status = 'READY'
                """, result -> result.next() ? new SampleFileRow(result.getObject(1, UUID.class), result.getString(2), readJson(result.getString(3)).path("sizeBytes").asLong()) : null, sample, project);
        if (row == null) throw failure(404, "RESOURCE_NOT_FOUND", "请求的资源不存在");
        try {
            Path path = storage.sample(row.batch(), sample);
            if (Files.size(path) != row.size()) throw new IOException("文件长度不一致");
            return new SampleFile(new FileSystemResource(path), row.mediaType(), row.size());
        } catch (IOException exception) { throw failure(503, "STORAGE_UNAVAILABLE", "图片存储不可用"); }
    }

    public record SampleView(String sampleId, String projectId, String batchId, String sourcePath, String mediaType,
                             String contentSha256, JsonNode metadata, String fileUrl) { }
    public record SamplePage(List<SampleView> items, String nextCursor) { }
    public record SampleFile(Resource resource, String mediaType, long sizeBytes) { }
    private record SampleFileRow(UUID batch, String mediaType, long size) { }

    private UUID projectOf(UUID batch) {
        UUID project = db.query("SELECT project_id FROM import_batch WHERE id = ?",
                result -> result.next() ? result.getObject(1, UUID.class) : null, batch);
        if (project == null) throw failure(404, "RESOURCE_NOT_FOUND", "请求的资源不存在");
        return project;
    }

    private String authorize(UUID actor, UUID project) {
        String enabled = db.query("SELECT status FROM actor WHERE id = ? FOR SHARE",
                result -> result.next() ? result.getString(1) : null, actor);
        if (!"ENABLED".equals(enabled)) throw failure(403, "LABELING_DISABLED", "当前账号无法使用标注平台");
        String state = db.query("SELECT status FROM project WHERE id = ? AND owner_actor_id = ? FOR SHARE",
                result -> result.next() ? result.getString(1) : null, project, actor);
        if (state == null) throw failure(404, "RESOURCE_NOT_FOUND", "请求的资源不存在");
        return state;
    }

    private static void writable(String state) {
        if ("PAUSED".equals(state)) throw failure(409, "PROJECT_PAUSED", "项目已暂停");
        if ("ARCHIVED".equals(state)) throw failure(409, "PROJECT_ARCHIVED", "项目已归档");
    }

    private BatchView view(UUID batch) {
        return db.queryForObject("SELECT * FROM import_batch WHERE id = ?", (row, index) -> new BatchView(
                row.getObject("id").toString(), row.getObject("project_id").toString(), row.getString("status"),
                row.getLong("size_bytes"), row.getLong("received_bytes"), row.getInt("chunk_size_bytes"),
                row.getBoolean("original_complete"), row.getInt("valid_sample_count"), row.getInt("error_count"),
                readJson(row.getString("errors")), row.getString("error_code")), batch);
    }

    private BatchView replay(UUID actor, String operation, String key, String hash) {
        if (key == null || key.isBlank() || key.length() > 200) throw failure(400, "INVALID_REQUEST", "需要有效的 Idempotency-Key");
        // Project share locks allow concurrent POSTs; serialize the same operation/key transactionally.
        db.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class, actor + ":" + operation + ":" + key);
        return db.query("SELECT request_hash, response_body FROM idempotency_record WHERE actor_id = ? AND operation = ? AND idempotency_key = ?",
                result -> {
                    if (!result.next()) return null;
                    if (!hash.equals(result.getString(1))) throw failure(409, "IDEMPOTENCY_CONFLICT", "同一幂等键对应了不同请求");
                    try { return json.readValue(result.getString(2), BatchView.class); }
                    catch (JsonProcessingException exception) { throw new IllegalStateException(exception); }
                }, actor, operation, key);
    }

    private void remember(UUID actor, String operation, String key, String hash, UUID batch, BatchView response) {
        db.update("""
                INSERT INTO idempotency_record(actor_id, operation, idempotency_key, request_hash, resource_id, response_body)
                VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb))
                """, actor, operation, key, hash, batch, writeJson(response));
    }

    private String writeJson(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException(exception); }
    }

    private JsonNode readJson(String value) {
        try { return json.readTree(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException(exception); }
    }

    static UUID id(String value) {
        try {
            UUID id = UUID.fromString(value);
            if (!id.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
            return id;
        } catch (IllegalArgumentException exception) { throw failure(404, "RESOURCE_NOT_FOUND", "请求的资源不存在"); }
    }

    static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    static BusinessException failure(int status, String code, String message) {
        return new BusinessException(HttpStatus.valueOf(status), code, message);
    }

    public record BatchView(String batchId, String projectId, String status, long sizeBytes, long receivedBytes,
                            int chunkSizeBytes, boolean originalComplete, int validSampleCount, int errorCount,
                            JsonNode errors, String errorCode) { }
}
