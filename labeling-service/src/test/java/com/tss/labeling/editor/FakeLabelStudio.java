package com.tss.labeling.editor;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** An HTTP contract fixture, not evidence of real Label Studio behavior. */
public class FakeLabelStudio implements AutoCloseable {
    private final HttpServer server;private final ObjectMapper json=new ObjectMapper();
    private final AtomicInteger ids=new AtomicInteger();
    private final Map<String,ObjectNode> annotations=new ConcurrentHashMap<>();
    public FakeLabelStudio()throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            try {
                String p=exchange.getRequestURI().getPath(),method=exchange.getRequestMethod();int status=200;Object response=Map.of();
                if(p.equals("/user/login/")&&method.equals("GET")) {
                    exchange.getResponseHeaders().set("Set-Cookie","csrftoken=test; Path=/");
                    byte[] b="<input name='csrfmiddlewaretoken' value='test'>".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200,b.length);exchange.getResponseBody().write(b);return;
                }else if(p.equals("/user/login/")){
                    exchange.getResponseHeaders().set("Location","/");exchange.getResponseHeaders().set("Set-Cookie","csrftoken=test; Path=/");status=302;
                }else if(p.equals("/api/current-user/whoami"))response=Map.of("id",99);
                else if(p.equals("/api/projects/"))response=Map.of("id",ids.incrementAndGet());
                else if(p.matches("/api/projects/[0-9]+/import")){
                    exchange.getRequestBody().readAllBytes();response=Map.of("task_ids",List.of(ids.incrementAndGet()));
                }else if(p.matches("/api/tasks/[0-9]+/annotations/")){
                    String task=p.split("/")[3];String id=""+ids.incrementAndGet();var a=(ObjectNode)json.readTree(exchange.getRequestBody());
                    a.put("id",Integer.parseInt(id));a.put("task",Integer.parseInt(task));a.put("completed_by",99);annotations.put(id,a);response=a;
                }else if(p.matches("/api/tasks/[0-9]+/")){
                    String task=p.split("/")[3];response=Map.of("annotations",annotations.values().stream().filter(a->task.equals(a.path("task").asText())).toList());
                }else if(p.matches("/api/annotations/[0-9]+/")){
                    String id=p.split("/")[3];var a=annotations.get(id);if(a==null){status=404;}else{
                        if(method.equals("PATCH"))a.set("result",json.readTree(exchange.getRequestBody()).path("result"));response=a;
                    }
                }else status=404;
                byte[] b=json.writeValueAsBytes(response);exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(status,b.length);exchange.getResponseBody().write(b);
            }catch(Exception e){exchange.sendResponseHeaders(500,-1);}finally{exchange.close();}
        });server.start();
    }
    public String baseUrl(){return "http://127.0.0.1:"+server.getAddress().getPort();}
    public void tamper(String id,JsonNode value){annotations.get(id).set("result",value);}
    public void close(){server.stop(0);}
}
