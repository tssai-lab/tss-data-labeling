package com.tss.labeling.imports;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class ImportIntegrationTest {
    static final String API = "/api/labeling/v1";
    static final EmbeddedPostgres PG;
    static final HttpServer IDENTITY;
    static final String STORAGE;
    static {
        try {
            PG = EmbeddedPostgres.builder().start();
            try (var connection = PG.getPostgresDatabase().getConnection(); var statement = connection.createStatement()) {
                statement.execute("CREATE ROLE tss_labeling_app LOGIN");
            }
            STORAGE = Files.createTempDirectory("labeling-import-test-").toString();
            IDENTITY = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            IDENTITY.createContext("/api/user/current-user", exchange -> {
                String auth = exchange.getRequestHeaders().getFirst("Authorization");
                String user = auth != null && auth.startsWith("Bearer ") ? auth.substring(7) : "other";
                byte[] json = ("{\"code\":200,\"data\":{\"id\":\"" + user + "\",\"username\":\"" + user + "\"}}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, json.length);
                try (var output = exchange.getResponseBody()) { output.write(json); }
            });
            IDENTITY.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                IDENTITY.stop(0);
                try { PG.close(); } catch (Exception ignored) { }
            }));
        } catch (Exception exception) { throw new ExceptionInInitializerError(exception); }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> "jdbc:postgresql://127.0.0.1:" + PG.getPort() + "/postgres?currentSchema=labeling");
        properties.add("spring.datasource.username", () -> "tss_labeling_app");
        properties.add("spring.datasource.password", () -> "");
        properties.add("spring.flyway.user", () -> "postgres");
        properties.add("spring.flyway.password", () -> "");
        properties.add("labeling.identity.base-url", () -> "http://127.0.0.1:" + IDENTITY.getAddress().getPort());
        properties.add("labeling.imports.storage-root", () -> STORAGE);
        properties.add("labeling.imports.chunk-size-bytes", () -> 256);
        properties.add("labeling.imports.worker-enabled", () -> false);
        properties.add("labeling.exports.worker-enabled", () -> false);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired ApplicationContext context;
    @org.springframework.boot.test.web.server.LocalServerPort int port;

    static byte[] image(String format) throws Exception {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(8, 6, BufferedImage.TYPE_INT_RGB), format, out);
        return out.toByteArray();
    }

    static byte[] zip(Map<String, byte[]> files) throws Exception {
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (var file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey())); zip.write(file.getValue()); zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    String uploaded(String project, byte[] bytes) throws Exception {
        String batch = batch(project, bytes);
        for (int offset = 0; offset < bytes.length; offset += 256) {
            chunk(batch, offset, Arrays.copyOfRange(bytes, offset, Math.min(offset + 256, bytes.length))).andExpect(status().isOk());
        }
        return batch;
    }

    org.springframework.test.web.servlet.ResultActions command(String batch, String action, String key) throws Exception {
        return mvc.perform(post(API + "/imports/" + batch + "/" + action)
                .header("Authorization", "Bearer owner").header("Idempotency-Key", key));
    }

    void processQueue() throws Exception {
        context.getBean(ImportWorker.class).runOnce();
    }

    JsonNode statusOf(String batch) throws Exception {
        return json.readTree(mvc.perform(get(API + "/imports/" + batch).header("Authorization", "Bearer owner"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }

    static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    String batch(String project, byte[] bytes) throws Exception {
        String response = mvc.perform(post(API + "/projects/" + project + "/imports")
                .header("Authorization", "Bearer owner").header("Idempotency-Key", UUID.randomUUID())
                .contentType("application/json").content(json.writeValueAsString(Map.of(
                        "filename", "images.zip", "sizeBytes", bytes.length, "sha256", sha(bytes)))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).path("data").path("batchId").asText();
    }

    org.springframework.test.web.servlet.ResultActions chunk(String batch, long offset, byte[] bytes) throws Exception {
        return mvc.perform(put(API + "/imports/" + batch + "/chunks").param("offset", "" + offset)
                .header("Authorization", "Bearer owner").header("X-Chunk-SHA256", sha(bytes))
                .contentType("application/octet-stream").content(bytes));
    }

    String project() throws Exception {
        String response = mvc.perform(post(API + "/projects").header("Authorization", "Bearer owner")
                .header("Idempotency-Key", UUID.randomUUID()).contentType("application/json")
                .content("{\"name\":\"图片导入测试\",\"taskType\":\"IMAGE_CLASSIFICATION\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).path("data").path("projectId").asText();
    }

    @Test void createsPersistentBatchAndReplaysOriginalCreation() throws Exception {
        String project = project();
        String key = UUID.randomUUID().toString();
        String body = json.writeValueAsString(Map.of("filename", "images.zip", "sizeBytes", 300, "sha256", "a".repeat(64)));
        String response = mvc.perform(post(API + "/projects/" + project + "/imports")
                .header("Authorization", "Bearer owner").header("Idempotency-Key", key)
                .contentType("application/json").content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.status").value("UPLOADING"))
                .andExpect(jsonPath("$.data.chunkSizeBytes").value(256))
                .andReturn().getResponse().getContentAsString();
        JsonNode original = json.readTree(response).path("data");
        mvc.perform(post(API + "/projects/" + project + "/imports")
                .header("Authorization", "Bearer owner").header("Idempotency-Key", key)
                .contentType("application/json").content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data").value(json.convertValue(original, Map.class)));
        mvc.perform(get(API + "/imports/" + original.path("batchId").asText()).header("Authorization", "Bearer owner"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.receivedBytes").value(0));
    }

    @Test void appendsSequentialChunksAndDeduplicatesReplays() throws Exception {
        byte[] bytes = new byte[300];
        Arrays.fill(bytes, (byte) 17);
        String batch = batch(project(), bytes);
        byte[] first = Arrays.copyOf(bytes, 256);
        chunk(batch, 0, first).andExpect(status().isOk()).andExpect(jsonPath("$.data.receivedBytes").value(256));
        chunk(batch, 0, first).andExpect(status().isOk()).andExpect(jsonPath("$.data.receivedBytes").value(256));
        byte[] conflicting = first.clone(); conflicting[0] = 20;
        chunk(batch, 0, conflicting).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("UPLOAD_CONFLICT"));
        chunk(batch, 299, new byte[]{17}).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("OFFSET_MISMATCH"));
        chunk(batch, 256, Arrays.copyOfRange(bytes, 256, 300)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.receivedBytes").value(300));
        chunk(batch, 0, first).andExpect(status().isOk()).andExpect(jsonPath("$.data.receivedBytes").value(300));
        assertArrayEquals(bytes, Files.readAllBytes(java.nio.file.Path.of(STORAGE, batch, "archive.zip")));
        assertEquals(2, db.queryForObject("SELECT count(*) FROM import_chunk WHERE batch_id = ?", Integer.class, UUID.fromString(batch)));
    }

    @Test void rejectsBadChunkDigestAndLengthWithoutChangingProgress() throws Exception {
        String batch = batch(project(), new byte[300]);
        mvc.perform(put(API + "/imports/" + batch + "/chunks?offset=0").header("Authorization", "Bearer owner")
                .header("X-Chunk-SHA256", "a".repeat(64)).contentType("application/octet-stream").content(new byte[256]))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error.code").value("CHECKSUM_MISMATCH"));
        chunk(batch, 0, new byte[255]).andExpect(status().isUnprocessableEntity());
        chunk(batch, 0, new byte[257]).andExpect(status().isPayloadTooLarge());
        mvc.perform(get(API + "/imports/" + batch).header("Authorization", "Bearer owner"))
                .andExpect(jsonPath("$.data.receivedBytes").value(0));
    }

    @Test void truncatesUncommittedDiskTailBeforeResumingUpload() throws Exception {
        byte[] bytes = new byte[300];
        String batch = batch(project(), bytes);
        chunk(batch, 0, new byte[256]).andExpect(status().isOk());
        Files.write(java.nio.file.Path.of(STORAGE, batch, "archive.zip"), new byte[10], java.nio.file.StandardOpenOption.APPEND);
        chunk(batch, 256, new byte[44]).andExpect(status().isOk());
        assertEquals(300, Files.size(java.nio.file.Path.of(STORAGE, batch, "archive.zip")));
    }

    @Test void hidesOtherOwnersImportsAndRequiresLogin() throws Exception {
        String project = project();
        String batch = batch(project, new byte[300]);
        mvc.perform(get(API + "/imports/" + batch)).andExpect(status().isUnauthorized());
        mvc.perform(get(API + "/imports/" + batch).header("Authorization", "Bearer other"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        mvc.perform(put(API + "/imports/" + batch + "/chunks?offset=0").header("Authorization", "Bearer other")
                .header("X-Chunk-SHA256", sha(new byte[256])).contentType("application/octet-stream").content(new byte[256]))
                .andExpect(status().isNotFound());
    }

    @Test void parsesRealImagesPagesSamplesAndAuthorizesOriginalFiles() throws Exception {
        String project = project();
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("分类/正常.png", image("png")); files.put("other/photo.jpg", image("jpg"));
        files.put("broken.png", new byte[]{1,2,3}); files.put("readme.txt", new byte[]{4,5});
        String batch = uploaded(project, zip(files));
        String key = UUID.randomUUID().toString();
        command(batch, "complete", key).andExpect(status().isAccepted()).andExpect(jsonPath("$.data.status").value("PROCESSING"));
        processQueue();
        JsonNode state = statusOf(batch);
        assertEquals("READY", state.path("status").asText());
        assertEquals(2, state.path("validSampleCount").asInt());
        assertEquals(2, state.path("errorCount").asInt());
        assertEquals(2, state.path("errors").size());
        assertFalse(state.toString().contains("storageKey"));
        command(batch, "complete", key).andExpect(status().isAccepted()).andExpect(jsonPath("$.data.status").value("PROCESSING"));
        String cursor = null;
        var paths = new java.util.HashSet<String>();
        do {
            var request = get(API + "/imports/" + batch + "/samples").param("limit", "1").header("Authorization", "Bearer owner");
            if (cursor != null) request.param("cursor", cursor);
            JsonNode page = json.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
            assertEquals(1, page.path("items").size());
            JsonNode sample = page.path("items").get(0);
            assertTrue(paths.add(sample.path("sourcePath").asText()));
            assertEquals(8, sample.path("metadata").path("width").asInt());
            String route = sample.path("fileUrl").asText();
            mvc.perform(get(route).header("Authorization", "Bearer owner")).andExpect(status().isOk())
                    .andExpect(content().contentType(sample.path("mediaType").asText()))
                    .andExpect(content().bytes(files.get(sample.path("sourcePath").asText())))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"));
            mvc.perform(get(route).header("Authorization", "Bearer other")).andExpect(status().isNotFound());
            mvc.perform(get(API + "/projects/" + project() + "/samples/" + sample.path("sampleId").asText() + "/file")
                    .header("Authorization", "Bearer owner")).andExpect(status().isNotFound());
            cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asText();
        } while (cursor != null);
        assertEquals(java.util.Set.of("分类/正常.png", "other/photo.jpg"), paths);
        mvc.perform(get(API + "/imports/" + batch + "/samples").header("Authorization", "Bearer other")).andExpect(status().isNotFound());
        chunk(batch, 0, new byte[256]).andExpect(status().isConflict());
    }

    @Test void validatesWholeArchiveBeforeScheduling() throws Exception {
        String incomplete = batch(project(), new byte[300]);
        command(incomplete, "complete", "incomplete").andExpect(status().isUnprocessableEntity());
        assertEquals("UPLOADING", statusOf(incomplete).path("status").asText());
        String batch = batch(project(), new byte[256]);
        byte[] wrong = new byte[256]; wrong[0] = 1;
        chunk(batch, 0, wrong).andExpect(status().isOk());
        command(batch, "complete", "checksum").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("CHECKSUM_MISMATCH"));
    }

    @Test void rejectsTraversalAndDoesNotPublishPartialSamples() throws Exception {
        String project = project();
        String batch = uploaded(project, zip(Map.of("good.png", image("png"), "../escape.png", image("png"))));
        command(batch, "complete", "traversal").andExpect(status().isAccepted());
        processQueue();
        assertEquals("FAILED", statusOf(batch).path("status").asText());
        assertEquals("UNSAFE_ARCHIVE", statusOf(batch).path("errorCode").asText());
        assertEquals(0, db.queryForObject("SELECT count(*) FROM sample WHERE batch_id = ?", Integer.class, UUID.fromString(batch)));
        assertFalse(Files.exists(java.nio.file.Path.of(STORAGE, "escape.png")));
        command(batch, "retry", "retry-traversal").andExpect(status().isAccepted());
        processQueue();
        assertEquals("FAILED", statusOf(batch).path("status").asText());
    }

    @Test void reprocessesDurableQueueWithoutDuplicatingStableSamples() throws Exception {
        String project = project();
        String batch = uploaded(project, zip(Map.of("one.png", image("png"))));
        command(batch, "complete", "complete-stable").andExpect(status().isAccepted());
        processQueue();
        UUID sample = db.queryForObject("SELECT id FROM sample WHERE batch_id = ?", UUID.class, UUID.fromString(batch));
        db.update(
                "UPDATE import_batch SET status = 'FAILED', error_code = 'TEST_RECOVERY' WHERE id ="
                    + " ?", UUID.fromString(batch));
        command(batch, "retry", "retry-stable").andExpect(status().isAccepted());
        processQueue();
        assertEquals("READY", statusOf(batch).path("status").asText());
        assertEquals(sample, db.queryForObject("SELECT id FROM sample WHERE batch_id = ?", UUID.class, UUID.fromString(batch)));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM sample WHERE batch_id = ?", Integer.class, UUID.fromString(batch)));
    }

    @Test void rejectsUnexpectedCompleteBodyInsteadOfIgnoringIdempotencyContent() throws Exception {
        String batch = uploaded(project(), zip(Map.of("one.png", image("png"))));
        mvc.perform(post(API + "/imports/" + batch + "/complete").header("Authorization", "Bearer owner")
                .header("Idempotency-Key", "unexpected-body").contentType("application/json").content("{\"extra\":true}"))
                .andExpect(status().isBadRequest());
        assertEquals("UPLOADING", statusOf(batch).path("status").asText());
    }

    @Test void serializesConcurrentReplaysWithoutDuplicatingDiskBytes() throws Exception {
        String batch = batch(project(), new byte[256]);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.concurrent.Callable<Integer> upload = () -> {
                start.await();
                return chunk(batch, 0, new byte[256]).andReturn().getResponse().getStatus();
            };
            var first = executor.submit(upload); var second = executor.submit(upload); start.countDown();
            assertEquals(200, first.get(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(200, second.get(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, db.queryForObject("SELECT count(*) FROM import_chunk WHERE batch_id = ?", Integer.class, UUID.fromString(batch)));
            assertEquals(256, Files.size(java.nio.file.Path.of(STORAGE, batch, "archive.zip")));
        } finally { executor.shutdownNow(); }
    }

    @Test void rejectsPausedWritesAndFilesAndDisabledIdentity() throws Exception {
        String project = project();
        String batch = uploaded(project, zip(Map.of("one.png", image("png"))));
        command(batch, "complete", "state-complete").andExpect(status().isAccepted()); processQueue();
        String sample = db.queryForObject("SELECT id::text FROM sample WHERE batch_id = ?", String.class, UUID.fromString(batch));
        db.update("UPDATE project SET status='PAUSED' WHERE id=?", UUID.fromString(project));
        mvc.perform(get(API + "/projects/" + project + "/samples/" + sample + "/file").header("Authorization", "Bearer owner"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("PROJECT_PAUSED"));
        chunk(batch, 0, new byte[1]).andExpect(status().isConflict());
        db.update("UPDATE actor SET status='DISABLED' WHERE identity_user_id='owner'");
        try {
            mvc.perform(get(API + "/imports/" + batch).header("Authorization", "Bearer owner")).andExpect(status().isForbidden());
        } finally { db.update("UPDATE actor SET status='ENABLED' WHERE identity_user_id='owner'"); }
    }

    @Test void restoresProcessingBatchInFreshServiceContext() throws Exception {
        String batch = uploaded(project(), zip(Map.of("one.png", image("png"))));
        command(batch, "complete", "restart-complete").andExpect(status().isAccepted());
        try (var restarted = new org.springframework.boot.builder.SpringApplicationBuilder(com.tss.labeling.LabelingApplication.class)
                .web(org.springframework.boot.WebApplicationType.NONE).run(
                        "--spring.datasource.url=jdbc:postgresql://127.0.0.1:" + PG.getPort() + "/postgres?currentSchema=labeling",
                        "--spring.datasource.username=tss_labeling_app", "--spring.datasource.password=",
                        "--spring.flyway.user=postgres", "--spring.flyway.password=",
                        "--labeling.imports.storage-root=" + STORAGE, "--labeling.imports.worker-enabled=false",
                                "--labeling.exports.worker-enabled=false")) {
            restarted.getBean(ImportWorker.class).runOnce();
        }
        assertEquals("READY", statusOf(batch).path("status").asText());
        assertEquals(1, db.queryForObject("SELECT count(*) FROM sample WHERE batch_id=?", Integer.class, UUID.fromString(batch)));
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="LABELING_NODE_E2E", matches="true")
    void runsFrontendHandoffScriptAgainstRealHttpService() throws Exception {
        Path temp = Files.createTempDirectory("labeling-node-e2e-");
        Path archive = temp.resolve("images.zip");
        Files.write(archive, zip(Map.of("分类/one.png", image("png"), "two.jpg", image("jpg"))));
        String project = project();
        ProcessBuilder builder = new ProcessBuilder("node", "examples/image-import.mjs", project, archive.toString(), temp.resolve("download").toString());
        builder.environment().put("TOKEN", "owner");
        builder.environment().put("LABELING_API_BASE", "http://127.0.0.1:" + port + API);
        builder.environment().put("IMPORT_IDEMPOTENCY_KEY", UUID.randomUUID().toString());
        builder.environment().remove("BATCH_ID");
        builder.environment().remove("RETRY_FAILED");
        builder.redirectErrorStream(true).redirectOutput(temp.resolve("node.log").toFile());
        Process client = builder.start();
        try {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(45);
            while (!client.waitFor(100, java.util.concurrent.TimeUnit.MILLISECONDS) && System.nanoTime() < deadline) processQueue();
            assertFalse(client.isAlive(), "Node 示例应在限定时间内完成");
            assertEquals(0, client.exitValue(), Files.readString(temp.resolve("node.log")));
            JsonNode manifest = json.readTree(Files.readString(temp.resolve("download/manifest.json")));
            assertEquals(2, manifest.path("samples").size());
            assertEquals("READY", manifest.path("batch").path("status").asText());
            for (JsonNode sample : manifest.path("samples")) {
                String extension = sample.path("mediaType").asText().equals("image/png") ? ".png" : ".jpg";
                assertEquals(sample.path("contentSha256").asText(), sha(Files.readAllBytes(temp.resolve("download/" + sample.path("sampleId").asText() + extension))));
            }
            client = builder.start();
            assertTrue(client.waitFor(20, java.util.concurrent.TimeUnit.SECONDS), "相同创建键应恢复已完成批次");
            assertEquals(0, client.exitValue(), Files.readString(temp.resolve("node.log")));
            JsonNode replayed = json.readTree(Files.readString(temp.resolve("download/manifest.json")));
            assertEquals(manifest.path("batch").path("batchId"), replayed.path("batch").path("batchId"));
        } finally { if (client.isAlive()) client.destroyForcibly(); }
    }

    JsonNode taskPost(String path, String user, String key, Object body, int expected) throws Exception {
        String content = mvc.perform(post(API + path).header("Authorization", "Bearer " + user)
                .header("Idempotency-Key", key).contentType("application/json").content(json.writeValueAsString(body)))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return json.readTree(content).path("data");
    }

    JsonNode taskPlan(int people) throws Exception {
        String project = project();
        String batch = uploaded(project, zip(Map.of("a.png", image("png"), "b.png", image("png"), "c.png", image("png"))));
        command(batch, "complete", UUID.randomUUID().toString()).andExpect(status().isAccepted());
        processQueue();
        JsonNode spec = taskPost("/projects/" + project + "/spec-versions", "owner", UUID.randomUUID().toString(),
                Map.of("taskType", "IMAGE_CLASSIFICATION", "schemaVersion", "1", "labels", java.util.List.of(Map.of("id", "normal", "name", "正常"))), 201);
        JsonNode plan = taskPost("/projects/" + project + "/package-plans", "owner", UUID.randomUUID().toString(),
                Map.of("batchId", batch, "specVersionId", spec.path("specVersionId").asText(), "packageSize", 2, "annotatorsPerSample", people), 201);
        assertEquals(3, plan.path("sampleCount").asInt());
        assertEquals(2, plan.path("packageSampleCounts").size());
        assertEquals(3 * people, plan.path("totalWorkload").asInt());
        return plan;
    }

    JsonNode publishTask(JsonNode plan) throws Exception {
        return taskPost("/package-plans/" + plan.path("planId").asText() + "/publish", "owner", UUID.randomUUID().toString(),
                Map.of("selectionHash", plan.path("selectionHash").asText()), 200);
    }

    @Test void taskPackagesAllowOnlyClaimedImagesAndReplayClaims() throws Exception {
        JsonNode draft = taskPlan(2);
        JsonNode published = publishTask(draft);
        assertEquals(published, publishTask(draft));
        String pkg = published.path("packages").get(0).path("packageId").asText();
        String pool = mvc.perform(get(API + "/task-pool").header("Authorization", "Bearer worker").param("limit", "100"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(pool.contains(pkg));
        assertFalse(pool.contains("storage_key"));
        assertFalse(pool.contains("fileUrl"));
        mvc.perform(get(API + "/task-pool")).andExpect(status().isUnauthorized());
        String key = UUID.randomUUID().toString();
        JsonNode claim = taskPost("/packages/" + pkg + "/claims", "worker", key, Map.of(), 201);
        assertEquals(claim, taskPost("/packages/" + pkg + "/claims", "worker", key, Map.of(), 201));
        taskPost("/packages/" + pkg + "/claims", "worker", UUID.randomUUID().toString(), Map.of(), 409);
        taskPost("/packages/" + pkg + "/claims", "worker2", UUID.randomUUID().toString(), Map.of(), 201);
        taskPost("/packages/" + pkg + "/claims", "worker3", UUID.randomUUID().toString(), Map.of(), 409);
        String id = claim.path("assignmentId").asText();
        String mine = mvc.perform(get(API + "/assignments").header("Authorization", "Bearer worker"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(mine.contains(id));
        String others = mvc.perform(get(API + "/assignments").header("Authorization", "Bearer worker3"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(others.contains(id));
        String content = mvc.perform(get(API + "/assignments/" + id + "/items").header("Authorization", "Bearer worker"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.specification.labels[0].id").value("normal"))
                .andReturn().getResponse().getContentAsString();
        JsonNode items = json.readTree(content).path("data").path("items");
        assertEquals(2, items.size());
        String file = items.get(0).path("fileUrl").asText();
        mvc.perform(get(file).header("Authorization", "Bearer worker")).andExpect(status().isOk());
        mvc.perform(get(file).header("Authorization", "Bearer worker2")).andExpect(status().isNotFound());
        UUID otherSample = db.queryForObject("SELECT sample_id FROM package_item WHERE package_id=?", UUID.class,
                UUID.fromString(published.path("packages").get(1).path("packageId").asText()));
        mvc.perform(get(API + "/assignments/" + id + "/samples/" + otherSample + "/file").header("Authorization", "Bearer worker"))
                .andExpect(status().isNotFound());
        UUID projectId = db.queryForObject("SELECT project_id FROM package_plan WHERE id=?", UUID.class, UUID.fromString(draft.path("planId").asText()));
        mvc.perform(get(API + "/projects/" + projectId + "/samples/" + items.get(0).path("sampleId").asText() + "/file")
                .header("Authorization", "Bearer worker")).andExpect(status().isNotFound());
        db.update("UPDATE project SET status='PAUSED' WHERE id=?", projectId);
        mvc.perform(get(file).header("Authorization", "Bearer worker")).andExpect(status().isConflict());
        db.update("UPDATE project SET status='OPEN' WHERE id=?", projectId);
        db.update("UPDATE actor SET status='DISABLED' WHERE identity_user_id='worker'");
        try { mvc.perform(get(file).header("Authorization", "Bearer worker")).andExpect(status().isForbidden()); }
        finally { db.update("UPDATE actor SET status='ENABLED' WHERE identity_user_id='worker'"); }
    }

    @Test void concurrentClaimsNeverOverbook() throws Exception {
        JsonNode published = publishTask(taskPlan(2));
        String pkg = published.path("packages").get(0).path("packageId").asText();
        var executor = java.util.concurrent.Executors.newFixedThreadPool(6);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
            for (int i = 0; i < 6; i++) {
                String user = "concurrent-" + UUID.randomUUID();
                futures.add(executor.submit(() -> {
                    start.await();
                    return mvc.perform(post(API + "/packages/" + pkg + "/claims").header("Authorization", "Bearer " + user)
                            .header("Idempotency-Key", UUID.randomUUID()).contentType("application/json").content("{}"))
                            .andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            int successes = 0;
            for (var future : futures) {
                int code = future.get(20, java.util.concurrent.TimeUnit.SECONDS);
                assertTrue(code == 201 || code == 409, "非预期状态：" + code);
                if (code == 201) successes++;
            }
            assertEquals(2, successes);
            assertEquals(2, db.queryForObject("SELECT count(*) FROM assignment WHERE package_id=?", Integer.class, UUID.fromString(pkg)));
        } finally { executor.shutdownNow(); }
    }

    @Test void cannotPublishSameBatchTwiceOrUseForeignSpecification() throws Exception {
        JsonNode plan = taskPlan(1);
        UUID planId = UUID.fromString(plan.path("planId").asText());
        var row = db.queryForMap("SELECT * FROM package_plan WHERE id=?", planId);
        String project = row.get("project_id").toString();
        JsonNode second = taskPost("/projects/" + project + "/package-plans", "owner", UUID.randomUUID().toString(),
                Map.of("batchId", row.get("batch_id"), "specVersionId", row.get("spec_version_id")), 201);
        publishTask(plan);
        taskPost("/package-plans/" + second.path("planId").asText() + "/publish", "owner", UUID.randomUUID().toString(),
                Map.of("selectionHash", second.path("selectionHash").asText()), 409);
        taskPost("/projects/" + project + "/package-plans", "other", UUID.randomUUID().toString(),
                Map.of("batchId", row.get("batch_id"), "specVersionId", row.get("spec_version_id")), 404);
        taskPost("/projects/" + project() + "/package-plans", "owner", UUID.randomUUID().toString(),
                Map.of("batchId", row.get("batch_id"), "specVersionId", row.get("spec_version_id")), 404);
    }


    JsonNode resultGet(String path, String user, int expected) throws Exception {
        String content = mvc.perform(get(API + path).header("Authorization", "Bearer " + user))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return json.readTree(content).path("data");
    }

    JsonNode saveDraft(String assignment, String sample, String user, long revision, boolean done, String label, int expected) throws Exception {
        String content = mvc.perform(put(API + "/assignments/" + assignment + "/drafts/" + sample)
                .header("Authorization", "Bearer " + user).contentType("application/json")
                .content(json.writeValueAsString(Map.of("baseRevision", revision, "explicitDone", done,
                        "resultFormat", "image-classification-v1", "result", label == null ? Map.of() : Map.of("labelId", label)))))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return json.readTree(content).path("data");
    }

    @Test void draftsSubmitWholePackageAndFreezePersonalSnapshots() throws Exception {
        JsonNode published = publishTask(taskPlan(2));
        String pkg = published.path("packages").get(0).path("packageId").asText();
        String first = taskPost("/packages/" + pkg + "/claims", "author-a", UUID.randomUUID().toString(), Map.of(), 201).path("assignmentId").asText();
        String second = taskPost("/packages/" + pkg + "/claims", "author-b", UUID.randomUUID().toString(), Map.of(), 201).path("assignmentId").asText();
        JsonNode items = resultGet("/assignments/" + first + "/items", "author-a", 200).path("items");
        String sample = items.get(0).path("sampleId").asText(), sample2 = items.get(1).path("sampleId").asText();
        assertEquals(0, resultGet("/assignments/" + first + "/drafts/" + sample, "author-a", 200).path("revision").asInt());
        saveDraft(first, sample, "author-b", 0, true, "normal", 404);
        saveDraft(first, UUID.randomUUID().toString(), "author-a", 0, true, "normal", 404);
        saveDraft(first, sample, "author-a", 0, true, "invalid", 422);
        assertEquals(1, saveDraft(first, sample, "author-a", 0, true, "normal", 200).path("revision").asInt());
        saveDraft(first, sample, "author-a", 0, true, "normal", 409);
        JsonNode restored = resultGet("/assignments/" + first + "/items", "author-a", 200).path("items").get(0);
        assertEquals(1, restored.path("draftRevision").asInt());
        assertEquals("normal", restored.path("result").path("labelId").asText());
        assertEquals(0, resultGet("/assignments/" + second + "/drafts/" + sample, "author-b", 200).path("revision").asInt());
        taskPost("/assignments/" + first + "/submissions", "author-a", UUID.randomUUID().toString(),
                Map.of("items", java.util.List.of(Map.of("sampleId", sample, "revision", 1))), 422);
        saveDraft(first, sample2, "author-a", 0, false, null, 200);
        var full = Map.of("items", java.util.List.of(Map.of("sampleId", sample, "revision", 1), Map.of("sampleId", sample2, "revision", 1)));
        taskPost("/assignments/" + first + "/submissions", "author-a", UUID.randomUUID().toString(), full, 422);
        saveDraft(first, sample2, "author-a", 1, true, "normal", 200);
        taskPost("/assignments/" + first + "/submissions", "author-a", UUID.randomUUID().toString(), full, 409);
        var correct = Map.of("items", java.util.List.of(Map.of("sampleId", sample, "revision", 1), Map.of("sampleId", sample2, "revision", 2)));
        String key = UUID.randomUUID().toString();
        JsonNode receipt = taskPost("/assignments/" + first + "/submissions", "author-a", key, correct, 201);
        assertEquals(receipt, taskPost("/assignments/" + first + "/submissions", "author-a", key, correct, 201));
        taskPost("/assignments/" + first + "/submissions", "author-a", key, full, 409);
        taskPost("/assignments/" + first + "/submissions", "author-a", UUID.randomUUID().toString(), correct, 409);
        saveDraft(first, sample, "author-a", 1, true, "normal", 409);
        String id = receipt.path("submissionId").asText();
        JsonNode frozen = resultGet("/submissions/" + id, "author-a", 200);
        assertEquals(2, frozen.path("items").size());
        UUID firstResult = UUID.fromString(frozen.path("items").get(0).path("resultId").asText());
        UUID secondResult = UUID.fromString(frozen.path("items").get(1).path("resultId").asText());
        assertNotEquals(firstResult, secondResult);
        assertEquals("normal", frozen.path("items").get(0).path("result").path("labelId").asText());
        assertEquals(frozen, resultGet("/submissions/" + id, "owner", 200));
        resultGet("/submissions/" + id, "author-b", 404);
        assertEquals("CLAIMED", db.queryForObject("SELECT status FROM assignment WHERE id=?", String.class, UUID.fromString(second)));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM submission WHERE package_id=?", Integer.class, UUID.fromString(pkg)));
        String projectId = frozen.path("projectId").asText();
        assertEquals(1, resultGet("/projects/" + projectId + "/submissions", "owner", 200).path("items").size());
        resultGet("/projects/" + projectId + "/submissions", "author-b", 404);
        db.update("UPDATE project SET status='PAUSED' WHERE id=?", UUID.fromString(projectId));
        assertEquals(frozen, resultGet("/submissions/" + id, "owner", 200));
        resultGet("/assignments/" + first + "/drafts/" + sample, "author-a", 200);
        saveDraft(second, sample, "author-b", 0, true, "normal", 409);
        assertThrows(org.springframework.dao.DataAccessException.class, () -> db.update("UPDATE submission_item SET result='{}' WHERE submission_id=?", UUID.fromString(id)));
    }

    @Test void projectResultsIncludeUnsubmittedSamplesAndFreezeTheFilteredSelection() throws Exception {
        JsonNode plan = taskPlan(1);
        String pkg = publishTask(plan).path("packages").get(0).path("packageId").asText();
        String assignment = taskPost("/packages/" + pkg + "/claims", "author-a", UUID.randomUUID().toString(),
                Map.of(), 201).path("assignmentId").asText();
        var submitted = new java.util.ArrayList<Map<String, Object>>();
        for (JsonNode item : resultGet("/assignments/" + assignment + "/items", "author-a", 200).path("items")) {
            String sample = item.path("sampleId").asText();
            saveDraft(assignment, sample, "author-a", 0, true, "normal", 200);
            submitted.add(Map.of("sampleId", sample, "revision", 1));
        }
        String submission = taskPost("/assignments/" + assignment + "/submissions", "author-a",
                UUID.randomUUID().toString(), Map.of("items", submitted), 201).path("submissionId").asText();
        JsonNode detail = resultGet("/submissions/" + submission, "owner", 200);
        String project = detail.path("projectId").asText();
        String spec = detail.path("specVersionId").asText();
        String projects = "/projects/" + project;
        String results = projects + "/results?specVersionId=" + spec;
        resultGet(results, "author-a", 404);
        assertEquals(3, resultGet(results, "owner", 200).path("items").size());
        JsonNode page = resultGet(results + "&hasResult=true&limit=1", "owner", 200);
        String excluded = page.path("items").get(0).path("sampleId").asText();
        JsonNode next = resultGet(results + "&hasResult=true&limit=1&cursor=" + page.path("nextCursor").asText(),
                "owner", 200).path("items").get(0);
        assertNotEquals(excluded, next.path("sampleId").asText());
        String selectedSample = next.path("sampleId").asText();
        String selectedResult = next.path("resultId").asText();
        assertEquals(1, resultGet(results + "&hasResult=false", "owner", 200).path("items").size());
        assertEquals(3, resultGet(results + "&reviewStatus=UNREVIEWED", "owner", 200).path("items").size());
        assertEquals(2, resultGet(projects + "/result-statistics?specVersionId=" + spec,
                "owner", 200).path("hasResultCount").asInt());
        assertEquals(selectedResult, resultGet(projects + "/samples/" + selectedSample + "/results", "owner", 200)
                .path("items").get(0).path("resultId").asText());

        var request = Map.of("purpose", "PUBLISH", "specVersionId", spec,
                "filter", Map.of("hasResult", true), "excludeSampleIds", java.util.List.of(excluded));
        String key = UUID.randomUUID().toString();
        JsonNode selection = taskPost(projects + "/result-selections", "owner", key, request, 201);
        assertEquals(selection, taskPost(projects + "/result-selections", "owner", key, request, 201));
        taskPost(projects + "/result-selections", "owner", key,
                Map.of("purpose", "PUBLISH", "specVersionId", spec, "filter", Map.of()), 409);
        String snapshot = "/result-selections/" + selection.path("selectionId").asText();
        JsonNode preview = resultGet(snapshot + "/items", "owner", 200);
        assertEquals(1, selection.path("count").asInt());
        assertEquals(1, resultGet(snapshot + "/statistics", "owner", 200).path("total").asInt());
        assertEquals(next.path("sampleId"), preview.path("items").get(0).path("sampleId"));
        assertEquals(next.path("resultId"), preview.path("items").get(0).path("resultId"));
        resultGet(snapshot + "/items", "author-a", 404);

        taskPost(projects + "/sample-reviews", "owner", UUID.randomUUID().toString(),
                Map.of("items", java.util.List.of(Map.of("sampleId", selectedSample,
                        "specVersionId", spec, "action", "NEEDS_REVISION"))), 200);
        assertEquals(1, resultGet(results + "&reviewStatus=NEEDS_REVISION", "owner", 200).path("items").size());
        assertEquals(preview, resultGet(snapshot + "/items", "owner", 200));
        taskPost(projects + "/result-selections", "owner", UUID.randomUUID().toString(),
                Map.of("purpose", "REVIEW", "reviewAction", "ACCEPT", "specVersionId", spec,
                        "items", java.util.List.of(Map.of("sampleId", selectedSample, "resultId", selectedResult, "reviewRevision", 0))), 409);
        JsonNode explicit = taskPost(projects + "/result-selections", "owner", UUID.randomUUID().toString(),
                Map.of("purpose", "REVIEW", "reviewAction", "ACCEPT", "specVersionId", spec,
                        "items", java.util.List.of(Map.of("sampleId", selectedSample, "resultId", selectedResult, "reviewRevision", 1))), 201);
        assertEquals(1, explicit.path("count").asInt());
    }

    @Test void confidenceStatisticsRespectBoundariesFiltersPermissionsAndFrozenSelections() throws Exception {
        var f = confidenceFixture(0.0, 0.099, 0.1, 0.2, 0.3, 0.399, 0.4, 0.5,
                0.6, 0.7, 0.799, 0.8, 0.9, 1.0, null);
        String base = "/projects/" + f.project();
        String filter = "?executionId=" + f.execution() + "&confidencePolicyId=" + f.policy();
        JsonNode statistics = resultGet(base + "/result-statistics" + filter, "owner", 200);
        assertEquals(16, statistics.path("total").asInt());
        assertEquals(15, statistics.path("hasResultCount").asInt());
        assertEquals(1, statistics.path("noResultCount").asInt());
        JsonNode confidence = statistics.path("confidenceStatistics");
        assertEquals(15, confidence.path("aiResultCount").asInt());
        assertEquals(14, confidence.path("scoredCount").asInt());
        assertEquals(1, confidence.path("unknownCount").asInt());
        assertEquals(0, confidence.path("unresolvedSampleCount").asInt());
        assertEquals(f.policy(), confidence.path("confidencePolicyId").asText());
        assertEquals(6, confidence.path("bandCounts").path("LOW").asInt());
        assertEquals(5, confidence.path("bandCounts").path("MEDIUM").asInt());
        assertEquals(3, confidence.path("bandCounts").path("HIGH").asInt());
        assertEquals(1, confidence.path("bandCounts").path("UNKNOWN").asInt());
        int[] expected = {2, 1, 1, 2, 1, 1, 1, 2, 1, 2};
        assertEquals(10, confidence.path("histogram").size());
        for (int i = 0; i < 10; i++) {
            JsonNode bin = confidence.path("histogram").get(i);
            assertEquals(expected[i], bin.path("count").asInt(), "histogram bin " + i);
            assertEquals(i / 10.0, bin.path("lower").asDouble());
            assertEquals((i + 1) / 10.0, bin.path("upper").asDouble());
            assertEquals(i == 9, bin.path("upperInclusive").asBoolean());
        }
        for (String band : java.util.List.of("LOW", "MEDIUM", "HIGH", "UNKNOWN")) {
            String selected = filter + "&band=" + band;
            int count = confidence.path("bandCounts").path(band).asInt();
            int listed = 0;
            String cursor = "";
            do {
                var page = resultGet(base + "/results" + selected + "&limit=2" + cursor, "owner", 200);
                listed += page.path("items").size();
                cursor = page.path("nextCursor").isNull() ? "" : "&cursor=" + page.path("nextCursor").asText();
            } while (!cursor.isEmpty());
            assertEquals(count, listed);
            assertEquals(count, resultGet(base + "/result-statistics" + selected, "owner", 200).path("total").asInt());
        }
        var selectionRequest = Map.of("purpose", "PUBLISH", "specVersionId", f.spec(),
                "filter", Map.of("executionId", f.execution(), "confidencePolicyId", f.policy()));
        var selection = taskPost(base + "/result-selections", "owner", UUID.randomUUID().toString(), selectionRequest, 201);
        String frozenPath = "/result-selections/" + selection.path("selectionId").asText() + "/statistics";
        JsonNode frozen = resultGet(frozenPath, "owner", 200);
        assertEquals(statistics, frozen);
        var excluded = taskPost(base + "/result-selections", "owner", UUID.randomUUID().toString(),
                Map.of("purpose", "PUBLISH", "specVersionId", f.spec(), "filter", selectionRequest.get("filter"),
                        "excludeSampleIds", java.util.List.of(f.samples().get(12))), 201);
        var excludedStats = resultGet("/result-selections/" + excluded.path("selectionId").asText() + "/statistics", "owner", 200);
        assertEquals(15, excludedStats.path("total").asInt());
        assertEquals(2, excludedStats.path("confidenceStatistics").path("bandCounts").path("HIGH").asInt());
        taskPost(base + "/sample-reviews", "owner", UUID.randomUUID().toString(),
                Map.of("items", java.util.List.of(Map.of("sampleId", f.samples().get(0), "specVersionId", f.spec(),
                        "action", "NEEDS_REVISION", "reason", "confidence snapshot regression"))), 200);
        assertEquals(1, resultGet(base + "/result-statistics" + filter, "owner", 200).path("needsRevisionCount").asInt());
        assertEquals(frozen, resultGet(frozenPath, "owner", 200));
        resultGet(base + "/result-statistics" + filter, "statistics-stranger", 404);
        resultGet(frozenPath, "statistics-stranger", 404);
        resultGet(base + "/result-statistics?confidencePolicyId=" + f.policy(), "owner", 422);
        resultGet(base + "/result-statistics?band=HIGH", "owner", 422);
        var missing = resultGet(base + "/result-statistics" + filter + "&hasResult=false", "owner", 200);
        assertEquals(1, missing.path("total").asInt());
        assertEquals(0, missing.path("confidenceStatistics").path("unknownCount").asInt());
        var unclassified = resultGet(base + "/result-statistics?executionId=" + f.execution(), "owner", 200);
        assertTrue(unclassified.path("confidenceStatistics").path("bandCounts").isNull());
        assertEquals(confidence.path("histogram"), unclassified.path("confidenceStatistics").path("histogram"));
    }

    @Test void confidenceStatisticsDoNotChooseAmbiguousOrHumanAnswersAndExplicitSelectionsUseChosenIds() throws Exception {
        var f = confidenceFixture(0.9, 0.1);
        var plan = taskPost("/projects/" + f.project() + "/package-plans", "owner", UUID.randomUUID().toString(),
                Map.of("batchId", f.batch(), "specVersionId", f.spec(), "packageSize", 10, "annotatorsPerSample", 1), 201);
        complete(new Fixture(f.project(), f.spec(), f.batch(), publishTask(plan)), "confidence-human-" + UUID.randomUUID(), "normal");
        String base = "/projects/" + f.project();
        var live = resultGet(base + "/result-statistics", "owner", 200).path("confidenceStatistics");
        assertEquals(2, live.path("unresolvedSampleCount").asInt());
        assertEquals(0, live.path("aiResultCount").asInt());
        assertEquals(0, live.path("unknownCount").asInt());
        var explicit = new java.util.ArrayList<Map<String, Object>>();
        for (var row : resultGet("/ai-executions/" + f.execution() + "/results", "owner", 200).path("items")) {
            if (!row.path("resultId").isNull()) explicit.add(Map.of("sampleId", row.path("sampleId").asText(),
                    "resultId", row.path("resultId").asText(), "reviewRevision", 0));
        }
        assertEquals(2, explicit.size());
        var selection = taskPost(base + "/result-selections", "owner", UUID.randomUUID().toString(),
                Map.of("purpose", "REVIEW", "reviewAction", "ACCEPT", "specVersionId", f.spec(), "items", explicit), 201);
        var frozen = resultGet("/result-selections/" + selection.path("selectionId").asText() + "/statistics", "owner", 200)
                .path("confidenceStatistics");
        assertEquals(2, frozen.path("aiResultCount").asInt());
        assertEquals(2, frozen.path("scoredCount").asInt());
        assertEquals(0, frozen.path("unresolvedSampleCount").asInt());
        assertEquals(1, frozen.path("histogram").get(1).path("count").asInt());
        assertEquals(1, frozen.path("histogram").get(9).path("count").asInt());
        assertTrue(frozen.path("bandCounts").isNull());
        var humanOnly = resultGet(base + "/result-statistics?sourceKind=HUMAN", "owner", 200).path("confidenceStatistics");
        assertEquals(0, humanOnly.path("aiResultCount").asInt());
        assertEquals(0, humanOnly.path("unknownCount").asInt());
        var filtered = resultGet(base + "/result-statistics?executionId=" + f.execution(), "owner", 200).path("confidenceStatistics");
        assertEquals(2, filtered.path("aiResultCount").asInt());
        assertEquals(0, filtered.path("unresolvedSampleCount").asInt());
    }

    @Test void confidenceStatisticsReturnAnEmptyDistributionForAProjectWithoutResults() throws Exception {
        var statistics = resultGet("/projects/" + project() + "/result-statistics", "owner", 200);
        assertEquals(0, statistics.path("total").asInt());
        var confidence = statistics.path("confidenceStatistics");
        assertEquals(0, confidence.path("aiResultCount").asInt());
        assertEquals(0, confidence.path("scoredCount").asInt());
        assertEquals(0, confidence.path("unknownCount").asInt());
        assertTrue(confidence.path("confidencePolicyId").isNull());
        assertTrue(confidence.path("bandCounts").isNull());
        assertEquals(10, confidence.path("histogram").size());
        for (var bin : confidence.path("histogram")) assertEquals(0, bin.path("count").asInt());
    }

    record ConfidenceFixture(String project, String spec, String batch, String execution, String policy,
            java.util.List<String> samples) {}

    ConfidenceFixture confidenceFixture(Double... scores) throws Exception {
        String project = project();
        var files = new LinkedHashMap<String, byte[]>();
        for (int i = 0; i < scores.length + 2; i++) files.put("score-" + i + ".png", image("png"));
        String batch = uploaded(project, zip(files));
        command(batch, "complete", UUID.randomUUID().toString()).andExpect(status().isAccepted());
        processQueue();
        var samples = new java.util.ArrayList<String>();
        for (var row : resultGet("/imports/" + batch + "/samples", "owner", 200).path("items")) {
            samples.add(row.path("sampleId").asText());
        }
        assertEquals(scores.length + 2, samples.size());
        String spec = taskPost("/projects/" + project + "/spec-versions", "owner", UUID.randomUUID().toString(),
                Map.of("taskType", "IMAGE_CLASSIFICATION", "schemaVersion", "1",
                        "labels", java.util.List.of(Map.of("id", "normal", "name", "正常"))), 201)
                .path("specVersionId").asText();
        String credential = UUID.randomUUID().toString(), agent = agent("IMAGE_CLASSIFICATION", credential);
        var refs = new java.util.ArrayList<Map<String, Object>>();
        for (int i = 0; i < scores.length + 1; i++) refs.add(Map.of("sampleId", samples.get(i)));
        String execution = taskPost("/projects/" + project + "/ai-executions", "owner", UUID.randomUUID().toString(),
                Map.of("specVersionId", spec, "agentId", agent, "samples", refs), 202).path("executionId").asText();
        long generation = worker("POST", "/executions/claim", credential, null, 200).path("generation").asLong();
        for (int i = 0; i < scores.length; i++) {
            var output = new LinkedHashMap<String, Object>();
            output.put("generation", generation);
            output.put("status", "SUCCEEDED");
            output.put("result", Map.of("labelId", "normal"));
            output.put("confidence", scores[i]);
            worker("PUT", "/executions/" + execution + "/outputs/" + samples.get(i), credential, output, 200);
        }
        worker("PUT", "/executions/" + execution + "/outputs/" + samples.get(scores.length), credential,
                Map.of("generation", generation, "status", "FAILED", "errorCode", "MODEL_ERROR"), 200);
        worker("POST", "/executions/" + execution + "/finish", credential, Map.of("generation", generation), 200);
        var terminal = resultGet("/ai-executions/" + execution, "owner", 200);
        String policy = taskPost("/rounds/" + terminal.path("roundId").asText() + "/confidence-policies", "owner",
                UUID.randomUUID().toString(), Map.of("snapshotId", terminal.path("resultSnapshotId").asText(),
                        "lowerThreshold", 0.4, "upperThreshold", 0.8), 201).path("policyId").asText();
        return new ConfidenceFixture(project, spec, batch, execution, policy, samples);
    }

    @Test void concurrentDraftSavesUseCompareAndSet() throws Exception {
        JsonNode published = publishTask(taskPlan(1));
        String pkg = published.path("packages").get(0).path("packageId").asText();
        String assignment = taskPost("/packages/" + pkg + "/claims", "draft-race", UUID.randomUUID().toString(), Map.of(), 201).path("assignmentId").asText();
        String sample = resultGet("/assignments/" + assignment + "/items", "draft-race", 200).path("items").get(0).path("sampleId").asText();
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.concurrent.Callable<Integer> save = () -> {
                start.await();
                return mvc.perform(put(API + "/assignments/" + assignment + "/drafts/" + sample)
                        .header("Authorization", "Bearer draft-race").contentType("application/json")
                        .content("{\"baseRevision\":0,\"explicitDone\":true,\"resultFormat\":\"image-classification-v1\",\"result\":{\"labelId\":\"normal\"}}"))
                        .andReturn().getResponse().getStatus();
            };
            var a = executor.submit(save); var b = executor.submit(save); start.countDown();
            var codes = java.util.List.of(a.get(20, java.util.concurrent.TimeUnit.SECONDS), b.get(20, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(codes.contains(200) && codes.contains(409));
            assertEquals(1, resultGet("/assignments/" + assignment + "/drafts/" + sample, "draft-race", 200).path("revision").asInt());
            // 验证大于 Long 缓存范围的版本仍按数值比较。
            db.update(
                    "UPDATE annotation_draft SET revision=128 WHERE assignment_id=? AND"
                        + " sample_id=?", UUID.fromString(assignment), UUID.fromString(sample));
            assertEquals(129, saveDraft(assignment, sample, "draft-race", 128, true, "normal", 200).path("revision").asInt());
        } finally { executor.shutdownNow(); }
    }

    record Fixture(String project, String spec, String batch, JsonNode plan) {

}

    Fixture fixture(int people) throws Exception {
        String project = project();
        String batch = uploaded(project, zip(Map.of("a.png", image("png"), "b.png", image("png"))));
        command(batch, "complete", UUID.randomUUID().toString()).andExpect(status().isAccepted());
        processQueue();
        String spec =
                taskPost(
                                "/projects/" + project + "/spec-versions",
                                "owner",
                                UUID.randomUUID().toString(),
                                Map.of(
                                        "taskType",
                                        "IMAGE_CLASSIFICATION",
                                        "schemaVersion",
                                        "1",
                                        "labels",
                                        java.util.List.of(
                                                Map.of("id", "normal", "name", "正常"),
                                                Map.of("id", "other", "name", "其他"))),
                                201)
                        .path("specVersionId")
                        .asText();
        JsonNode plan =
                taskPost(
                        "/projects/" + project + "/package-plans",
                        "owner",
                        UUID.randomUUID().toString(),
                        Map.of(
                                "batchId",
                                batch,
                                "specVersionId",
                                spec,
                                "packageSize",
                                10,
                                "annotatorsPerSample",
                                people),
                        201);
        return new Fixture(project, spec, batch, publishTask(plan));
    }

    JsonNode complete(Fixture f, String user, String label) throws Exception {
        String assignment =
                taskPost(
                                "/packages/"
                                        + f.plan.path("packages").get(0).path("packageId").asText()
                                        + "/claims",
                                user,
                                UUID.randomUUID().toString(),
                                Map.of(),
                                201)
                        .path("assignmentId")
                        .asText();
        var revisions = new java.util.ArrayList<Map<String, Object>>();
        for (var item :
                resultGet("/assignments/" + assignment + "/items", user, 200).path("items")) {
            String sample = item.path("sampleId").asText();
            saveDraft(assignment, sample, user, 0, true, label, 200);
            revisions.add(Map.of("sampleId", sample, "revision", 1));
        }
        String submission =
                taskPost(
                                "/assignments/" + assignment + "/submissions",
                                user,
                                UUID.randomUUID().toString(),
                                Map.of("items", revisions),
                                201)
                        .path("submissionId")
                        .asText();
        return resultGet("/submissions/" + submission, user, 200);
    }

    java.util.List<Map<String, Object>> exact(JsonNode submitted, long revision) {
        var items = new java.util.ArrayList<Map<String, Object>>();
        for (var row : submitted.path("items"))
            items.add(
                    Map.of(
                            "sampleId",
                            row.path("sampleId").asText(),
                            "resultId",
                            row.path("resultId").asText(),
                            "baseReviewRevision",
                            revision));
        return items;
    }

    JsonNode selection(Fixture f, JsonNode submitted, String purpose, String action, long revision)
            throws Exception {
        var items = new java.util.ArrayList<Map<String, Object>>();
        for (var row : submitted.path("items"))
            items.add(
                    Map.of(
                            "sampleId",
                            row.path("sampleId").asText(),
                            "resultId",
                            row.path("resultId").asText(),
                            "reviewRevision",
                            revision));
        var body = new LinkedHashMap<String, Object>();
        body.put("specVersionId", f.spec);
        body.put("purpose", purpose);
        body.put("items", items);
        if (action != null) body.put("reviewAction", action);
        return taskPost(
                "/projects/" + f.project + "/result-selections",
                "owner",
                UUID.randomUUID().toString(),
                body,
                201);
    }

    @Test
    void acceptsLegacyHumanThenFreezesActualDatabaseResult() throws Exception {
        var f = fixture(1);
        var sub = complete(f, "legacy-" + UUID.randomUUID(), "normal");
        var items = new java.util.ArrayList<Map<String, Object>>();
        for (var row : sub.path("items"))
            items.add(
                    Map.of(
                            "sampleId",
                            row.path("sampleId").asText(),
                            "specVersionId",
                            f.spec,
                            "action",
                            "ACCEPT",
                            "sourceKind",
                            "HUMAN",
                            "submissionId",
                            sub.path("submissionId").asText()));
        taskPost(
                "/projects/" + f.project + "/sample-reviews",
                "owner",
                UUID.randomUUID().toString(),
                Map.of("items", items),
                200);
        assertEquals(
                "ACCEPTED",
                resultGet("/projects/" + f.project + "/sample-reviews", "owner", 200)
                        .path("items")
                        .get(0)
                        .path("status")
                        .asText());
        var snapshot =
                taskPost(
                        "/projects/" + f.project + "/final-snapshots",
                        "owner",
                        UUID.randomUUID().toString(),
                        Map.of(
                                "name",
                                "legacy",
                                "specVersionId",
                                f.spec,
                                "sampleIds",
                                items.stream().map(i -> i.get("sampleId")).toList()),
                        201);
        assertFalse(snapshot.path("items").get(0).path("resultId").asText().isBlank());
        assertEquals(2, snapshot.path("sampleCount").asInt());
        assertEquals(
                "normal", snapshot.path("items").get(0).path("result").path("labelId").asText());
    }

    @Test
    void exactReviewIsAtomicIdempotentAndSelectionCannotHideARevisionConflict() throws Exception {
        var f = fixture(1);
        var sub = complete(f, "atomic-" + UUID.randomUUID(), "normal");
        var chosen = selection(f, sub, "REVIEW", "ACCEPT", 0);
        var bad = new java.util.ArrayList<>(exact(sub, 0));
        var stale = new LinkedHashMap<>(bad.get(1));
        stale.put("baseReviewRevision", 7);
        bad.set(1, stale);
        taskPost(
                "/projects/" + f.project + "/reviews",
                "owner",
                UUID.randomUUID().toString(),
                Map.of("specVersionId", f.spec, "action", "ACCEPT", "items", bad),
                409);
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT count(*) FROM sample_review WHERE project_id=?",
                        Integer.class,
                        UUID.fromString(f.project)));
        var body = Map.of("selectionId", chosen.path("selectionId").asText(), "action", "ACCEPT");
        String key = UUID.randomUUID().toString();
        var response = taskPost("/projects/" + f.project + "/reviews", "owner", key, body, 200);
        assertEquals(
                response, taskPost("/projects/" + f.project + "/reviews", "owner", key, body, 200));
        taskPost(
                "/projects/" + f.project + "/reviews",
                "owner",
                UUID.randomUUID().toString(),
                body,
                409);
        var finalized = selection(f, sub, "FINALIZE", null, 1);
        var snapshot =
                taskPost(
                        "/projects/" + f.project + "/final-snapshots",
                        "owner",
                        UUID.randomUUID().toString(),
                        Map.of(
                                "name",
                                "fixed",
                                "specVersionId",
                                f.spec,
                                "selectionId",
                                finalized.path("selectionId").asText()),
                        201);
        assertEquals(
                sub.path("items").get(0).path("resultId"),
                snapshot.path("items").get(0).path("resultId"));
        var first = sub.path("items").get(0);
        String parent = first.path("resultId").asText();
        var revised =
                taskPost(
                        "/projects/" + f.project + "/results/" + parent + "/revisions",
                        "owner",
                        UUID.randomUUID().toString(),
                        Map.of(
                                "specVersionId",
                                f.spec,
                                "sampleId",
                                first.path("sampleId").asText(),
                                "baseReviewRevision",
                                1,
                                "result",
                                Map.of("labelId", "other"),
                                "reason",
                                "纠正标签"),
                        200);
        assertEquals(2, revised.path("reviewRevision").asInt());
        taskPost(
                "/projects/" + f.project + "/final-snapshots",
                "owner",
                UUID.randomUUID().toString(),
                Map.of(
                        "name",
                        "stale",
                        "specVersionId",
                        f.spec,
                        "selectionId",
                        finalized.path("selectionId").asText()),
                409);
        taskPost(
                "/projects/" + f.project + "/reviews",
                "owner",
                UUID.randomUUID().toString(),
                Map.of(
                        "specVersionId",
                        f.spec,
                        "action",
                        "ACCEPT",
                        "items",
                        java.util.List.of(
                                Map.of(
                                        "sampleId",
                                        first.path("sampleId").asText(),
                                        "resultId",
                                        revised.path("resultId").asText(),
                                        "baseReviewRevision",
                                        2))),
                200);
        var newer =
                taskPost(
                        "/projects/" + f.project + "/final-snapshots",
                        "owner",
                        UUID.randomUUID().toString(),
                        Map.of(
                                "name",
                                "revised",
                                "specVersionId",
                                f.spec,
                                "sampleIds",
                                java.util.List.of(first.path("sampleId").asText())),
                        201);
        assertEquals("other", newer.path("items").get(0).path("result").path("labelId").asText());
        assertEquals(
                snapshot,
                resultGet(
                        "/final-snapshots/" + snapshot.path("finalSnapshotId").asText(),
                        "owner",
                        200));
        resultGet(
                "/projects/"
                        + f.project
                        + "/samples/"
                        + first.path("sampleId").asText()
                        + "/results?specVersionId="
                        + f.spec,
                "stranger",
                404);
        assertEquals(
                "normal",
                resultGet("/submissions/" + sub.path("submissionId").asText(), "owner", 200)
                        .path("items")
                        .get(0)
                        .path("result")
                        .path("labelId")
                        .asText());
        assertEquals(
                revised.path("resultId"),
                resultGet(
                                "/projects/" + f.project + "/results?specVersionId=" + f.spec,
                                "owner",
                                200)
                        .path("items")
                        .get(0)
                        .path("selectedResultId"));
    }

    @Test
    void selectionPublishChecksExpiryAndVersionAtFinalPublish() throws Exception {
        var f = fixture(1);
        var sub = complete(f, "publish-" + UUID.randomUUID(), "normal");
        var chosen = selection(f, sub, "PUBLISH", null, 0);
        var plan =
                taskPost(
                        "/projects/" + f.project + "/package-plans",
                        "owner",
                        UUID.randomUUID().toString(),
                        Map.of(
                                "selectionId",
                                chosen.path("selectionId").asText(),
                                "specVersionId",
                                f.spec,
                                "packageSize",
                                1),
                        201);
        assertEquals(f.project, plan.path("projectId").asText());
        assertEquals(f.spec, plan.path("specVersionId").asText());
        taskPost(
                "/projects/" + f.project + "/reviews",
                "owner",
                UUID.randomUUID().toString(),
                Map.of("specVersionId", f.spec, "action", "ACCEPT", "items", exact(sub, 0)),
                200);
        taskPost(
                "/package-plans/" + plan.path("planId").asText() + "/publish",
                "owner",
                UUID.randomUUID().toString(),
                Map.of("selectionHash", plan.path("selectionHash").asText()),
                409);
        var fresh = selection(f, sub, "PUBLISH", null, 1);
        var freshPlan =
                taskPost(
                        "/projects/" + f.project + "/package-plans",
                        "owner",
                        UUID.randomUUID().toString(),
                        Map.of(
                                "selectionId",
                                fresh.path("selectionId").asText(),
                                "specVersionId",
                                f.spec,
                                "packageSize",
                                1,
                                "referenceMode",
                                "SELECTED"),
                        201);
        var republished = publishTask(freshPlan);
        assertEquals(2, republished.path("packages").size());
        String reader = "reference-" + UUID.randomUUID();
        String assignment =
                taskPost(
                                "/packages/"
                                        + republished
                                                .path("packages")
                                                .get(0)
                                                .path("packageId")
                                                .asText()
                                        + "/claims",
                                reader,
                                UUID.randomUUID().toString(),
                                Map.of(),
                                201)
                        .path("assignmentId")
                        .asText();
        var reference =
                resultGet("/assignments/" + assignment + "/items", reader, 200)
                        .path("items")
                        .get(0)
                        .path("referenceResult");
        assertEquals("normal", reference.path("result").path("labelId").asText());
        assertFalse(reference.path("resultId").asText().isBlank());
        assertEquals(
                "PENDING",
                resultGet("/projects/" + f.project + "/sample-reviews", "owner", 200)
                        .path("items")
                        .get(0)
                        .path("status")
                        .asText());
        var expired = selection(f, sub, "PUBLISH", null, 2);
        db.update(
                "UPDATE result_selection SET expires_at=CURRENT_TIMESTAMP-interval '1 second' WHERE"
                    + " id=?",
                UUID.fromString(expired.path("selectionId").asText()));
        taskPost(
                "/projects/" + f.project + "/package-plans",
                "owner",
                UUID.randomUUID().toString(),
                Map.of(
                        "selectionId",
                        expired.path("selectionId").asText(),
                        "specVersionId",
                        f.spec),
                410);
    }

    @Test
    void stateChangesAndDraftDeletionAreOwnerScopedVersionedAndRecoverable() throws Exception {
        String p = project(), key = UUID.randomUUID().toString();
        var body = Map.of("baseRevision", 1, "reason", "删除空草稿");
        mvc.perform(
                        delete(API + "/projects/" + p)
                                .header("Authorization", "Bearer stranger")
                                .header("Idempotency-Key", key)
                                .contentType("application/json")
                                .content(json.writeValueAsString(body)))
                .andExpect(status().isNotFound());
        for (int i = 0; i < 2; i++)
            mvc.perform(
                            delete(API + "/projects/" + p)
                                    .header("Authorization", "Bearer owner")
                                    .header("Idempotency-Key", key)
                                    .contentType("application/json")
                                    .content(json.writeValueAsString(body)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.deleted").value(true));
        resultGet("/projects/" + p, "owner", 404);
        taskPost(
                "/projects/" + p + "/spec-versions",
                "owner",
                UUID.randomUUID().toString(),
                Map.of(
                        "taskType",
                        "IMAGE_CLASSIFICATION",
                        "schemaVersion",
                        "1",
                        "labels",
                        java.util.List.of(Map.of("id", "n", "name", "N"))),
                404);
        var f = fixture(1);
        var open = resultGet("/projects/" + f.project, "owner", 200);
        taskPost(
                "/projects/" + f.project + "/archive",
                "owner",
                UUID.randomUUID().toString(),
                Map.of("baseRevision", open.path("revision").asLong(), "reason", "归档"),
                409);
        var paused =
                taskPost(
                        "/projects/" + f.project + "/pause",
                        "owner",
                        UUID.randomUUID().toString(),
                        Map.of("baseRevision", open.path("revision").asLong(), "reason", "暂停"),
                        200);
        taskPost(
                "/packages/"
                        + f.plan.path("packages").get(0).path("packageId").asText()
                        + "/claims",
                "pause-user",
                UUID.randomUUID().toString(),
                Map.of(),
                409);
        taskPost(
                "/projects/" + f.project + "/resume",
                "owner",
                UUID.randomUUID().toString(),
                Map.of("baseRevision", open.path("revision").asLong(), "reason", "恢复"),
                409);
        taskPost(
                "/projects/" + f.project + "/resume",
                "owner",
                UUID.randomUUID().toString(),
                Map.of("baseRevision", paused.path("revision").asLong(), "reason", "恢复"),
                200);
        complete(f, "history-" + UUID.randomUUID(), "normal");
        for (String path :
                java.util.List.of("imports", "rounds", "ai-executions", "package-plans")) {
            resultGet("/projects/" + f.project + "/" + path, "stranger", 404);
            resultGet("/projects/" + f.project + "/" + path + "?limit=0", "owner", 400);
            resultGet("/projects/" + f.project + "/" + path, "owner", 200);
        }
        assertFalse(
                resultGet("/me/operation-receipts?limit=1", "owner", 200).path("items").isEmpty());
    }

    JsonNode worker(String method, String path, String workerKey, Object body, int expected)
            throws Exception {
        var request =
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(
                                org.springframework.http.HttpMethod.valueOf(method),
                                "/internal/labeling/v1" + path)
                        .header("X-Worker-Key", workerKey)
                        .header("Idempotency-Key", UUID.randomUUID().toString());
        if (body != null)
            request.contentType("application/json").content(json.writeValueAsString(body));
        String text =
                mvc.perform(request)
                        .andExpect(status().is(expected))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return text.isEmpty() ? null : json.readTree(text);
    }

    String agent(String taskType, String workerKey) throws Exception {
        resultGet("/me", "admin-test", 200);
        UUID admin =
                db.queryForObject(
                        "SELECT id FROM actor WHERE identity_user_id='admin-test'", UUID.class);
        db.update(
                "INSERT INTO actor_role_grant(actor_id,role_code) VALUES (?,'LABELING_ADMIN') ON"
                    + " CONFLICT DO NOTHING",
                admin);
        return taskPost(
                        "/admin/agents",
                        "admin-test",
                        UUID.randomUUID().toString(),
                        Map.of(
                                "taskType",
                                taskType,
                                "displayName",
                                "test",
                                "modelVersion",
                                "1",
                                "workerKey",
                                workerKey),
                        201)
                .path("agentId")
                .asText();
    }

    @Test
    void aiPartialFailureHasRealSnapshotsAndRetriesOnlyFailedChildren() throws Exception {
        var f = fixture(1);
        var sub = complete(f, "ai-seed-" + UUID.randomUUID(), "normal");
        String workerKey = UUID.randomUUID().toString();
        String agent = agent("IMAGE_CLASSIFICATION", workerKey);
        var samples = sub.path("items");
        var refs = new java.util.ArrayList<Map<String, Object>>();
        for (var row : samples) refs.add(Map.of("sampleId", row.path("sampleId").asText()));
        var execution =
                taskPost(
                        "/projects/" + f.project + "/ai-executions",
                        "owner",
                        UUID.randomUUID().toString(),
                        Map.of("specVersionId", f.spec, "agentId", agent, "samples", refs),
                        202);
        String id = execution.path("executionId").asText();
        var claimed = worker("POST", "/executions/claim", workerKey, null, 200);
        long gen = claimed.path("generation").asLong();
        worker(
                "PUT",
                "/executions/" + id + "/outputs/" + samples.get(0).path("sampleId").asText(),
                workerKey,
                Map.of(
                        "generation",
                        gen,
                        "status",
                        "SUCCEEDED",
                        "result",
                        Map.of("labelId", "normal"),
                        "confidence",
                        0),
                200);
        worker(
                "PUT",
                "/executions/" + id + "/outputs/" + samples.get(1).path("sampleId").asText(),
                workerKey,
                Map.of("generation", gen, "status", "FAILED", "errorCode", "MODEL_ERROR"),
                200);
        worker("POST", "/executions/" + id + "/finish", workerKey, Map.of("generation", gen), 200);
        var terminal = resultGet("/ai-executions/" + id, "owner", 200);
        assertEquals("PARTIAL", terminal.path("status").asText());
        var answers = resultGet("/ai-executions/" + id + "/results", "owner", 200).path("items");
        assertEquals(0, answers.get(0).path("confidence").asDouble());
        assertTrue(answers.get(1).path("resultId").isNull());
        taskPost(
                "/projects/" + f.project + "/sample-reviews",
                "owner",
                UUID.randomUUID().toString(),
                Map.of(
                        "items",
                        java.util.List.of(
                                Map.of(
                                        "sampleId",
                                        samples.get(0).path("sampleId").asText(),
                                        "specVersionId",
                                        f.spec,
                                        "action",
                                        "ACCEPT",
                                        "sourceKind",
                                        "AI",
                                        "executionId",
                                        id))),
                200);
        taskPost(
                "/ai-executions/" + id + "/retries",
                "owner",
                UUID.randomUUID().toString(),
                Map.of(
                        "baseRevision",
                        terminal.path("revision").asLong(),
                        "sampleIds",
                        java.util.List.of(samples.get(0).path("sampleId").asText())),
                422);
        String key = UUID.randomUUID().toString();
        var body = Map.of("baseRevision", terminal.path("revision").asLong());
        var retry = taskPost("/ai-executions/" + id + "/retries", "owner", key, body, 201);
        assertEquals(1, retry.path("sampleCount").asInt());
        assertEquals(id, retry.path("retryOfExecutionId").asText());
        assertEquals(retry, taskPost("/ai-executions/" + id + "/retries", "owner", key, body, 201));
        taskPost(
                "/ai-executions/" + id + "/retries",
                "stranger",
                UUID.randomUUID().toString(),
                body,
                404);
    }

    @Test
    void aggregationUsesIndependentAnswersAndExportsRealImmutableZip() throws Exception {
        var f = fixture(2);
        var first = complete(f, "vote-a-" + UUID.randomUUID(), "normal");
        var second = complete(f, "vote-b-" + UUID.randomUUID(), "normal");
        var inputs = new java.util.ArrayList<Map<String, Object>>();
        for (int i = 0; i < first.path("items").size(); i++)
            inputs.add(
                    Map.of(
                            "sampleId",
                            first.path("items").get(i).path("sampleId").asText(),
                            "resultIds",
                            java.util.List.of(
                                    first.path("items").get(i).path("resultId").asText(),
                                    second.path("items").get(i).path("resultId").asText())));
        var job =
                taskPost(
                        "/projects/" + f.project + "/aggregations",
                        "owner",
                        UUID.randomUUID().toString(),
                        Map.of(
                                "specVersionId",
                                f.spec,
                                "algorithmId",
                                "majority-vote-v1",
                                "items",
                                inputs),
                        201);
        var items =
                resultGet(
                                "/aggregations/" + job.path("aggregationId").asText() + "/items",
                                "owner",
                                200)
                        .path("items");
        assertEquals("SUGGESTED", items.get(0).path("outcome").asText());
        assertEquals(
                "UNREVIEWED",
                resultGet(
                                "/projects/" + f.project + "/results?specVersionId=" + f.spec,
                                "owner",
                                200)
                        .path("items")
                        .get(0)
                        .path("reviewStatus")
                        .asText());
        var accept = new java.util.ArrayList<Map<String, Object>>();
        for (var row : items)
            accept.add(
                    Map.of(
                            "sampleId",
                            row.path("sampleId").asText(),
                            "resultId",
                            row.path("resultId").asText(),
                            "baseReviewRevision",
                            0));
        taskPost(
                "/projects/" + f.project + "/reviews",
                "owner",
                UUID.randomUUID().toString(),
                Map.of("specVersionId", f.spec, "action", "ACCEPT", "items", accept),
                200);
        var snapshot =
                taskPost(
                        "/projects/" + f.project + "/final-snapshots",
                        "owner",
                        UUID.randomUUID().toString(),
                        Map.of(
                                "name",
                                "voted",
                                "specVersionId",
                                f.spec,
                                "sampleIds",
                                accept.stream().map(x -> x.get("sampleId")).toList()),
                        201);
        var export =
                taskPost(
                        "/projects/" + f.project + "/exports",
                        "owner",
                        UUID.randomUUID().toString(),
                        Map.of(
                                "format",
                                "classification-csv-zip",
                                "finalSnapshotIds",
                                java.util.List.of(snapshot.path("finalSnapshotId").asText())),
                        202);
        String id = export.path("exportId").asText();
        assertEquals("QUEUED", export.path("status").asText());
        resultGet("/exports/" + id + "/download", "stranger", 404);
        resultGet("/exports/" + id + "/download", "owner", 409);
        context.getBean(com.tss.labeling.review.ExportWorker.class).runOnce();
        var done = resultGet("/exports/" + id, "owner", 200);
        assertEquals("SUCCEEDED", done.path("status").asText());
        byte[] bytes =
                mvc.perform(
                                get(API + "/exports/" + id + "/download")
                                        .header("Authorization", "Bearer owner"))
                        .andExpect(status().isOk())
                        .andExpect(content().contentType("application/zip"))
                        .andReturn()
                        .getResponse()
                        .getContentAsByteArray();
        assertEquals(sha(bytes), done.path("sha256").asText());
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bytes))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null)
                files.put(entry.getName(), zip.readAllBytes());
        }
        assertTrue(new String(files.get("results.csv"), StandardCharsets.UTF_8).contains("normal"));
        var manifest = json.readTree(files.get("manifest.json"));
        assertEquals(2, manifest.path("sampleCount").asInt());
        assertFalse(manifest.path("specifications").isEmpty());assertFalse(manifest.path("finalSnapshots").isEmpty());
        assertEquals(
                sha(files.get("results.csv")),
                manifest.path("files").get(0).path("sha256").asText());
    }

    String energyRow(String task, String station, String h0, String label) {
        var fields = new java.util.ArrayList<String>();
        fields.addAll(java.util.List.of(task, station, "enterprise", "device", "2026-11-01", h0));
        for (int h = 1; h < 24; h++) fields.add(h == 1 ? "" : "1.5");
        fields.add("34.5");
        fields.add(label);
        return String.join(",", fields);
    }

    byte[] energyArchive(String version, String... rows) throws Exception {
        var fields =
                new java.util.ArrayList<String>(
                        java.util.List.of(
                                "task_id",
                                "station_key",
                                "enterprise_key",
                                "station_level",
                                "date_local"));
        for (int h = 0; h < 24; h++) fields.add(String.format("h%02d", h));
        fields.addAll(java.util.List.of("daily_kwh", "label"));
        return zip(
                Map.of(
                        "manifest.json",
                        json.writeValueAsBytes(Map.of("datasetVersion", version)),
                        "sample/station_day_tasks_2k.csv",
                        (String.join(",", fields) + "\r\n" + String.join("\r\n", rows) + "\r\n")
                                .getBytes(StandardCharsets.UTF_8),
                        "preview/ignore.png",
                        image("png")));
    }

    String energyProject() throws Exception {
        return taskPost(
                        "/projects",
                        "owner",
                        UUID.randomUUID().toString(),
                        Map.of(
                                "name",
                                "能耗",
                                "taskType",
                                "TIMESERIES_ANNOTATION",
                                "templateId",
                                "energy-station-day",
                                "templateVersion",
                                "1.0.0"),
                        201)
                .path("projectId")
                .asText();
    }

    @Test
    void energyInputIsAtomicAuthorizedAndRoundTripsThroughSubmissionReviewAndExport()
            throws Exception {
        String p = energyProject();
        String batch =
                uploaded(
                        p,
                        energyArchive(
                                "energy-v1",
                                energyRow("t1", "s1", "0", ""),
                                energyRow("t2", "s2", "2", "")));
        command(batch, "complete", UUID.randomUUID().toString()).andExpect(status().isAccepted());
        processQueue();
        assertEquals("READY", statusOf(batch).path("status").asText());
        assertEquals(2, statusOf(batch).path("validSampleCount").asInt());
        var samples = resultGet("/imports/" + batch + "/samples", "owner", 200).path("items");
        String sid = samples.get(0).path("sampleId").asText();
        var input = resultGet("/projects/" + p + "/samples/" + sid + "/data", "owner", 200);
        assertEquals(24, input.path("payload").path("series").path("values").size());
        assertTrue(input.path("payload").path("series").path("values").get(1).isNull());
        assertEquals("NOT_PROVIDED", input.path("context").path("status").asText());
        for (var sample : samples) {
            var data =
                    resultGet(
                            "/projects/"
                                    + p
                                    + "/samples/"
                                    + sample.path("sampleId").asText()
                                    + "/data",
                            "owner",
                            200);
            if ("t1".equals(data.path("source").path("sourceTaskId").asText()))
                assertEquals(
                        0, data.path("payload").path("series").path("values").get(0).asDouble());
        }
        resultGet("/projects/" + p + "/samples/" + sid + "/data", "stranger", 404);
        resultGet("/projects/" + project() + "/samples/" + sid + "/data", "owner", 404);
        String spec =
                taskPost(
                                "/projects/" + p + "/spec-versions",
                                "owner",
                                UUID.randomUUID().toString(),
                                Map.of(
                                        "taskType",
                                        "TIMESERIES_ANNOTATION",
                                        "schemaVersion",
                                        "1.0.0",
                                        "labels",
                                        java.util.List.of(Map.of("id", "normal", "name", "正常"))),
                                201)
                        .path("specVersionId")
                        .asText();
        var plan =
                taskPost(
                        "/projects/" + p + "/package-plans",
                        "owner",
                        UUID.randomUUID().toString(),
                        Map.of("batchId", batch, "specVersionId", spec, "packageSize", 10),
                        201);
        plan = publishTask(plan);
        String user = "energy-" + UUID.randomUUID();
        String assignment =
                taskPost(
                                "/packages/"
                                        + plan.path("packages").get(0).path("packageId").asText()
                                        + "/claims",
                                user,
                                UUID.randomUUID().toString(),
                                Map.of(),
                                201)
                        .path("assignmentId")
                        .asText();
        assertEquals(
                input,
                resultGet("/assignments/" + assignment + "/samples/" + sid + "/data", user, 200));
        resultGet("/assignments/" + assignment + "/samples/" + sid + "/data", "owner", 404);
        var result =
                Map.of(
                        "schemaVersion",
                        "1.0.0",
                        "annotations",
                        java.util.List.of(
                                Map.of(
                                        "id",
                                        "primary",
                                        "from_name",
                                        "primary_label",
                                        "to_name",
                                        "load_curve",
                                        "type",
                                        "choices",
                                        "value",
                                        Map.of("labelIds", java.util.List.of("normal"))),
                                Map.of(
                                        "id",
                                        "point",
                                        "from_name",
                                        "point_labels",
                                        "to_name",
                                        "load_curve",
                                        "type",
                                        "timeseries_point",
                                        "value",
                                        Map.of(
                                                "timestamp",
                                                "2026-11-01T01:00:00+08:00",
                                                "channel",
                                                "value_kwh",
                                                "labelIds",
                                                java.util.List.of("normal")))));
        var revisions = new java.util.ArrayList<Map<String, Object>>();
        for (var sample : samples) {
            String id = sample.path("sampleId").asText();
            mvc.perform(
                            put(API + "/assignments/" + assignment + "/drafts/" + id)
                                    .header("Authorization", "Bearer " + user)
                                    .contentType("application/json")
                                    .content(
                                            json.writeValueAsString(
                                                    Map.of(
                                                            "baseRevision",
                                                            0,
                                                            "explicitDone",
                                                            true,
                                                            "resultFormat",
                                                            "annotation-record-v1",
                                                            "inputHash",
                                                            sample.path("contentSha256").asText(),
                                                            "result",
                                                            result))))
                    .andExpect(status().isOk());
            revisions.add(Map.of("sampleId", id, "revision", 1));
        }
        var receipt =
                taskPost(
                        "/assignments/" + assignment + "/submissions",
                        user,
                        UUID.randomUUID().toString(),
                        Map.of("items", revisions),
                        201);
        var submitted =
                resultGet("/submissions/" + receipt.path("submissionId").asText(), user, 200);
        assertEquals(json.valueToTree(result), submitted.path("items").get(0).path("result"));
        taskPost(
                "/projects/" + p + "/reviews",
                "owner",
                UUID.randomUUID().toString(),
                Map.of("specVersionId", spec, "action", "ACCEPT", "items", exact(submitted, 0)),
                200);
        var finalSnapshot =
                taskPost(
                        "/projects/" + p + "/final-snapshots",
                        "owner",
                        UUID.randomUUID().toString(),
                        Map.of(
                                "name",
                                "energy",
                                "specVersionId",
                                spec,
                                "sampleIds",
                                revisions.stream().map(x -> x.get("sampleId")).toList()),
                        201);
        var pluginFormats=resultGet("/final-snapshots/"+finalSnapshot.path("finalSnapshotId").asText()+"/plugin-export-formats","owner",200);
        assertTrue(pluginFormats.path("formats").toString().contains("energy-jsonl-zip"));
        assertTrue(pluginFormats.path("formats").toString().contains("JSON_MIN"));
        resultGet("/final-snapshots/"+finalSnapshot.path("finalSnapshotId").asText()+"/plugin-export-formats","other",404);
        var export =
                taskPost(
                        "/projects/" + p + "/exports",
                        "owner",
                        UUID.randomUUID().toString(),
                        Map.of(
                                "format",
                                "energy-jsonl-zip",
                                "finalSnapshotIds",
                                java.util.List.of(finalSnapshot.path("finalSnapshotId").asText())),
                        202);
        context.getBean(com.tss.labeling.review.ExportWorker.class).runOnce();
        assertEquals(
                "SUCCEEDED",
                resultGet("/exports/" + export.path("exportId").asText(), "owner", 200)
                        .path("status")
                        .asText());
        var exportList=resultGet("/projects/"+p+"/exports?snapshotId="+finalSnapshot.path("finalSnapshotId").asText(),"owner",200);
        assertEquals(export.path("exportId").asText(),exportList.path("items").get(0).path("exportId").asText());
        resultGet("/projects/"+p+"/exports","other",404);
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT count(*) FROM source_asset WHERE project_id=?",
                        Integer.class,
                        UUID.fromString(p)));
        String duplicate = uploaded(p, energyArchive("energy-v1", energyRow("t1", "s1", "0", "")));
        command(duplicate, "complete", UUID.randomUUID().toString())
                .andExpect(status().isAccepted());
        processQueue();
        assertEquals("DUPLICATE_ENERGY_SAMPLE", statusOf(duplicate).path("errorCode").asText());
    }

    @Test
    void actualEnergyDatasetFirstPackageRoundTripsThroughReviewAndExport() throws Exception {
        String datasetDirectory = System.getProperty("labeling.energy.dataset");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                datasetDirectory != null && !datasetDirectory.isBlank(),
                "通过 -Dlabeling.energy.dataset 指定本地能耗数据集目录后运行");

        Path dataset = Path.of(datasetDirectory);
        var manifest = json.readTree(Files.readAllBytes(dataset.resolve("manifest.json")));
        String version = manifest.path("version").asText();
        assertFalse(version.isBlank(), "数据集 manifest.json 应包含 version");
        var csvLines = Files.readAllLines(
                dataset.resolve("sample/station_day_tasks_2k.csv"), StandardCharsets.UTF_8);
        assertTrue(csvLines.size() >= 11, "样例 CSV 至少应有 10 条任务数据");
        String csv = String.join("\r\n", csvLines.subList(0, 11)) + "\r\n";
        byte[] archive = zip(Map.of(
                "manifest.json",
                json.writeValueAsBytes(Map.of("datasetVersion", version)),
                "sample/station_day_tasks_2k.csv",
                csv.getBytes(StandardCharsets.UTF_8)));

        String project = energyProject();
        String batch = uploaded(project, archive);
        command(batch, "complete", UUID.randomUUID().toString())
                .andExpect(status().isAccepted());
        processQueue();
        assertEquals("READY", statusOf(batch).path("status").asText());
        assertEquals(10, statusOf(batch).path("validSampleCount").asInt());

        JsonNode samples = resultGet("/imports/" + batch + "/samples", "owner", 200)
                .path("items");
        assertEquals(10, samples.size());
        JsonNode specification = taskPost(
                "/projects/" + project + "/spec-versions",
                "owner",
                UUID.randomUUID().toString(),
                Map.of(
                        "taskType", "TIMESERIES_ANNOTATION",
                        "schemaVersion", "1.0.0",
                        "labels", java.util.List.of(Map.of("id", "normal", "name", "正常"))),
                201);
        JsonNode plan = taskPost(
                "/projects/" + project + "/package-plans",
                "owner",
                UUID.randomUUID().toString(),
                Map.of(
                        "batchId", batch,
                        "specVersionId", specification.path("specVersionId").asText(),
                        "packageSize", 10),
                201);
        JsonNode published = publishTask(plan);
        String annotator = "real-energy-" + UUID.randomUUID();
        String assignment = taskPost(
                "/packages/" + published.path("packages").get(0).path("packageId").asText()
                        + "/claims",
                annotator,
                UUID.randomUUID().toString(),
                Map.of(),
                201).path("assignmentId").asText();

        var submissionItems = new java.util.ArrayList<Map<String, Object>>();
        for (JsonNode sample : samples) {
            String sampleId = sample.path("sampleId").asText();
            JsonNode input = resultGet(
                    "/assignments/" + assignment + "/samples/" + sampleId + "/data",
                    annotator,
                    200);
            String date = input.path("payload").path("dateLocal").asText();
            assertEquals(24, input.path("payload").path("series").path("values").size());
            Map<String, Object> result = Map.of(
                    "schemaVersion", "1.0.0",
                    "annotations", java.util.List.of(Map.of(
                            "id", "primary",
                            "from_name", "primary_label",
                            "to_name", "load_curve",
                            "type", "choices",
                            "value", Map.of("labelIds", java.util.List.of("normal")))));
            mvc.perform(put(API + "/assignments/" + assignment + "/drafts/" + sampleId)
                            .header("Authorization", "Bearer " + annotator)
                            .contentType("application/json")
                            .content(json.writeValueAsString(Map.of(
                                    "baseRevision", 0,
                                    "explicitDone", true,
                                    "resultFormat", "annotation-record-v1",
                                    "inputHash", input.path("inputHash").asText(),
                                    "result", result))))
                    .andExpect(status().isOk());
            submissionItems.add(Map.of("sampleId", sampleId, "revision", 1));
            assertFalse(date.isBlank(), "每条站点日样本应包含日期");
        }

        JsonNode receipt = taskPost(
                "/assignments/" + assignment + "/submissions",
                annotator,
                UUID.randomUUID().toString(),
                Map.of("items", submissionItems),
                201);
        JsonNode submitted = resultGet(
                "/submissions/" + receipt.path("submissionId").asText(), annotator, 200);
        assertEquals(10, submitted.path("items").size());
        taskPost(
                "/projects/" + project + "/reviews",
                "owner",
                UUID.randomUUID().toString(),
                Map.of(
                        "specVersionId", specification.path("specVersionId").asText(),
                        "action", "ACCEPT",
                        "items", exact(submitted, 0)),
                200);
        JsonNode snapshot = taskPost(
                "/projects/" + project + "/final-snapshots",
                "owner",
                UUID.randomUUID().toString(),
                Map.of(
                        "name", "真实能耗数据首包验收",
                        "specVersionId", specification.path("specVersionId").asText(),
                        "sampleIds", submissionItems.stream().map(item -> item.get("sampleId")).toList()),
                201);
        JsonNode export = taskPost(
                "/projects/" + project + "/exports",
                "owner",
                UUID.randomUUID().toString(),
                Map.of(
                        "format", "energy-jsonl-zip",
                        "finalSnapshotIds", java.util.List.of(snapshot.path("finalSnapshotId").asText())),
                202);
        context.getBean(com.tss.labeling.review.ExportWorker.class).runOnce();
        String exportId = export.path("exportId").asText();
        JsonNode completedExport = resultGet("/exports/" + exportId, "owner", 200);
        assertEquals("SUCCEEDED", completedExport.path("status").asText());
        byte[] exportedArchive = mvc.perform(
                        get(API + "/exports/" + exportId + "/download")
                                .header("Authorization", "Bearer owner"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/zip"))
                .andReturn().getResponse().getContentAsByteArray();
        assertEquals(sha(exportedArchive), completedExport.path("sha256").asText());
        Map<String, byte[]> exportedFiles = new LinkedHashMap<>();
        try (var zip = new java.util.zip.ZipInputStream(
                new java.io.ByteArrayInputStream(exportedArchive))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null)
                exportedFiles.put(entry.getName(), zip.readAllBytes());
        }
        String exportedResults = new String(exportedFiles.get("results.jsonl"), StandardCharsets.UTF_8);
        assertEquals(10, exportedResults.lines().count());
        assertTrue(exportedResults.contains("\"labelIds\":[\"normal\"]"));
        assertEquals(10, json.readTree(exportedFiles.get("manifest.json")).path("sampleCount").asInt());
    }

    @Test
    void energyInvalidOrAnnotatedRowsLeaveNoPartialSamples() throws Exception {
        for (String type : java.util.List.of("negative", "annotated", "duplicate")) {
            String p = energyProject();
            String row =
                    energyRow(
                            "bad",
                            "s-bad",
                            type.equals("negative") ? "-1" : "0",
                            type.equals("annotated") ? "normal" : "");
            if (type.equals("duplicate")) row = energyRow("good", "s-good", "0", "");
            String batch =
                    uploaded(p, energyArchive("bad-v1", energyRow("good", "s-good", "0", ""), row));
            command(batch, "complete", UUID.randomUUID().toString())
                    .andExpect(status().isAccepted());
            processQueue();
            var status = statusOf(batch);
            assertEquals("FAILED", status.path("status").asText());
            assertEquals(
                    0,
                    db.queryForObject(
                            "SELECT count(*) FROM sample WHERE project_id=?",
                            Integer.class,
                            UUID.fromString(p)));
            assertTrue(status.path("errors").get(0).path("sourceRow").asInt() > 0);
            assertFalse(status.path("errors").get(0).path("field").asText().isBlank());
        }
    }

    @Test
    void concurrentExactReviewsHaveOneWinnerAndNoPartialCommit() throws Exception {
        var f = fixture(1);
        var sub = complete(f, "race-" + UUID.randomUUID(), "normal");
        var body = Map.of("specVersionId", f.spec, "action", "ACCEPT", "items", exact(sub, 0));
        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
            for (int i = 0; i < 2; i++)
                tasks.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return mvc.perform(
                                                    post(API
                                                                    + "/projects/"
                                                                    + f.project
                                                                    + "/reviews")
                                                            .header("Authorization", "Bearer owner")
                                                            .header(
                                                                    "Idempotency-Key",
                                                                    UUID.randomUUID().toString())
                                                            .contentType("application/json")
                                                            .content(json.writeValueAsString(body)))
                                            .andReturn()
                                            .getResponse()
                                            .getStatus();
                                }));
            start.countDown();
            var statuses = new java.util.ArrayList<Integer>();
            for (var task : tasks)
                statuses.add(task.get(10, java.util.concurrent.TimeUnit.SECONDS));
            java.util.Collections.sort(statuses);
            assertEquals(java.util.List.of(200, 409), statuses);
            assertEquals(
                    2,
                    db.queryForObject(
                            "SELECT count(*) FROM review_event WHERE project_id=?",
                            Integer.class,
                            UUID.fromString(f.project)));
            assertEquals(
                    1,
                    db.queryForObject(
                            "SELECT max(revision) FROM sample_review WHERE project_id=?",
                            Integer.class,
                            UUID.fromString(f.project)));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void upgradesV6EditorAndSubmittedImageDataWithoutRewritingHistory() throws Exception {
        String database = "upgrade_" + UUID.randomUUID().toString().replace("-", "");
        try (var c = PG.getPostgresDatabase().getConnection();
                var statement = c.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }
        String url =
                "jdbc:postgresql://127.0.0.1:"
                        + PG.getPort()
                        + "/"
                        + database
                        + "?currentSchema=labeling";
        org.flywaydb.core.Flyway.configure()
                .dataSource(url, "postgres", "")
                .schemas("labeling")
                .defaultSchema("labeling")
                .target("6")
                .load()
                .migrate();
        var ds =
                new org.springframework.jdbc.datasource.DriverManagerDataSource(
                        url, "postgres", "");
        var old = new JdbcTemplate(ds);
        UUID actor = UUID.randomUUID(),
                project = UUID.randomUUID(),
                spec = UUID.randomUUID(),
                batch = UUID.randomUUID(),
                sample = UUID.randomUUID(),
                plan = UUID.randomUUID(),
                pkg = UUID.randomUUID(),
                slot = UUID.randomUUID(),
                assignment = UUID.randomUUID(),
                submission = UUID.randomUUID();
        String hash = "a".repeat(64);
        old.update(
                "INSERT INTO actor(id,kind,identity_user_id,display_name) VALUES"
                        + " (?,'HUMAN','legacy','legacy')",
                actor);
        old.update(
                "INSERT INTO project(id,owner_actor_id,name,task_type,status) VALUES"
                        + " (?,?,'legacy','IMAGE_CLASSIFICATION','OPEN')",
                project,
                actor);
        old.update(
                "INSERT INTO spec_version(id,project_id,version_no,task_type,labels,schema_version)"
                    + " VALUES"
                    + " (?,?,1,'IMAGE_CLASSIFICATION','[{\"id\":\"normal\",\"name\":\"Normal\"}]','1')",
                spec,
                project);
        old.update(
                "INSERT INTO"
                    + " import_batch(id,project_id,status,original_name,storage_key,size_bytes,received_bytes,chunk_size_bytes,sha256,original_complete,valid_sample_count)"
                    + " VALUES (?,?,'READY','old.zip','old.zip',1,1,1,?,true,1)",
                batch,
                project,
                hash);
        old.update(
                "INSERT INTO"
                    + " sample(id,project_id,batch_id,source_path,storage_key,content_sha256,media_type,metadata)"
                    + " VALUES (?,?,?,'old.png','old.bin',?,'image/png','{\"sizeBytes\":1}')",
                sample,
                project,
                batch,
                hash);
        old.update(
                "INSERT INTO"
                    + " package_plan(id,project_id,batch_id,spec_version_id,package_size,annotators_per_sample,sample_count,status,selection_hash,published_at)"
                    + " VALUES (?,?,?,?,1,1,1,'PUBLISHED',?,CURRENT_TIMESTAMP)",
                plan,
                project,
                batch,
                spec,
                hash);
        old.update(
                "INSERT INTO task_package(id,plan_id,sequence_no,sample_count,required_count)"
                    + " VALUES (?,?,1,1,1)",
                pkg,
                plan);
        old.update(
                "INSERT INTO package_item(package_id,plan_id,batch_id,sample_id,ordinal) VALUES"
                        + " (?,?,?,?,1)",
                pkg,
                plan,
                batch,
                sample);
        old.update("INSERT INTO package_slot(id,package_id,slot_no) VALUES (?,?,1)", slot, pkg);
        old.update(
                "INSERT INTO assignment(id,slot_id,package_id,actor_id,status,ended_at) VALUES"
                        + " (?,?,?,?,'SUBMITTED',CURRENT_TIMESTAMP)",
                assignment,
                slot,
                pkg,
                actor);
        old.update(
                "INSERT INTO"
                    + " editor_mapping(assignment_id,package_id,sample_id,actor_id,spec_version_id,sync_status,image_sha256,external_project_id,external_task_id,external_author_id)"
                    + " VALUES (?,?,?,?,?,'READY',?,'old-project','old-task','old-author')",
                assignment,
                pkg,
                sample,
                actor,
                spec,
                hash);
        old.update(
                "INSERT INTO"
                    + " submission(id,assignment_id,package_id,plan_id,project_id,spec_version_id,submitted_by,content_hash)"
                    + " VALUES (?,?,?,?,?,?,?,?)",
                submission,
                assignment,
                pkg,
                plan,
                project,
                spec,
                actor,
                hash);
        old.update(
                "INSERT INTO"
                    + " submission_item(submission_id,package_id,sample_id,draft_revision,result_format,result)"
                    + " VALUES (?,?,?,1,'image-classification-v1','{\"labelId\":\"normal\"}')",
                submission,
                pkg,
                sample);
        var upgraded =
                org.flywaydb.core.Flyway.configure()
                        .dataSource(url, "postgres", "")
                        .schemas("labeling")
                        .defaultSchema("labeling")
                        .load();
        upgraded.migrate();
        upgraded.validate();
        assertEquals(
                "image-classification",
                old.queryForObject(
                        "SELECT template_id FROM spec_version WHERE id=?", String.class, spec));
        assertEquals(
                old.queryForObject(
                        "SELECT round_id FROM assignment WHERE id=?", UUID.class, assignment),
                old.queryForObject(
                        "SELECT round_id FROM editor_mapping WHERE assignment_id=?",
                        UUID.class,
                        assignment));
        assertEquals(
                "CLOSED",
                old.queryForObject(
                        "SELECT status FROM publication_round WHERE package_id=?",
                        String.class,
                        pkg));
        assertEquals(
                "normal",
                json.readTree(
                                old.queryForObject(
                                        "SELECT result::text FROM project_result_candidate WHERE"
                                            + " sample_id=?",
                                        String.class,
                                        sample))
                        .path("labelId")
                        .asText());
        assertNotNull(
                old.queryForObject(
                        "SELECT result_id FROM project_result_candidate WHERE sample_id=?",
                        UUID.class,
                        sample));
    }

    @Test
    void energyWorkerUsesLeasedSlicesAndTheSameCompletionValidator() throws Exception {
        String p = energyProject();
        String batch =
                uploaded(p, energyArchive("worker-v1", energyRow("w1", "w-station", "0", "")));
        command(batch, "complete", UUID.randomUUID().toString()).andExpect(status().isAccepted());
        processQueue();
        String sample =
                resultGet("/imports/" + batch + "/samples", "owner", 200)
                        .path("items")
                        .get(0)
                        .path("sampleId")
                        .asText();
        String spec =
                taskPost(
                                "/projects/" + p + "/spec-versions",
                                "owner",
                                UUID.randomUUID().toString(),
                                Map.of(
                                        "taskType",
                                        "TIMESERIES_ANNOTATION",
                                        "schemaVersion",
                                        "1.0.0",
                                        "labels",
                                        java.util.List.of(Map.of("id", "normal", "name", "正常"))),
                                201)
                        .path("specVersionId")
                        .asText();
        String credential = UUID.randomUUID().toString(),
                agent = agent("TIMESERIES_ANNOTATION", credential);
        var execution =
                taskPost(
                        "/projects/" + p + "/ai-executions",
                        "owner",
                        UUID.randomUUID().toString(),
                        Map.of(
                                "specVersionId",
                                spec,
                                "agentId",
                                agent,
                                "samples",
                                java.util.List.of(Map.of("sampleId", sample))),
                        202);
        String id = execution.path("executionId").asText();
        var claim = worker("POST", "/executions/claim", credential, null, 200);
        long gen = claim.path("generation").asLong();
        assertEquals("annotation-record-v1", claim.path("resultFormat").asText());
        var data =
                worker(
                        "GET",
                        "/executions/" + id + "/samples/" + sample + "/data?generation=" + gen,
                        credential,
                        null,
                        200);
        assertEquals("energy-station-day-input-v1", data.path("inputFormat").asText());
        worker(
                "GET",
                "/executions/" + id + "/samples/" + UUID.randomUUID() + "/data?generation=" + gen,
                credential,
                null,
                404);
        worker(
                "GET",
                "/executions/" + id + "/samples/" + sample + "/data?generation=" + (gen + 1),
                credential,
                null,
                409);
        var primary =
                Map.of(
                        "id",
                        "p",
                        "from_name",
                        "primary_label",
                        "to_name",
                        "load_curve",
                        "type",
                        "choices",
                        "value",
                        Map.of("labelIds", java.util.List.of("normal")));
        var invalidPoint =
                Map.of(
                        "id",
                        "out",
                        "from_name",
                        "point_labels",
                        "to_name",
                        "load_curve",
                        "type",
                        "timeseries_point",
                        "value",
                        Map.of(
                                "timestamp",
                                "2026-11-02T00:00:00+08:00",
                                "channel",
                                "value_kwh",
                                "labelIds",
                                java.util.List.of("normal")));
        worker(
                "PUT",
                "/executions/" + id + "/outputs/" + sample,
                credential,
                Map.of(
                        "generation",
                        gen,
                        "status",
                        "SUCCEEDED",
                        "result",
                        Map.of("schemaVersion", "1.0.0", "annotations", java.util.List.of())),
                422);
        worker(
                "PUT",
                "/executions/" + id + "/outputs/" + sample,
                credential,
                Map.of(
                        "generation",
                        gen,
                        "status",
                        "SUCCEEDED",
                        "result",
                        Map.of(
                                "schemaVersion",
                                "1.0.0",
                                "annotations",
                                java.util.List.of(primary, invalidPoint))),
                422);
        var interval =
                Map.of(
                        "id",
                        "interval",
                        "from_name",
                        "interval_labels",
                        "to_name",
                        "load_curve",
                        "type",
                        "timeseries_interval",
                        "value",
                        Map.of(
                                "start",
                                "2026-11-01T23:00:00+08:00",
                                "end",
                                "2026-11-02T00:00:00+08:00",
                                "channel",
                                "value_kwh",
                                "labelIds",
                                java.util.List.of("normal")));
        var certainty =
                Map.of(
                        "id",
                        "certainty",
                        "from_name",
                        "self_reported_certainty",
                        "to_name",
                        "load_curve",
                        "type",
                        "choices",
                        "value",
                        Map.of("labelIds", java.util.List.of("UNKNOWN")));
        worker(
                "PUT",
                "/executions/" + id + "/outputs/" + sample,
                credential,
                Map.of(
                        "generation",
                        gen,
                        "status",
                        "SUCCEEDED",
                        "result",
                        Map.of(
                                "schemaVersion",
                                "1.0.0",
                                "annotations",
                                java.util.List.of(primary, interval, certainty))),
                200);
        worker("POST", "/executions/" + id + "/finish", credential, Map.of("generation", gen), 200);
        assertTrue(
                resultGet("/ai-executions/" + id + "/results", "owner", 200)
                        .path("items")
                        .get(0)
                        .path("confidence")
                        .isNull());
        worker(
                "GET",
                "/executions/" + id + "/samples/" + sample + "/data?generation=" + gen,
                credential,
                null,
                409);
    }

    @Test
    void tiesDoNotBecomeSuggestionsAndDerivedOrRepeatedVotesAreRejected() throws Exception {
        var f = fixture(2);
        String a = "tie-a-" + UUID.randomUUID(), b = "tie-b-" + UUID.randomUUID();
        var first = complete(f, a, "normal");
        var second = complete(f, b, "other");
        String sample = first.path("items").get(0).path("sampleId").asText(),
                ra = first.path("items").get(0).path("resultId").asText(),
                rb = second.path("items").get(0).path("resultId").asText();
        var body =
                Map.of(
                        "specVersionId",
                        f.spec,
                        "algorithmId",
                        "majority-vote-v1",
                        "items",
                        java.util.List.of(
                                Map.of(
                                        "sampleId",
                                        sample,
                                        "resultIds",
                                        java.util.List.of(ra, rb))));
        var job =
                taskPost(
                        "/projects/" + f.project + "/aggregations",
                        "owner",
                        UUID.randomUUID().toString(),
                        body,
                        201);
        var item =
                resultGet(
                                "/aggregations/" + job.path("aggregationId").asText() + "/items",
                                "owner",
                                200)
                        .path("items")
                        .get(0);
        assertEquals("TIE", item.path("outcome").asText());
        assertTrue(item.path("resultId").isNull());
        taskPost(
                "/projects/" + f.project + "/aggregations",
                "owner",
                UUID.randomUUID().toString(),
                Map.of(
                        "specVersionId",
                        f.spec,
                        "algorithmId",
                        "majority-vote-v1",
                        "items",
                        java.util.List.of(
                                Map.of(
                                        "sampleId",
                                        sample,
                                        "resultIds",
                                        java.util.List.of(ra, ra)))),
                422);
        taskPost(
                "/packages/"
                        + f.plan.path("packages").get(0).path("packageId").asText()
                        + "/rounds",
                "owner",
                UUID.randomUUID().toString(),
                Map.of(),
                201);
        var third = complete(f, a, "normal");
        String rc = third.path("items").get(0).path("resultId").asText();
        taskPost(
                "/projects/" + f.project + "/aggregations",
                "owner",
                UUID.randomUUID().toString(),
                Map.of(
                        "specVersionId",
                        f.spec,
                        "algorithmId",
                        "majority-vote-v1",
                        "items",
                        java.util.List.of(
                                Map.of(
                                        "sampleId",
                                        sample,
                                        "resultIds",
                                        java.util.List.of(ra, rc)))),
                422);
    }

    @Test
    void exportExpiryOverlapAndOperationReceiptPaginationAreExplicit() throws Exception {
        var f = fixture(1);
        String author = "receipt-" + UUID.randomUUID();
        var sub = complete(f, author, "normal");
        taskPost(
                "/projects/" + f.project + "/reviews",
                "owner",
                UUID.randomUUID().toString(),
                Map.of("specVersionId", f.spec, "action", "ACCEPT", "items", exact(sub, 0)),
                200);
        var snapshots = new java.util.ArrayList<String>();
        var ids = exact(sub, 0).stream().map(x -> x.get("sampleId")).toList();
        snapshots.add(
                taskPost(
                                "/projects/" + f.project + "/final-snapshots",
                                "owner",
                                UUID.randomUUID().toString(),
                                Map.of("name", "first", "specVersionId", f.spec, "sampleIds", ids),
                                201)
                        .path("finalSnapshotId")
                        .asText());
        taskPost(
                "/projects/" + f.project + "/final-snapshots",
                "owner",
                UUID.randomUUID().toString(),
                Map.of("name", "same content with another name", "specVersionId", f.spec, "sampleIds", ids),
                409);
        mvc.perform(patch(API + "/final-snapshots/" + snapshots.get(0))
                        .header("Authorization", "Bearer owner")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("name", "renamed"))))
                .andExpect(status().isOk());
        assertEquals("renamed", resultGet("/final-snapshots/" + snapshots.get(0), "owner", 200).path("name").asText());
        mvc.perform(patch(API + "/final-snapshots/" + snapshots.get(0))
                        .header("Authorization", "Bearer other")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("name", "forbidden"))))
                .andExpect(status().isNotFound());
        mvc.perform(put(API + "/submissions/" + sub.path("submissionId").asText() + "/review/items")
                        .header("Authorization", "Bearer owner")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("items", java.util.List.of(
                                Map.of("sampleId", ids.get(0), "action", "MODIFY", "result", Map.of("labelId", "other"), "reason", "修正"))))))
                .andExpect(status().isOk());
        snapshots.add(
                taskPost(
                                "/projects/" + f.project + "/final-snapshots",
                                "owner",
                                UUID.randomUUID().toString(),
                                Map.of("name", "second", "specVersionId", f.spec, "sampleIds", ids),
                                201)
                        .path("finalSnapshotId")
                        .asText());
        taskPost(
                "/projects/" + f.project + "/exports",
                "owner",
                UUID.randomUUID().toString(),
                Map.of("format", "classification-csv-zip", "finalSnapshotIds", snapshots),
                409);
        var export =
                taskPost(
                        "/projects/" + f.project + "/exports",
                        "owner",
                        UUID.randomUUID().toString(),
                        Map.of(
                                "format",
                                "classification-csv-zip",
                                "finalSnapshotIds",
                                java.util.List.of(snapshots.get(0))),
                        202);
        db.update(
                "UPDATE export_job SET expires_at=CURRENT_TIMESTAMP-interval '1 second' WHERE id=?",
                UUID.fromString(export.path("exportId").asText()));
        resultGet("/exports/" + export.path("exportId").asText() + "/download", "owner", 410);
        var page = resultGet("/me/operation-receipts?limit=1", author, 200);
        assertEquals(1, page.path("items").size());
        assertFalse(page.path("nextCursor").isNull());
        var next =
                resultGet(
                        "/me/operation-receipts?limit=1&cursor=" + page.path("nextCursor").asText(),
                        author,
                        200);
        assertNotEquals(
                page.path("items").get(0).path("operation"),
                next.path("items").get(0).path("operation"));
        resultGet("/me/operation-receipts?cursor=invalid", "owner", 400);
        assertTrue(
                resultGet("/me/submissions", author, 200)
                        .path("items")
                        .toString()
                        .contains(sub.path("submissionId").asText()));
    }

    @Test
    void globalOperationReceiptsAreAdminOnlyAndIncludeOperatorIdentity() throws Exception {
        complete(fixture(1), "operation-log-author", "normal");
        resultGet("/me", "admin-log-viewer", 200);
        UUID admin =
                db.queryForObject(
                        "SELECT id FROM actor WHERE identity_user_id='admin-log-viewer'",
                        UUID.class);
        db.update(
                "INSERT INTO actor_role_grant(actor_id,role_code) VALUES (?,'LABELING_ADMIN') ON"
                    + " CONFLICT DO NOTHING",
                admin);

        resultGet("/admin/operation-receipts?limit=1", "ordinary-user", 403);
        var page = resultGet("/admin/operation-receipts?limit=1", "admin-log-viewer", 200);
        assertEquals(1, page.path("items").size());
        var item = page.path("items").get(0);
        assertFalse(item.path("actorId").asText().isBlank());
        assertFalse(item.path("actorName").asText().isBlank());
        assertFalse(item.path("identityUserId").asText().isBlank());
        assertFalse(page.path("nextCursor").isNull());
        var next =
                resultGet(
                        "/admin/operation-receipts?limit=1&cursor="
                                + page.path("nextCursor").asText(),
                        "admin-log-viewer",
                        200);
        assertNotEquals(
                item.path("idempotencyKey").asText(),
                next.path("items").get(0).path("idempotencyKey").asText());
    }

    @Test
    void assignmentListReturnsClaimedAtForTheAnnotator() throws Exception {
        var f = fixture(1);
        String annotator = "claim-time-" + UUID.randomUUID();
        String assignment =
                taskPost(
                                "/packages/"
                                        + f.plan.path("packages").get(0).path("packageId").asText()
                                        + "/claims",
                                annotator,
                                UUID.randomUUID().toString(),
                                Map.of(),
                                201)
                        .path("assignmentId")
                        .asText();

        JsonNode page = resultGet("/assignments?scope=mine", annotator, 200);
        JsonNode item = page.path("items").get(0);
        assertEquals(assignment, item.path("assignmentId").asText());
        assertTrue(item.path("claimedAt").isTextual());
        assertDoesNotThrow(() -> java.time.Instant.parse(item.path("claimedAt").asText()));
    }

    @Test void archivedResultsAreReadOnlyButOldReceiptsRemainReplayable()throws Exception{
        var f=fixture(1);var sub=complete(f,"archive-"+UUID.randomUUID(),"normal");String key=UUID.randomUUID().toString();var body=Map.of("specVersionId",f.spec,"action","ACCEPT","items",exact(sub,0));
        var original=taskPost("/projects/"+f.project+"/reviews","owner",key,body,200);
        taskPost("/projects/"+f.project+"/package-plans","owner",UUID.randomUUID().toString(),Map.of("specVersionId",f.spec,"samples",java.util.List.of(Map.of("sampleId",sub.path("items").get(0).path("sampleId").asText())),"reopenAccepted",false),409);
        long revision=resultGet("/projects/"+f.project,"owner",200).path("revision").asLong();taskPost("/projects/"+f.project+"/archive","owner",UUID.randomUUID().toString(),Map.of("baseRevision",revision,"reason","关闭项目"),200);
        assertEquals(original,taskPost("/projects/"+f.project+"/reviews","owner",key,body,200));
        taskPost("/projects/"+f.project+"/reviews","owner",UUID.randomUUID().toString(),Map.of("specVersionId",f.spec,"action","ACCEPT","items",exact(sub,1)),409);
        taskPost("/projects/"+f.project+"/final-snapshots","owner",UUID.randomUUID().toString(),Map.of("name","closed","specVersionId",f.spec,"sampleIds",exact(sub,0).stream().map(x->x.get("sampleId")).toList()),409);
        mvc.perform(put(API+"/submissions/"+sub.path("submissionId").asText()+"/review/items").header("Authorization","Bearer owner").header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(json.writeValueAsString(Map.of("items",java.util.List.of(Map.of("sampleId",sub.path("items").get(0).path("sampleId").asText(),"action","REJECT")))))).andExpect(status().isConflict());
        resultGet("/submissions/"+sub.path("submissionId").asText(),"owner",200);
    }

    @Test void legacySubmissionModificationKeepsSubmissionImmutableAndChecksSpecBeforeAdoption()throws Exception{
        var f=fixture(1);var sub=complete(f,"modify-"+UUID.randomUUID(),"normal");String sample=sub.path("items").get(0).path("sampleId").asText(),submission=sub.path("submissionId").asText();
        mvc.perform(put(API+"/submissions/"+submission+"/review/items").header("Authorization","Bearer owner").header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(json.writeValueAsString(Map.of("items",java.util.List.of(Map.of("sampleId",sample,"action","MODIFY","result",Map.of("labelId","other"),"reason","修正")))))).andExpect(status().isOk());
        var results=resultGet("/projects/"+f.project+"/results?specVersionId="+f.spec,"owner",200);assertEquals("other",results.path("items").get(0).path("result").path("labelId").asText());
        var snapshot=taskPost("/projects/"+f.project+"/final-snapshots","owner",UUID.randomUUID().toString(),Map.of("name","modified","specVersionId",f.spec,"sampleIds",java.util.List.of(sample)),201);assertEquals("other",snapshot.path("items").get(0).path("result").path("labelId").asText());
        String otherSpec=taskPost("/projects/"+f.project+"/spec-versions","owner",UUID.randomUUID().toString(),Map.of("taskType","IMAGE_CLASSIFICATION","schemaVersion","2","labels",java.util.List.of(Map.of("id","normal","name","正常"))),201).path("specVersionId").asText();
        taskPost("/projects/"+f.project+"/sample-reviews","owner",UUID.randomUUID().toString(),Map.of("items",java.util.List.of(Map.of("sampleId",sample,"specVersionId",otherSpec,"action","ACCEPT","sourceKind","HUMAN","submissionId",submission))),409);
        assertEquals("normal",resultGet("/submissions/"+submission,"owner",200).path("items").get(0).path("result").path("labelId").asText());
        assertThrows(org.springframework.dao.DataAccessException.class,()->db.update("UPDATE submission_item SET result='{}' WHERE submission_id=?",UUID.fromString(submission)));
    }

    @Test
    void trustedPluginCataloguePinsProjectsSpecsAndInflightImports() throws Exception {
        var catalogue=json.readTree(mvc.perform(get(API+"/task-templates").header("Authorization","Bearer owner"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertEquals(4,catalogue.size());
        assertEquals(2,java.util.stream.StreamSupport.stream(catalogue.spliterator(),false)
                .filter(item->item.path("creationAvailable").asBoolean()).count());
        for(var plugin:catalogue) {
            assertTrue(plugin.path("enabled").asBoolean());
            assertTrue(plugin.path("capabilities").path("importer").asBoolean());
            assertTrue(plugin.path("capabilities").path("completion").asBoolean());
            assertEquals(64,plugin.path("schemaDigest").asText().length());
        }
        String p=project();
        var spec=taskPost("/projects/"+p+"/spec-versions","owner",UUID.randomUUID().toString(),
                Map.of("taskType","IMAGE_CLASSIFICATION","schemaVersion","1","labels",java.util.List.of(Map.of("id","normal","name","正常"))),201);
        assertEquals("image-classification",spec.path("templateId").asText());
        assertEquals("1.0.0",spec.path("templateVersion").asText());
        assertEquals("1.0.0",db.queryForObject("SELECT template_version FROM labeling.project WHERE id=?",String.class,UUID.fromString(p)));
        String batch=uploaded(p,zip(Map.of("sample.png",image("png"))));
        db.update("UPDATE labeling.project SET template_version='2.0.0' WHERE id=?",UUID.fromString(p));
        assertEquals("1.0.0",db.queryForObject("SELECT template_version FROM labeling.import_batch WHERE id=?",String.class,UUID.fromString(batch)));
        command(batch,"complete",UUID.randomUUID().toString()).andExpect(status().isAccepted());
        processQueue();
        assertEquals("READY",statusOf(batch).path("status").asText());
        taskPost("/projects","owner",UUID.randomUUID().toString(),Map.of("name","未知插件","taskType","IMAGE_CLASSIFICATION","templateId","unknown","templateVersion","1.0.0"),422);
    }

    @Test
    void actualEnergyDatasetImportsAllTwoThousandSamplesAndPublishesTwoHundredPackages() throws Exception {
        String folder=System.getProperty("labeling.energy.dataset");
        org.junit.jupiter.api.Assumptions.assumeTrue(folder!=null && !folder.isBlank());
        Path dataset=Path.of(folder);
        String version=json.readTree(Files.readAllBytes(dataset.resolve("manifest.json"))).path("version").asText();
        byte[] csv=Files.readAllBytes(dataset.resolve("sample/station_day_tasks_2k.csv"));
        String p=energyProject();
        String batch=uploaded(p,zip(Map.of("manifest.json",json.writeValueAsBytes(Map.of("datasetVersion",version)),"sample/station_day_tasks_2k.csv",csv)));
        command(batch,"complete",UUID.randomUUID().toString()).andExpect(status().isAccepted());
        processQueue();
        assertEquals("READY",statusOf(batch).path("status").asText());
        assertEquals(2000,statusOf(batch).path("validSampleCount").asInt());
        assertEquals(0,statusOf(batch).path("errorCount").asInt());
        assertEquals(2000,db.queryForObject("SELECT count(*) FROM labeling.sample_input WHERE project_id=?",Integer.class,UUID.fromString(p)));
        var spec=taskPost("/projects/"+p+"/spec-versions","owner",UUID.randomUUID().toString(),Map.of("taskType","TIMESERIES_ANNOTATION","schemaVersion","1.0.0","labels",java.util.List.of(Map.of("id","normal","name","正常"))),201);
        var plan=taskPost("/projects/"+p+"/package-plans","owner",UUID.randomUUID().toString(),Map.of("batchId",batch,"specVersionId",spec.path("specVersionId").asText(),"packageSize",10,"annotatorsPerSample",1),201);
        assertEquals(200,plan.path("packageSampleCounts").size());
        assertEquals(2000,plan.path("sampleCount").asInt());
        var published=publishTask(plan);
        assertEquals("PUBLISHED",published.path("status").asText());
        assertEquals(200,published.path("packages").size());
    }
}
