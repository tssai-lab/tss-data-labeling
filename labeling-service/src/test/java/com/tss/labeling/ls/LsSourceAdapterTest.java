package com.tss.labeling.ls;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tss.labeling.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Explicitly enabled with -Dls.test.python=...; uses real vendored Python source. */
@EnabledIfSystemProperty(named = "ls.test.python", matches = ".+")
class LsSourceAdapterTest {
    @TempDir Path temporary;
    private final ObjectMapper json = new ObjectMapper();
    private static final String XML = """
            <View><TimeSeries name="sensor" value="$readings" valueType="json" timeColumn="t">
            <Channel column="v"/></TimeSeries><Choices name="state" toName="sensor">
            <Choice value="正常"/><Choice value="异常"/></Choices></View>
            """;

    private LsSourceAdapter adapter() {
        return new LsSourceAdapter(json, System.getProperty("ls.test.python"),
                Path.of("ls-source-adapter").toAbsolutePath().toString(), temporary.toString(), 30);
    }

    @Test void parsesNativeBindingsAndReportsTaskDependentFormats() throws Exception {
        JsonNode parsed = adapter().inspect(XML);
        assertEquals("readings", parsed.at("/parsedConfig/state/inputs/0/value").asText());
        assertTrue(parsed.path("formats").findValuesAsText("name").contains("CSV"));
        try (var paths = Files.list(temporary)) { assertEquals(0, paths.count()); }
    }

    @Test void persistentWorkerSurvivesRejectedInputAndClosesWithTheBackend() throws Exception {
        var adapter=adapter();org.springframework.test.util.ReflectionTestUtils.setField(adapter,"workerEnabled",true);
        try{
            assertTrue(adapter.validateConfig(XML).has("parsedConfig"));
            assertThrows(BusinessException.class,()->adapter.validateConfig("<View><Unknown name=\"x\"/></View>"));
            assertTrue(adapter.validateConfig(XML).has("parsedConfig"));
            var workers=(java.util.Set<?>)org.springframework.test.util.ReflectionTestUtils.getField(adapter,"allWorkers");assertEquals(1,workers.size());
        }finally{adapter.close();}
        assertTrue(((java.util.Set<?>)org.springframework.test.util.ReflectionTestUtils.getField(adapter,"allWorkers")).isEmpty());
        try(var paths=Files.list(temporary)){assertEquals(0,paths.filter(path->path.getFileName().toString().startsWith("job-")).count());}
    }

    @Test void exportsActualUnicodeCsvAndPreservesRawLsJson() throws Exception {
        JsonNode tasks = json.readTree("""
                [{"id":"sample-1","data":{"readings":[{"t":0.2,"v":-4}],"station":"站点甲"},
                  "annotations":[{"id":1,"completed_by":9,"result":[
                  {"from_name":"state","to_name":"sensor","type":"choices","value":{"choices":["异常"]}}]}]}]
                """);
        for (String format : new String[]{"CSV", "JSON"}) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            adapter().writeExportZip(XML, format, tasks, Map.of(), bytes);
            Map<String, String> files = new HashMap<>();
            try (var zip = new ZipInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray()), StandardCharsets.UTF_8)) {
                for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                    files.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
            if (format.equals("JSON")) assertEquals(tasks, json.readTree(files.get("result.json")));
            else {
                assertTrue(files.values().stream().anyMatch(value -> value.contains("异常") && value.contains("站点甲")));
            }
        }
        try (var paths = Files.list(temporary)) { assertEquals(0, paths.count()); }
    }

    @Test void invalidXmlRejectedWithoutLeakingFiles() throws Exception {
        BusinessException error = assertThrows(BusinessException.class,
                () -> adapter().inspect("<!DOCTYPE View><View/>"));
        assertEquals("INVALID_LABEL_CONFIG", error.code());
        assertEquals(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, error.status());
        assertTrue(error.getMessage().contains("XML_DECLARATION_NOT_ALLOWED"));
        try (var paths = Files.list(temporary)) { assertEquals(0, paths.count()); }
    }

    @Test void missingPythonFailsClearly() {
        LsSourceAdapter missing = new LsSourceAdapter(json, "nonexistent-labeling-python",
                Path.of("ls-source-adapter").toAbsolutePath().toString(), temporary.toString(), 30);
        assertEquals("LS_SOURCE_IO", assertThrows(BusinessException.class, () -> missing.inspect(XML)).code());
    }

    @Test void timeoutTerminatesLocalWorker() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("source"));
        Files.writeString(source.resolve("adapter.py"), "import time\ntime.sleep(30)\n");
        Path jobs = temporary.resolve("jobs");
        LsSourceAdapter slow = new LsSourceAdapter(json, System.getProperty("ls.test.python"),
                source.toString(), jobs.toString(), 1);
        assertEquals("LS_SOURCE_TIMEOUT", assertThrows(BusinessException.class, () -> slow.inspect(XML)).code());
        try (var paths = Files.list(jobs)) { assertEquals(0, paths.count()); }
    }
}
