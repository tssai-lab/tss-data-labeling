package com.tss.labeling.plugins;

import com.tss.labeling.tasks.TaskTemplates;
import com.tss.labeling.imports.*;
import com.tss.labeling.common.WorkflowStore;
import com.tss.labeling.ls.NativeResultValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BuiltInPlugins {
    @Bean TaskPlugin structuredRecordsPlugin(RecordArchiveParser parser, WorkflowStore store, com.tss.labeling.ls.LsSourceAdapter source, ImportStorage storage) {
        return new RecordDataPlugin(descriptor("STRUCTURED", "结构化数据标注"), parser, store, source, storage);
    }
    @Bean TaskPlugin semiStructuredDocumentsPlugin(RecordArchiveParser parser, WorkflowStore store, com.tss.labeling.ls.LsSourceAdapter source, ImportStorage storage) {
        return new RecordDataPlugin(descriptor("SEMI_STRUCTURED", "半结构化数据标注"), parser, store, source, storage);
    }
    static TaskPlugin.Descriptor descriptor(String type, String name) {
        var binding = TaskTemplates.forType(type);
        return new TaskPlugin.Descriptor((String)binding.get("templateId"), (String)binding.get("templateVersion"), name, type,
                (String)binding.get("inputFormat"), (String)binding.get("resultFormat"),
                (String)binding.get("inputSchemaId"), (String)binding.get("resultSchemaId"), (String)binding.get("editorKey"),
                true, true, new TaskPlugin.Capabilities(true, true, true, true, true));
    }
    @Bean TaskPlugin computerVisionPlugin(ArchiveParser parser, WorkflowStore store, ImportStorage storage, NativeResultValidator validator) {
        return new NativeTaskPlugin(descriptor("COMPUTER_VISION", "图像标注"), parser, store, storage, validator);
    }
    @Bean TaskPlugin timeSeriesPlugin(ArchiveParser parser, WorkflowStore store, ImportStorage storage, NativeResultValidator validator) {
        return new NativeTaskPlugin(descriptor("TIME_SERIES", "时序标注"), parser, store, storage, validator);
    }
}
