package com.tss.labeling.editor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EditorResourcePackagingTest {
    private byte[] resource(String path)throws Exception {
        try(var in=getClass().getClassLoader().getResourceAsStream("static/editor-assets/"+path)) {
            assertNotNull(in,"Missing editor classpath resource: "+path);
            return in.readAllBytes();
        }
    }
    @Test void fixedEditorRuntimeAndAllChunksArePresentAndMatchManifest()throws Exception {
        var manifest=new ObjectMapper().readTree(resource("asset-manifest.json"));
        assertEquals("1.23.2",manifest.path("version").asText());
        assertEquals("df96d79183156e556d91825d15fa7721f1145e1a",manifest.path("sourceRevision").asText());
        var paths=new HashSet<String>();
        for(var entry:manifest.path("files")) {
            String path=entry.path("path").asText();assertTrue(paths.add(path));
            assertFalse(path.contains(".."));assertFalse(path.endsWith(".map"));
            byte[] bytes=resource(path);
            assertEquals(entry.path("bytes").asLong(),bytes.length,path);
            assertEquals(entry.path("sha256").asText(),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),path);
        }
        assertTrue(paths.containsAll(List.of("main.js","main.css","LICENSE.txt","3rdpartylicenses.txt")));
        assertTrue(paths.stream().anyMatch(path->path.matches("[0-9]+\\.js")),"Missing lazy-loaded chunks");
        var references=java.util.regex.Pattern.compile("url\\(([^)]+)\\)").matcher(new String(resource("main.css"),java.nio.charset.StandardCharsets.UTF_8));
        while(references.find()) {
            String path=references.group(1).trim().replace("\"","").replace("'","");
            if(path.startsWith("data:")||path.startsWith("http:")||path.startsWith("https:")||path.startsWith("#"))continue;
            if(path.startsWith("./"))path=path.substring(2);
            assertTrue(paths.contains(path),"CSS references missing runtime resource: "+path);
        }
    }
}
