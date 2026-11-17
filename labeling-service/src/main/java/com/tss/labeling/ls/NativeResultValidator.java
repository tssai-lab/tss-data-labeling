package com.tss.labeling.ls;

import com.fasterxml.jackson.databind.JsonNode;
import com.tss.labeling.imports.NativeTaskService;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.UUID;
import static com.tss.labeling.tasks.TaskService.error;

/** Native shape/type rules use the pinned SDK; platform adds stage, input and completion context. */
@Component
public class NativeResultValidator {
    public enum Stage { DRAFT, COMPLETE, SUBMIT }
    private final LsSourceAdapter source;private final NativeTaskService inputs;
    public NativeResultValidator(LsSourceAdapter source,NativeTaskService inputs){this.source=source;this.inputs=inputs;}
    public void validate(JsonNode config,JsonNode envelope,boolean complete){validate(config,envelope,complete,null);}
    public void validate(JsonNode config,JsonNode envelope,boolean complete,UUID sample){validate(config,envelope,complete?Stage.COMPLETE:Stage.DRAFT,sample);}
    public void validate(JsonNode config,JsonNode envelope,Stage stage,UUID sample){
        if(envelope==null||!envelope.isObject()||!envelope.path("result").isArray())throw error(422,"INVALID_LS_RESULT","结果必须包含原生 LS result 数组");
        source.validateNativeResult(config,envelope,stage.name().toLowerCase(java.util.Locale.ROOT),
                sample==null?null:inputs.rawData(sample),sample==null?null:inputs.resultMetadata(sample,config),
                sample==null?Map.of():inputs.timeResources(sample,config));
    }
}
