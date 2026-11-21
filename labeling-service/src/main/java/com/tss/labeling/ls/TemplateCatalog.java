package com.tss.labeling.ls;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import static com.tss.labeling.tasks.TaskService.error;

/** Authoritative catalog is the pinned LS YAML; cache once per application process. */
@Service
public class TemplateCatalog {
    private final LsSourceAdapter source;
    private volatile JsonNode cache;
    public TemplateCatalog(LsSourceAdapter source){this.source=source;}
    public JsonNode all(){
        if(cache==null)synchronized(this){if(cache==null)cache=source.templates();}
        return cache.deepCopy();
    }
    public JsonNode find(String id){
        for(JsonNode template:all().path("templates"))if(id.equals(template.path("id").asText()))return template;
        throw error(422,"TEMPLATE_NOT_REGISTERED","模板未登记，请重新选择模板或使用自定义配置");
    }
}
