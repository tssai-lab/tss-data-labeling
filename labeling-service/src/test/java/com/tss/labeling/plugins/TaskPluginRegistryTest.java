package com.tss.labeling.plugins;

import org.junit.jupiter.api.Test;
import com.tss.labeling.tasks.TaskTemplates;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.tss.labeling.imports.ArchiveParser;

class TaskPluginRegistryTest {
    private TaskPlugin plugin(TaskPlugin.Descriptor descriptor) {
        return new TaskPlugin() {
            public Descriptor descriptor() { return descriptor; }
            public Set<String> exportFormats() { return Set.of(descriptor.id()+"-export"); }
            public ArchiveParser.ParsedArchive parseArchive(UUID batch,UUID project) { throw new UnsupportedOperationException(); }
            public JsonNode readInput(UUID project,UUID sample,String path) { throw new UnsupportedOperationException(); }
            public void validateResult(UUID spec,UUID sample,Set<String> labels,JsonNode result,boolean done) { throw new UnsupportedOperationException(); }
            public ExportData export(String format,List<Map<String,Object>> rows) { throw new UnsupportedOperationException(); }
        };
    }
    private TaskPlugin.Descriptor version(String version, boolean active, boolean ready, boolean current) {
        var old=BuiltInPlugins.descriptor("IMAGE_CLASSIFICATION","图片分类");
        return new TaskPlugin.Descriptor(old.id(),version,old.name(),old.taskType(),old.inputFormat(),old.resultFormat(),old.inputSchemaId(),old.resultSchemaId(),old.editorKey(),active,current,new TaskPlugin.Capabilities(true,true,true,true,ready));
    }
    @Test void duplicateIdsVersionsAndDefaultsFailAtStartup() {
        var first=plugin(version("1.0.0",true,true,true));
        assertThrows(IllegalStateException.class,()->new TaskPluginRegistry(List.of(first,first)));
        assertThrows(IllegalStateException.class,()->new TaskPluginRegistry(List.of(first,plugin(version("2.0.0",true,true,true)))));
    }
    @Test void unavailableAndIncompletePluginsCannotCreateProjectsButRemainDiscoverable() {
        var registry=new TaskPluginRegistry(List.of(plugin(version("1.0.0",false,true,true)),plugin(version("2.0.0",true,false,false))));
        assertEquals(2,registry.templates().size());
        assertFalse((Boolean)registry.templates().get(0).get("enabled"));
        assertFalse((Boolean)registry.templates().get(1).get("enabled"));
        assertThrows(RuntimeException.class,()->registry.forCreation("IMAGE_CLASSIFICATION",null,null));
        assertThrows(RuntimeException.class,()->registry.forCreation("IMAGE_CLASSIFICATION","image-classification","2.0.0"));
        assertNotNull(registry.resolve("IMAGE_CLASSIFICATION","image-classification","1.0.0"));
    }
    @Test void frozenVersionNeverFallsBackAndBindingTamperingIsRejected() {
        var first=plugin(version("1.0.0",true,true,true));
        var second=plugin(version("2.0.0",true,true,false));
        var registry=new TaskPluginRegistry(List.of(first,second));
        var frozen=new HashMap<String,Object>();
        frozen.put("task_type","IMAGE_CLASSIFICATION");frozen.put("template_id","image-classification");frozen.put("template_version","1.0.0");
        frozen.put("schema_digest",first.descriptor().digest());
        assertSame(first,registry.frozen(frozen));
        frozen.put("template_version","3.0.0");
        assertThrows(RuntimeException.class,()->registry.frozen(frozen));
        frozen.put("template_version","1.0.0");frozen.put("input_schema_id","wrong-schema");
        assertThrows(RuntimeException.class,()->registry.frozen(frozen));
        assertThrows(RuntimeException.class,()->registry.resolve("TIME_SERIES","image-classification","1.0.0"));
        assertThrows(RuntimeException.class,()->registry.resolve("IMAGE_CLASSIFICATION","image-classification",null));
    }
    @Test void builtInDigestsStayCompatibleWithExistingFrozenSpecifications() {
        for(String type:List.of("IMAGE_CLASSIFICATION","TIMESERIES_ANNOTATION","COMPUTER_VISION","TIME_SERIES"))
            assertEquals(TaskTemplates.digest(type),BuiltInPlugins.descriptor(type,"显示名不改变契约").digest());
    }
    @Test void creationCatalogueUsesGenericFamiliesAndRetainsHistoricalBindings() {
        var types=List.of("IMAGE_CLASSIFICATION","TIMESERIES_ANNOTATION","COMPUTER_VISION","TIME_SERIES");
        var registry=new TaskPluginRegistry(types.stream().map(type->plugin(BuiltInPlugins.descriptor(type,type))).toList());
        assertEquals(4,registry.templates().size());
        var available=registry.templates().stream().filter(item->Boolean.TRUE.equals(item.get("creationAvailable"))).toList();
        assertEquals(Set.of("COMPUTER_VISION","TIME_SERIES"),available.stream().map(item->item.get("taskType")).collect(java.util.stream.Collectors.toSet()));
        assertEquals("image-classification-v1",registry.resolve("IMAGE_CLASSIFICATION","image-classification","1.0.0").descriptor().resultFormat());
        assertEquals("annotation-record-v1",registry.resolve("TIMESERIES_ANNOTATION","energy-station-day","1.0.0").descriptor().resultFormat());
    }
}
