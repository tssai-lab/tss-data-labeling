package com.tss.labeling.imports;

import static com.tss.labeling.imports.ArchiveParser.rejected;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tss.labeling.common.WorkflowStore;

import org.apache.commons.compress.archivers.zip.*;
import org.apache.commons.csv.*;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.time.*;
import java.util.*;

@Component
public class EnergyArchiveParser {
    static final String SOURCE = "sample/station_day_tasks_2k.csv";
    private final ImportSettings settings;
    private final ImportStorage storage;
    private final ObjectMapper json;

    public EnergyArchiveParser(ImportSettings settings, ImportStorage storage, ObjectMapper json) {
        this.settings = settings;
        this.storage = storage;
        this.json = json;
    }

    public static class Invalid extends RuntimeException {
        final String code, field;
        final long row;

        Invalid(String code, long row, String field, String message) {
            super(message);
            this.code = code;
            this.row = row;
            this.field = field;
        }
    }

    private static void invalid(long row, String field, String message) {
        throw new Invalid("INVALID_ENERGY_INPUT", row, field, message);
    }

    ArchiveParser.ParsedArchive parse(UUID batch, UUID project) throws IOException {
        ZipSafety.check(storage.archive(batch), settings);
        try (var zip =
                ZipFile.builder()
                        .setPath(storage.archive(batch))
                        .setCharset(settings.zipEncoding())
                        .get()) {
            Set<String> paths = new HashSet<>();
            Map<String, ZipArchiveEntry> files = new HashMap<>();
            long expanded = 0;
            int count = 0;
            var entries = zip.getEntries();
            while (entries.hasMoreElements()) {
                var e = entries.nextElement();
                String path = ArchiveParser.sourcePath(e.getName());
                int mode = e.getUnixMode() & 0170000;
                if (++count > settings.maxEntries()
                        || e.getSize() < 0
                        || e.getSize() > settings.maxFileBytes()
                        || e.getSize() > settings.maxExpandedBytes() - expanded)
                    throw rejected("EXPANSION_LIMIT", "展开归档超过上限");
                expanded += e.getSize();
                if (!paths.add(path)
                        || e.isUnixSymlink()
                        || (mode != 0 && mode != 0100000 && mode != 0040000))
                    throw rejected("UNSAFE_ARCHIVE", "归档路径重复或含特殊文件");
                if (!zip.canReadEntryData(e)) throw rejected("UNSUPPORTED_FORMAT", "归档包含不支持的压缩条目");
                if (!e.isDirectory()) files.put(path, e);
            }
            if (!files.containsKey(SOURCE) || !files.containsKey("manifest.json"))
                invalid(0, "archive", "归档必须包含 manifest.json 与 " + SOURCE);
            var manifest = json.readTree(bytes(zip, files.get("manifest.json")));
            String version =
                    manifest.path("datasetVersion")
                            .asText(manifest.path("dataset_version").asText());
            if (version.isBlank() || version.length() > 200)
                invalid(0, "datasetVersion", "manifest 缺少数据集版本");
            if (manifest.has("datasetVersion")
                    && manifest.has("dataset_version")
                    && !manifest.path("datasetVersion").equals(manifest.path("dataset_version")))
                invalid(0, "datasetVersion", "manifest 版本字段冲突");
            String csv;
            try {
                csv =
                        StandardCharsets.UTF_8
                                .newDecoder()
                                .onMalformedInput(CodingErrorAction.REPORT)
                                .decode(ByteBuffer.wrap(bytes(zip, files.get(SOURCE))))
                                .toString();
            } catch (CharacterCodingException ex) {
                invalid(0, "encoding", "CSV 必须为 UTF-8");
                return null;
            }
            if (csv.startsWith("\uFEFF")) csv = csv.substring(1);
            var format =
                    CSVFormat.RFC4180
                            .builder()
                            .setHeader()
                            .setSkipHeaderRecord(true)
                            .setDuplicateHeaderMode(DuplicateHeaderMode.DISALLOW)
                            .get();
            List<ArchiveParser.ParsedSample> samples = new ArrayList<>();
            Set<String> tasks = new HashSet<>(), days = new HashSet<>();
            try (var parser = CSVParser.parse(csv, format)) {
                Set<String> headers = parser.getHeaderMap().keySet();
                for (String field :
                        List.of(
                                "task_id",
                                "station_key",
                                "enterprise_key",
                                "station_level",
                                "date_local",
                                "daily_kwh"))
                    if (!headers.contains(field)) invalid(1, field, "缺少必需列");
                for (int hour = 0; hour < 24; hour++) {
                    String field = String.format(Locale.ROOT, "h%02d", hour);
                    if (!headers.contains(field)) invalid(1, field, "缺少小时列");
                }
                for (var record : parser) {
                    long row = record.getRecordNumber() + 1;
                    if (!record.isConsistent()) invalid(row, "columns", "列数与表头不一致");
                    if (samples.size() >= 10000) invalid(row, "rows", "一次最多导入 10000 条站点日");
                    for (String field :
                            List.of("label", "label_detail", "annotator", "confidence", "notes"))
                        if (headers.contains(field) && !record.get(field).isBlank())
                            throw new Invalid(
                                    "EXISTING_ANNOTATIONS_UNSUPPORTED", row, field, "本轮不支持导入已有标注");
                    String task = required(record, "task_id", row),
                            station = required(record, "station_key", row),
                            enterprise = required(record, "enterprise_key", row),
                            level = required(record, "station_level", row),
                            date = required(record, "date_local", row);
                    if (!Set.of("enterprise", "device").contains(level))
                        invalid(row, "station_level", "层级必须为 enterprise 或 device");
                    if(!date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))invalid(row,"date_local","日期应为 YYYY-MM-DD");
                    LocalDate day;
                    try {
                        day = LocalDate.parse(date);
                        if (!date.equals(day.toString())) throw new IllegalArgumentException();
                    } catch (Exception ex) {
                        invalid(row, "date_local", "日期应为 YYYY-MM-DD");
                        return null;
                    }
                    if (!tasks.add(task) || !days.add(station + "\n" + date))
                        throw new Invalid(
                                "DUPLICATE_ENERGY_SAMPLE", row, "task_id", "同一数据集版本的任务或站点日重复");
                    List<Double> values = new ArrayList<>();
                    for (int hour = 0; hour < 24; hour++)
                        values.add(number(record, String.format(Locale.ROOT, "h%02d", hour), row));
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("stationKey", station);
                    payload.put("enterpriseKey", enterprise);
                    payload.put("stationLevel", level);
                    payload.put("dateLocal", date);
                    payload.put("timezone", "Asia/Shanghai");
                    payload.put("dailyKwh", number(record, "daily_kwh", row));
                    payload.put(
                            "series",
                            Map.of(
                                    "name",
                                    "load_curve",
                                    "channel",
                                    "value_kwh",
                                    "unit",
                                    "kWh",
                                    "start",
                                    date + "T00:00:00+08:00",
                                    "periodSeconds",
                                    3600,
                                    "values",
                                    values));
                    payload.put(
                            "qualityReasons",
                            values.contains(null) ? List.of("MISSING_HOURLY_VALUES") : List.of());
                    Map<String, Object> source =
                            Map.of(
                                    "datasetVersion",
                                    version,
                                    "sourceTaskId",
                                    task,
                                    "sourceFile",
                                    SOURCE,
                                    "sourceRow",
                                    row);
                    Map<String, Object> input = new LinkedHashMap<>();
                    input.put("inputFormat", "energy-station-day-input-v1");
                    input.put("schemaId", "energy-station-day-input@1.0.0");
                    input.put("source", source);
                    input.put("payload", payload);
                    input.put(
                            "context", Map.of("status", "NOT_PROVIDED", "availableHistoryDays", 0));
                    String hash =
                            WorkflowStore.digest(
                                    json.writer()
                                            .with(
                                                    com.fasterxml.jackson.databind
                                                            .SerializationFeature
                                                            .ORDER_MAP_ENTRIES_BY_KEYS)
                                            .writeValueAsBytes(input));
                    input.put("inputHash", hash);
                    UUID id =
                            UUID.nameUUIDFromBytes(
                                    (project + "\n" + version + "\n" + task)
                                            .getBytes(StandardCharsets.UTF_8));
                    input.put("sampleId", id);
                    samples.add(
                            new ArchiveParser.ParsedSample(
                                    id,
                                    SOURCE + "#row=" + row,
                                    batch + "/archive.zip",
                                    hash,
                                    "application/json",
                                    input));
                }
            } catch (IllegalArgumentException | UncheckedIOException ex) {
                invalid(0, "csv", "CSV 格式无效");
            }
            if (samples.isEmpty()) invalid(1, "rows", "CSV 不含样本");
            return new ArchiveParser.ParsedArchive(samples, List.of(), 0);
        }
    }

    private byte[] bytes(ZipFile zip, ZipArchiveEntry entry) throws IOException {
        try (var input = zip.getInputStream(entry)) {
            byte[] bytes =
                    input.readNBytes(
                            (int) Math.min(settings.maxFileBytes() + 1, Integer.MAX_VALUE));
            var crc = new java.util.zip.CRC32();
            crc.update(bytes);
            if (bytes.length > settings.maxFileBytes()
                    || bytes.length != entry.getSize()
                    || crc.getValue() != entry.getCrc())
                throw rejected("INVALID_ARCHIVE", "归档条目大小或 CRC 校验失败");
            return bytes;
        }
    }

    private static String required(CSVRecord record, String field, long row) {
        String v = record.get(field).trim();
        if (v.isEmpty() || v.length() > 500) invalid(row, field, "字段不能为空且不能超过500字");
        return v;
    }

    private static Double number(CSVRecord record, String field, long row) {
        String v = record.get(field).trim();
        if (v.isEmpty()) return null;
        try {
            double value = Double.parseDouble(v);
            if (!Double.isFinite(value) || value < 0) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException ex) {
            invalid(row, field, "非空数值必须有限且非负");
            return null;
        }
    }
}
