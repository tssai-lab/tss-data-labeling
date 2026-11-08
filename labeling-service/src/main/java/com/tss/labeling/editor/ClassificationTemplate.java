package com.tss.labeling.editor;
import com.fasterxml.jackson.databind.JsonNode;
import com.tss.labeling.common.BusinessException;
import org.springframework.http.HttpStatus;
import java.util.HashSet;
public final class ClassificationTemplate {
    private ClassificationTemplate() {}
    public static String xml(JsonNode labels,String instructions) {
        if(!labels.isArray()||labels.isEmpty())throw invalid();
        var names=new HashSet<String>();var ids=new HashSet<String>();
        StringBuilder xml=new StringBuilder("<View><Header value=\"").append(escape(instructions==null?"":instructions)).append("\"/><Image name=\"image\" value=\"$image\"/><Choices name=\"category\" toName=\"image\" choice=\"single\" required=\"true\">");
        for(var label:labels){String name=label.path("name").asText(),id=label.path("id").asText();
            if(name.isBlank()||id.isBlank()||!names.add(name)||!ids.add(id))throw invalid();
            xml.append("<Choice value=\"").append(escape(name)).append("\"/>");}
        return xml.append("</Choices></View>").toString();
    }
    private static String escape(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;");}
    private static BusinessException invalid(){return new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY,"INVALID_RESULT","标签ID和名称必须有效且唯一");}
}
