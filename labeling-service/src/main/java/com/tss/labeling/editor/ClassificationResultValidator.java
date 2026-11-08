package com.tss.labeling.editor;
import com.fasterxml.jackson.databind.*;
import com.tss.labeling.common.BusinessException;
import org.springframework.http.HttpStatus;

/** Validates image classification content independently of projects, claims or authentication. */
public final class ClassificationResultValidator {
    private ClassificationResultValidator() {}
    public static JsonNode canonical(JsonNode result,JsonNode labels) {
        if(!result.isArray()||result.size()!=1)throw fail(422,"INVALID_RESULT","单选分类只接受一个结果");var r=result.get(0);
        if(!"category".equals(r.path("from_name").asText())||!"image".equals(r.path("to_name").asText())||!"choices".equals(r.path("type").asText()))throw fail(422,"INVALID_RESULT","分类格式不匹配");
        var choices=r.path("value").path("choices");
        if(!choices.isArray()||choices.size()!=1||!choices.get(0).isTextual())throw fail(422,"INVALID_RESULT","请选择一个合法类别");
        String name=choices.get(0).asText();boolean valid=false;for(var label:labels)if(name.equals(label.path("name").asText()))valid=true;
        if(!valid)throw fail(422,"INVALID_RESULT","类别不属于项目规范");
        var mapper=new ObjectMapper();var array=mapper.createArrayNode();var item=array.addObject();
        item.put("id","category");item.put("from_name","category");item.put("to_name","image");item.put("type","choices");
        item.putObject("value").putArray("choices").add(name);return array;
    }
    private static BusinessException fail(int status,String code,String message) {
        return new BusinessException(HttpStatus.valueOf(status),code,message);
    }
}
