package com.tss.platform.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tss.platform.dto.lerobot.LeRobotDatasetInfoDto;
import com.tss.platform.dto.lerobot.LeRobotEpisodeDto;
import com.tss.platform.dto.lerobot.LeRobotEpisodeSummaryDto;
import com.tss.platform.dto.lerobot.LeRobotPointCloudDto;
import com.tss.platform.dto.lerobot.LeRobotVideoDto;
import com.tss.platform.entity.DatasetAsset;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.repository.DatasetAssetRepository;
import com.tss.platform.repository.DatasetVersionRepository;
import com.tss.platform.security.AuthContext;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class LeRobotDatasetPreviewService {

    private static final int MAX_ARCHIVE_ENTRIES = 100_000;
    private static final long MAX_UNCOMPRESSED_BYTES = 50L * 1024 * 1024 * 1024;
    private static final long MAX_POINT_CLOUD_POINTS_PER_EPISODE = 1_000_000;

    private final DatasetVersionRepository versionRepo;
    private final DatasetAssetRepository assetRepo;
    private final MinioService minioService;
    private final AuthContext authContext;
    private final ObjectMapper objectMapper;
    private final Path cacheRoot;
    private final long mediaTicketSeconds;
    private final ConcurrentHashMap<String, MediaTicket> mediaTickets = new ConcurrentHashMap<>();

    public LeRobotDatasetPreviewService(
            DatasetVersionRepository versionRepo,
            DatasetAssetRepository assetRepo,
            MinioService minioService,
            AuthContext authContext,
            ObjectMapper objectMapper,
            @Value("${dataset.lerobot.cache-directory:${java.io.tmpdir}/tss-lerobot-preview}") String cacheDirectory,
            @Value("${dataset.lerobot.media-ticket-seconds:300}") long mediaTicketSeconds
    ) {
        this.versionRepo = versionRepo;
        this.assetRepo = assetRepo;
        this.minioService = minioService;
        this.authContext = authContext;
        this.objectMapper = objectMapper;
        this.cacheRoot = Path.of(cacheDirectory).toAbsolutePath().normalize();
        this.mediaTicketSeconds = Math.max(30, mediaTicketSeconds);
    }

    @PostConstruct
    void initializeCache() throws IOException {
        Files.createDirectories(cacheRoot);
    }

    @Transactional(readOnly = true)
    public LeRobotDatasetInfoDto info(String versionId) {
        DatasetSource source = requireSource(versionId);
        Path root = materialize(source.version());
        JsonNode info = readInfo(root);
        List<LeRobotEpisodeSummaryDto> episodes = readEpisodeSummaries(root, intValue(info, "fps"));
        Map<String, Object> features = objectMapper.convertValue(
                info.path("features"),
                new TypeReference<>() { }
        );
        return new LeRobotDatasetInfoDto(
                source.version().getId(),
                textValue(info, "codebase_version"),
                textValue(info, "robot_type"),
                intValue(info, "fps"),
                longValue(info, "total_episodes"),
                longValue(info, "total_frames"),
                longValue(info, "total_tasks"),
                features,
                episodes
        );
    }

    @Transactional(readOnly = true)
    public LeRobotEpisodeDto episode(String versionId, long episodeIndex) {
        DatasetSource source = requireSource(versionId);
        Path root = materialize(source.version());
        JsonNode info = readInfo(root);
        int fps = intValue(info, "fps");
        if (isV21(info)) {
            return readV21Episode(source, root, info, fps, episodeIndex);
        }
        EpisodeLocation location = findEpisode(root, episodeIndex);
        Path dataFile = safeResolve(root, "data/chunk-%03d/file-%03d.parquet".formatted(
                location.dataChunkIndex(), location.dataFileIndex()
        ));
        if (!Files.isRegularFile(dataFile)) {
            throw new IllegalArgumentException("LeRobot episode data file does not exist");
        }

        List<Double> timestamps = new ArrayList<>();
        List<Long> frameIndices = new ArrayList<>();
        List<List<Double>> states = new ArrayList<>();
        List<List<Double>> actions = new ArrayList<>();
        String sql = "select timestamp, frame_index, \"observation.state\", action "
                + "from read_parquet('" + sqlPath(dataFile) + "') where episode_index = "
                + episodeIndex + " order by frame_index";
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:");
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                timestamps.add(rows.getDouble(1));
                frameIndices.add(rows.getLong(2));
                states.add(numberList(rows.getObject(3)));
                actions.add(numberList(rows.getObject(4)));
            }
        } catch (Exception exception) {
            throw new IllegalArgumentException("failed to read LeRobot episode data", exception);
        }
        if (timestamps.isEmpty()) {
            throw new IllegalArgumentException("LeRobot episode has no frames: " + episodeIndex);
        }

        Map<String, LeRobotVideoDto> videos = new LinkedHashMap<>();
        for (Map.Entry<String, VideoLocation> entry : location.videos().entrySet()) {
            VideoLocation video = entry.getValue();
            String relative = "videos/%s/chunk-%03d/file-%03d.mp4".formatted(
                    entry.getKey(), video.chunkIndex(), video.fileIndex()
            );
            Path videoFile = safeResolve(root, relative);
            if (!Files.isRegularFile(videoFile)) {
                continue;
            }
            videos.put(entry.getKey(), new LeRobotVideoDto(
                    entry.getKey(),
                    issueMediaUrl(source, relative),
                    video.fromTimestamp(),
                    video.toTimestamp()
            ));
        }

        List<String> stateNames = featureNames(info, "observation.state");
        List<String> actionNames = featureNames(info, "action");
        return new LeRobotEpisodeDto(
                episodeIndex,
                fps,
                timestamps.size(),
                timestamps.size() / (double) fps,
                location.tasks(),
                stateNames,
                actionNames,
                timestamps,
                frameIndices,
                states,
                actions,
                videos
        );
    }

    @Transactional(readOnly = true)
    public LeRobotPointCloudDto pointCloud(String versionId, long episodeIndex, String requestedFeature) {
        DatasetSource source = requireSource(versionId);
        Path root = materialize(source.version());
        JsonNode info = readInfo(root);
        String feature = resolvePointCloudFeature(info, requestedFeature);
        int dimensions = pointCloudDimensions(info, feature);
        Path dataFile = episodeDataFile(root, info, episodeIndex);

        List<Long> frameIndices = new ArrayList<>();
        List<List<List<Double>>> frames = new ArrayList<>();
        long totalPoints = 0;
        String sql = "select frame_index, \"" + sqlIdentifier(feature) + "\" from read_parquet('"
                + sqlPath(dataFile) + "') order by frame_index";
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:");
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                List<Double> values = new ArrayList<>();
                flattenNumbers(rows.getObject(2), values);
                if (values.size() % dimensions != 0) {
                    throw new IllegalArgumentException("LeRobot point cloud value count does not match its shape");
                }
                List<List<Double>> points = new ArrayList<>(values.size() / dimensions);
                for (int offset = 0; offset < values.size(); offset += dimensions) {
                    points.add(new ArrayList<>(values.subList(offset, offset + dimensions)));
                }
                totalPoints += points.size();
                if (totalPoints > MAX_POINT_CLOUD_POINTS_PER_EPISODE) {
                    throw new IllegalArgumentException("LeRobot point cloud episode exceeds the preview point limit");
                }
                frameIndices.add(rows.getLong(1));
                frames.add(points);
            }
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("failed to read LeRobot point cloud data", exception);
        }
        if (frames.isEmpty()) {
            throw new IllegalArgumentException("LeRobot point cloud episode has no frames");
        }
        return new LeRobotPointCloudDto(episodeIndex, feature, dimensions, frameIndices, frames);
    }

    public MediaFile requireMedia(String versionId, String ticketValue) {
        if (ticketValue == null || ticketValue.isBlank()) {
            throw new IllegalArgumentException("media ticket is required");
        }
        MediaTicket ticket = mediaTickets.get(ticketValue);
        if (ticket == null || !ticket.versionId().equals(versionId) || ticket.expiresAt().isBefore(Instant.now())) {
            mediaTickets.remove(ticketValue);
            throw new IllegalArgumentException("media ticket is invalid or expired");
        }
        Path cacheDirectory = cacheRoot.resolve(
                cacheKey(ticket.versionId(), ticket.artifactSha256())
        ).normalize();
        Path root = readMaterializedRoot(cacheDirectory);
        Path file = safeResolve(root, ticket.relativePath());
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("LeRobot video does not exist");
        }
        String contentType = file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".mkv")
                ? "video/x-matroska"
                : "video/mp4";
        return new MediaFile(file, Files.exists(file) ? file.toFile().length() : 0L, contentType);
    }

    private String issueMediaUrl(DatasetSource source, String relativePath) {
        cleanupTickets();
        String ticket = UUID.randomUUID().toString().replace("-", "");
        mediaTickets.put(ticket, new MediaTicket(
                source.version().getId(),
                source.version().getArtifactSha256(),
                relativePath,
                Instant.now().plusSeconds(mediaTicketSeconds)
        ));
        return "/api/v2/dataset-versions/" + source.version().getId()
                + "/lerobot/media?ticket=" + ticket;
    }

    private void cleanupTickets() {
        Instant now = Instant.now();
        mediaTickets.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    }

    private DatasetSource requireSource(String versionId) {
        DatasetVersion version = versionRepo.findByIdAndDeletedFalse(versionId)
                .orElseThrow(() -> new IllegalArgumentException("dataset version not found or no permission"));
        DatasetAsset asset = assetRepo.findByIdAndDeletedFalse(version.getAssetId())
                .orElseThrow(() -> new IllegalArgumentException("dataset version not found or no permission"));
        if (!authContext.canAccessOwner(asset.getOwnerUserId())) {
            throw new IllegalArgumentException("dataset version not found or no permission");
        }
        if (!"LEROBOT".equals(asset.getType())) {
            throw new IllegalArgumentException("dataset type is not LEROBOT");
        }
        if (!"READY".equals(version.getStatus()) && !"DEPRECATED".equals(version.getStatus())) {
            throw new IllegalArgumentException("dataset version is not previewable");
        }
        if (version.getStoragePath() == null || version.getStoragePath().isBlank()) {
            throw new IllegalArgumentException("dataset version has no stored archive");
        }
        return new DatasetSource(version, asset);
    }

    private synchronized Path materialize(DatasetVersion version) {
        Path target = cacheRoot.resolve(cacheKey(version.getId(), version.getArtifactSha256())).normalize();
        Path marker = target.resolve(".ready");
        if (Files.isRegularFile(marker)) {
            try {
                Path cachedRoot = target.resolve(Files.readString(marker).trim()).normalize();
                requireLeRobotLayout(cachedRoot);
                return cachedRoot;
            } catch (Exception ignored) {
                deleteTree(target);
            }
        }
        Path staging = cacheRoot.resolve(target.getFileName() + "-" + UUID.randomUUID()).normalize();
        try {
            Files.createDirectories(staging);
            long totalBytes = 0;
            int entries = 0;
            try (InputStream input = minioService.downloadStream(version.getStoragePath());
                 ZipInputStream zip = new ZipInputStream(new BufferedInputStream(input))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    entries += 1;
                    if (entries > MAX_ARCHIVE_ENTRIES) {
                        throw new IllegalArgumentException("LeRobot archive contains too many entries");
                    }
                    String normalized = entry.getName().replace('\\', '/');
                    Path output = safeResolve(staging, normalized);
                    if (entry.isDirectory()) {
                        Files.createDirectories(output);
                    } else {
                        Files.createDirectories(output.getParent());
                        long copied = Files.copy(zip, output, StandardCopyOption.REPLACE_EXISTING);
                        totalBytes += copied;
                        if (totalBytes > MAX_UNCOMPRESSED_BYTES) {
                            throw new IllegalArgumentException("LeRobot archive exceeds the preview size limit");
                        }
                    }
                    zip.closeEntry();
                }
            }
            Path datasetRoot = findLeRobotRoot(staging);
            String relativeRoot = staging.relativize(datasetRoot).toString();
            Files.writeString(staging.resolve(".ready"), relativeRoot.isBlank() ? "." : relativeRoot);
            if (Files.exists(target)) {
                deleteTree(target);
            }
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            return target.resolve(relativeRoot).normalize();
        } catch (Exception exception) {
            deleteTree(staging);
            if (exception instanceof IllegalArgumentException illegalArgumentException) {
                throw illegalArgumentException;
            }
            throw new IllegalArgumentException("failed to prepare LeRobot preview", exception);
        }
    }

    private void requireLeRobotLayout(Path root) {
        boolean hasEpisodeMetadata = Files.isDirectory(root.resolve("meta/episodes"))
                || Files.isRegularFile(root.resolve("meta/episodes.jsonl"));
        if (!Files.isRegularFile(root.resolve("meta/info.json"))
                || !hasEpisodeMetadata
                || !Files.isDirectory(root.resolve("data"))
                || !Files.isDirectory(root.resolve("videos"))) {
            throw new IllegalArgumentException("archive is not a supported LeRobot dataset");
        }
    }

    private Path findLeRobotRoot(Path extractionRoot) throws IOException {
        try {
            requireLeRobotLayout(extractionRoot);
            return extractionRoot;
        } catch (IllegalArgumentException ignored) {
            // Archives downloaded from repository hosts commonly add one top-level directory.
        }
        try (var children = Files.list(extractionRoot)) {
            List<Path> candidates = children
                    .filter(Files::isDirectory)
                    .filter(path -> {
                        try {
                            requireLeRobotLayout(path);
                            return true;
                        } catch (IllegalArgumentException ignored) {
                            return false;
                        }
                    })
                    .toList();
            if (candidates.size() == 1) {
                return candidates.get(0);
            }
        }
        throw new IllegalArgumentException("archive is not a supported LeRobot dataset");
    }

    private JsonNode readInfo(Path root) {
        try {
            JsonNode info = objectMapper.readTree(root.resolve("meta/info.json").toFile());
            if (info == null || !info.hasNonNull("fps") || !info.has("features")) {
                throw new IllegalArgumentException("LeRobot meta/info.json is incomplete");
            }
            return info;
        } catch (IOException exception) {
            throw new IllegalArgumentException("failed to read LeRobot meta/info.json", exception);
        }
    }

    private List<LeRobotEpisodeSummaryDto> readEpisodeSummaries(Path root, int fps) {
        Path jsonl = root.resolve("meta/episodes.jsonl");
        if (Files.isRegularFile(jsonl)) {
            List<LeRobotEpisodeSummaryDto> result = new ArrayList<>();
            try (BufferedReader reader = Files.newBufferedReader(jsonl, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    JsonNode episode = objectMapper.readTree(line);
                    long length = episode.path("length").asLong(0);
                    List<String> tasks = new ArrayList<>();
                    episode.path("tasks").forEach(task -> tasks.add(task.asText()));
                    result.add(new LeRobotEpisodeSummaryDto(
                            episode.path("episode_index").asLong(),
                            length,
                            length / (double) fps,
                            tasks
                    ));
                }
                return result;
            } catch (IOException exception) {
                throw new IllegalArgumentException("failed to read LeRobot v2.1 episode metadata", exception);
            }
        }
        List<LeRobotEpisodeSummaryDto> result = new ArrayList<>();
        String sql = "select episode_index, length, tasks from read_parquet('"
                + parquetGlob(root, "meta/episodes") + "') order by episode_index";
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:");
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                long length = rows.getLong("length");
                result.add(new LeRobotEpisodeSummaryDto(
                        rows.getLong("episode_index"),
                        length,
                        length / (double) fps,
                        stringList(rows.getObject("tasks"))
                ));
            }
            return result;
        } catch (Exception exception) {
            throw new IllegalArgumentException("failed to read LeRobot episode metadata", exception);
        }
    }

    private LeRobotEpisodeDto readV21Episode(
            DatasetSource source,
            Path root,
            JsonNode info,
            int fps,
            long episodeIndex
    ) {
        long chunkIndex = episodeIndex / Math.max(1, info.path("chunks_size").asLong(1000));
        String dataTemplate = textValue(info, "data_path");
        String dataRelative = applyV21Template(dataTemplate, chunkIndex, episodeIndex, null);
        Path dataFile = safeResolve(root, dataRelative);
        if (!Files.isRegularFile(dataFile)) {
            throw new IllegalArgumentException("LeRobot v2.1 episode data file does not exist");
        }

        List<Double> timestamps = new ArrayList<>();
        List<Long> frameIndices = new ArrayList<>();
        List<List<Double>> states = new ArrayList<>();
        List<List<Double>> actions = new ArrayList<>();
        String sql = "select timestamp, frame_index, \"observation.state\", action "
                + "from read_parquet('" + sqlPath(dataFile) + "') order by frame_index";
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:");
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                timestamps.add(rows.getDouble(1));
                frameIndices.add(rows.getLong(2));
                states.add(numberList(rows.getObject(3)));
                actions.add(numberList(rows.getObject(4)));
            }
        } catch (Exception exception) {
            throw new IllegalArgumentException("failed to read LeRobot v2.1 episode data", exception);
        }
        if (timestamps.isEmpty()) {
            throw new IllegalArgumentException("LeRobot episode has no frames: " + episodeIndex);
        }

        Map<String, LeRobotVideoDto> videos = new LinkedHashMap<>();
        String videoTemplate = textValue(info, "video_path");
        info.path("features").fields().forEachRemaining(entry -> {
            if (!"video".equals(entry.getValue().path("dtype").asText())) return;
            String relative = applyV21Template(videoTemplate, chunkIndex, episodeIndex, entry.getKey());
            Path videoFile = safeResolve(root, relative);
            if (!Files.isRegularFile(videoFile) && relative.toLowerCase(Locale.ROOT).endsWith(".mp4")) {
                String mkvRelative = relative.substring(0, relative.length() - 4) + ".mkv";
                if (Files.isRegularFile(safeResolve(root, mkvRelative))) {
                    relative = mkvRelative;
                    videoFile = safeResolve(root, relative);
                }
            }
            // Browsers do not reliably support Matroska depth streams. Keep them for file preview,
            // but do not expose them as synchronized HTML video tracks.
            if (!Files.isRegularFile(videoFile) || relative.toLowerCase(Locale.ROOT).endsWith(".mkv")) return;
            videos.put(entry.getKey(), new LeRobotVideoDto(
                    entry.getKey(),
                    issueMediaUrl(source, relative),
                    0D,
                    timestamps.size() / (double) fps
            ));
        });

        List<String> tasks = readEpisodeSummaries(root, fps).stream()
                .filter(summary -> summary.episodeIndex() == episodeIndex)
                .findFirst()
                .map(LeRobotEpisodeSummaryDto::tasks)
                .orElse(List.of());
        return new LeRobotEpisodeDto(
                episodeIndex,
                fps,
                timestamps.size(),
                timestamps.size() / (double) fps,
                tasks,
                featureNames(info, "observation.state"),
                featureNames(info, "action"),
                timestamps,
                frameIndices,
                states,
                actions,
                videos
        );
    }

    private boolean isV21(JsonNode info) {
        return textValue(info, "codebase_version").startsWith("v2.");
    }

    private Path episodeDataFile(Path root, JsonNode info, long episodeIndex) {
        if (isV21(info)) {
            long chunkIndex = episodeIndex / Math.max(1, info.path("chunks_size").asLong(1000));
            return safeResolve(root, applyV21Template(
                    textValue(info, "data_path"), chunkIndex, episodeIndex, null
            ));
        }
        EpisodeLocation location = findEpisode(root, episodeIndex);
        return safeResolve(root, "data/chunk-%03d/file-%03d.parquet".formatted(
                location.dataChunkIndex(), location.dataFileIndex()
        ));
    }

    private String resolvePointCloudFeature(JsonNode info, String requestedFeature) {
        List<String> candidates = new ArrayList<>();
        info.path("features").fields().forEachRemaining(entry -> {
            String lower = entry.getKey().toLowerCase(Locale.ROOT);
            if ((lower.contains("point") || lower.contains("pcd") || lower.contains("cloud") || lower.contains("lidar"))
                    && isPointCloudShape(entry.getValue())) {
                candidates.add(entry.getKey());
            }
        });
        if (requestedFeature != null && !requestedFeature.isBlank()) {
            if (!candidates.contains(requestedFeature)) {
                throw new IllegalArgumentException("requested LeRobot point cloud feature is not available");
            }
            return requestedFeature;
        }
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("LeRobot dataset has no point cloud feature");
        }
        return candidates.get(0);
    }

    private boolean isPointCloudShape(JsonNode feature) {
        JsonNode shape = feature.path("shape");
        if (!shape.isArray() || shape.size() < 2) return false;
        int dimensions = shape.get(shape.size() - 1).asInt(0);
        return dimensions == 3 || dimensions == 6;
    }

    private int pointCloudDimensions(JsonNode info, String featureKey) {
        JsonNode shape = info.path("features").path(featureKey).path("shape");
        int dimensions = shape.get(shape.size() - 1).asInt(0);
        if (dimensions != 3 && dimensions != 6) {
            throw new IllegalArgumentException("unsupported LeRobot point cloud dimensions");
        }
        return dimensions;
    }

    private void flattenNumbers(Object value, List<Double> output) throws Exception {
        if (value == null) return;
        if (value instanceof Number number) {
            output.add(number.doubleValue());
            return;
        }
        if (value instanceof Array array) {
            Object raw = array.getArray();
            if (raw instanceof Object[] values) {
                for (Object item : values) flattenNumbers(item, output);
            } else {
                flattenJavaArray(raw, output);
            }
            return;
        }
        if (value instanceof List<?> list) {
            for (Object item : list) flattenNumbers(item, output);
            return;
        }
        if (value.getClass().isArray()) {
            flattenJavaArray(value, output);
            return;
        }
        throw new IllegalArgumentException("unsupported LeRobot point cloud value type");
    }

    private void flattenJavaArray(Object value, List<Double> output) throws Exception {
        int length = java.lang.reflect.Array.getLength(value);
        for (int index = 0; index < length; index++) {
            flattenNumbers(java.lang.reflect.Array.get(value, index), output);
        }
    }

    private String applyV21Template(
            String template,
            long chunkIndex,
            long episodeIndex,
            String videoKey
    ) {
        if (template == null || template.isBlank()) {
            throw new IllegalArgumentException("LeRobot v2.1 path template is missing");
        }
        String result = template
                .replace("{episode_chunk:03d}", "%03d".formatted(chunkIndex))
                .replace("{chunk_index:03d}", "%03d".formatted(chunkIndex))
                .replace("{episode_index:06d}", "%06d".formatted(episodeIndex));
        if (videoKey != null) {
            result = result.replace("{video_key}", videoKey);
        }
        if (result.contains("{")) {
            throw new IllegalArgumentException("unsupported LeRobot v2.1 path template: " + template);
        }
        return result;
    }

    private EpisodeLocation findEpisode(Path root, long episodeIndex) {
        JsonNode info = readInfo(root);
        List<String> videoKeys = new ArrayList<>();
        info.path("features").fields().forEachRemaining(entry -> {
            if ("video".equals(entry.getValue().path("dtype").asText())) {
                videoKeys.add(entry.getKey());
            }
        });
        StringBuilder select = new StringBuilder(
                "select episode_index, length, tasks, \"data/chunk_index\", \"data/file_index\""
        );
        for (String key : videoKeys) {
            select.append(", \"videos/").append(sqlIdentifier(key)).append("/chunk_index\"")
                    .append(", \"videos/").append(sqlIdentifier(key)).append("/file_index\"")
                    .append(", \"videos/").append(sqlIdentifier(key)).append("/from_timestamp\"")
                    .append(", \"videos/").append(sqlIdentifier(key)).append("/to_timestamp\"");
        }
        select.append(" from read_parquet('").append(parquetGlob(root, "meta/episodes"))
                .append("') where episode_index = ").append(episodeIndex);
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:");
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(select.toString())) {
            if (!row.next()) {
                throw new IllegalArgumentException("LeRobot episode does not exist: " + episodeIndex);
            }
            Map<String, VideoLocation> videos = new LinkedHashMap<>();
            for (String key : videoKeys) {
                String prefix = "videos/" + key + "/";
                videos.put(key, new VideoLocation(
                        row.getLong(prefix + "chunk_index"),
                        row.getLong(prefix + "file_index"),
                        row.getDouble(prefix + "from_timestamp"),
                        row.getDouble(prefix + "to_timestamp")
                ));
            }
            return new EpisodeLocation(
                    row.getLong("data/chunk_index"),
                    row.getLong("data/file_index"),
                    stringList(row.getObject("tasks")),
                    videos
            );
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("failed to locate LeRobot episode", exception);
        }
    }

    private List<String> featureNames(JsonNode info, String key) {
        JsonNode names = info.path("features").path(key).path("names");
        if (!names.isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        names.forEach(value -> result.add(value.asText()));
        return result;
    }

    private List<Double> numberList(Object value) throws Exception {
        Object[] values = arrayValues(value);
        List<Double> result = new ArrayList<>(values.length);
        for (Object item : values) {
            result.add(item == null ? 0D : ((Number) item).doubleValue());
        }
        return result;
    }

    private List<String> stringList(Object value) throws Exception {
        Object[] values = arrayValues(value);
        List<String> result = new ArrayList<>(values.length);
        for (Object item : values) {
            if (item != null) result.add(item.toString());
        }
        return result;
    }

    private Object[] arrayValues(Object value) throws Exception {
        if (value == null) return new Object[0];
        if (value instanceof Array array) return (Object[]) array.getArray();
        if (value instanceof Object[] array) return array;
        if (value instanceof List<?> list) return list.toArray();
        return new Object[]{value};
    }

    private Path safeResolve(Path root, String relative) {
        if (relative == null || relative.isBlank() || relative.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid LeRobot archive path");
        }
        String normalizedRelative = relative.replace('\\', '/');
        if (normalizedRelative.startsWith("/") || normalizedRelative.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("invalid LeRobot archive path");
        }
        Path target = root.resolve(normalizedRelative).normalize();
        if (!target.startsWith(root.normalize())) {
            throw new IllegalArgumentException("invalid LeRobot archive path");
        }
        return target;
    }

    private String cacheKey(String versionId, String sha256) {
        String key = (versionId + "-" + (sha256 == null ? "unknown" : sha256))
                .replaceAll("[^A-Za-z0-9._-]", "_");
        return key.length() <= 180 ? key : key.substring(0, 180);
    }

    private String sqlPath(Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/').replace("'", "''");
    }

    private String parquetGlob(Path root, String directory) {
        return sqlPath(safeResolve(root, directory)) + "/**/*.parquet";
    }

    private Path readMaterializedRoot(Path cacheDirectory) {
        Path marker = cacheDirectory.resolve(".ready");
        try {
            if (!Files.isRegularFile(marker)) {
                throw new IllegalArgumentException("LeRobot preview cache is not ready");
            }
            Path root = cacheDirectory.resolve(Files.readString(marker).trim()).normalize();
            if (!root.startsWith(cacheDirectory)) {
                throw new IllegalArgumentException("invalid LeRobot preview cache marker");
            }
            requireLeRobotLayout(root);
            return root;
        } catch (IOException exception) {
            throw new IllegalArgumentException("failed to read LeRobot preview cache", exception);
        }
    }

    private String sqlIdentifier(String value) {
        return value.replace("\"", "\"\"");
    }

    private String textValue(JsonNode node, String field) {
        return node.path(field).asText("");
    }

    private int intValue(JsonNode node, String field) {
        int value = node.path(field).asInt(0);
        if (value <= 0) throw new IllegalArgumentException("LeRobot " + field + " must be positive");
        return value;
    }

    private long longValue(JsonNode node, String field) {
        return node.path(field).asLong(0);
    }

    private void deleteTree(Path root) {
        if (root == null || !Files.exists(root) || !root.normalize().startsWith(cacheRoot)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(Collections.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    private record DatasetSource(DatasetVersion version, DatasetAsset asset) { }
    private record MediaTicket(String versionId, String artifactSha256, String relativePath, Instant expiresAt) { }
    private record EpisodeLocation(long dataChunkIndex, long dataFileIndex, List<String> tasks, Map<String, VideoLocation> videos) { }
    private record VideoLocation(long chunkIndex, long fileIndex, double fromTimestamp, double toTimestamp) { }
    public record MediaFile(Path path, long size, String contentType) { }
}
