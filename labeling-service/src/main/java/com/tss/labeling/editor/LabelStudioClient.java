package com.tss.labeling.editor;

import com.fasterxml.jackson.databind.*;
import com.tss.labeling.common.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

/** Server-side transport identity only. Platform actor is checked and mapped separately. */
@Component
public class LabelStudioClient {
    private final String base,username,password;
    private final ObjectMapper json;
    public LabelStudioClient(ObjectMapper json,
            @Value("${labeling.editor.base-url:http://127.0.0.1:8085}") String base,
            @Value("${labeling.editor.username:}") String username,
            @Value("${labeling.editor.password:}") String password) {
        this.json=json;this.base=base.replaceAll("/$","");this.username=username;this.password=password;
    }
    public Connection connect() {
        try {
            if(username.isBlank()||password.isBlank())throw unavailable();
            var cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
            var http=HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(8))
                    .followRedirects(HttpClient.Redirect.NEVER).build();
            var connection=new Connection(http,"");
            var page=connection.raw("GET","/user/login/",null,null);
            var matcher=Pattern.compile("name=[\"']csrfmiddlewaretoken[\"']\\s+value=[\"']([^\"']+)").matcher(page.body());
            if(!matcher.find())throw unavailable();
            String form="email="+encode(username)+"&password="+encode(password)+"&csrfmiddlewaretoken="+encode(matcher.group(1));
            var response=connection.raw("POST","/user/login/",form.getBytes(StandardCharsets.UTF_8),"application/x-www-form-urlencoded");
            if(response.statusCode()!=302)throw unavailable();
            String csrf=cookies.getCookieStore().getCookies().stream().filter(c->c.getName().equals("csrftoken")).map(HttpCookie::getValue).findFirst().orElseThrow();
            return new Connection(http,csrf);
        }catch(BusinessException e){throw e;}catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();throw unavailable();}
    }
    public class Connection {
        private final HttpClient http;private final String csrf;
        Connection(HttpClient http,String csrf){this.http=http;this.csrf=csrf;}
        HttpResponse<String> raw(String method,String path,byte[] bytes,String type)throws Exception {
            var request=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(30)).header("Referer",base+"/");
            if(type!=null)request.header("Content-Type",type);if(!csrf.isEmpty())request.header("X-CSRFToken",csrf);
            request.method(method,bytes==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(bytes));
            return http.send(request.build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
        public JsonNode call(String method,String path,Object value) {
            try {
                var response=raw(method,path,value==null?null:json.writeValueAsBytes(value),value==null?null:"application/json");
                if(response.statusCode()<200||response.statusCode()>=300)throw unavailable();
                return json.readTree(response.body());
            }catch(BusinessException e){throw e;}catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();throw unavailable();}
        }
        public String importImage(String project,byte[] image,String type,String filename) {
            try {
                String boundary="platform"+UUID.randomUUID().toString().replace("-","");var body=new ByteArrayOutputStream();
                body.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\""+filename+"\"\r\nContent-Type: "+type+"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                body.write(image);body.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
                var response=raw("POST","/api/projects/"+numeric(project)+"/import?return_task_ids=true",body.toByteArray(),"multipart/form-data; boundary="+boundary);
                if(response.statusCode()<200||response.statusCode()>=300)throw unavailable();
                var tasks=json.readTree(response.body()).path("task_ids");
                if(tasks.size()!=1)throw unavailable();return numeric(tasks.get(0).asText());
            }catch(BusinessException e){throw e;}catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();throw unavailable();}
        }
    }
    public static String numeric(String value){if(value==null||!value.matches("[0-9]+"))throw unavailable();return value;}
    private static String encode(String s){return URLEncoder.encode(s,StandardCharsets.UTF_8);}
    private static BusinessException unavailable(){return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,"EDITOR_UNAVAILABLE","编辑器请求失败，尚未确认平台保存成功，请重试或联系管理员");}
}
