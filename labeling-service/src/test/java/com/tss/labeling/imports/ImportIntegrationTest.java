package com.tss.labeling.imports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Arrays;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.util.LinkedHashMap;
import java.util.zip.ZipOutputStream;
import java.util.zip.ZipEntry;
import org.springframework.context.ApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

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
                String user = "Bearer owner".equals(auth) ? "owner" : "other";
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
        db.update("UPDATE import_batch SET status = 'FAILED', error_code = 'TEST_RECOVERY' WHERE id = ?", UUID.fromString(batch));
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
                        "--labeling.imports.storage-root=" + STORAGE, "--labeling.imports.worker-enabled=false")) {
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
}
