package com.tss.labeling.ls;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tss.labeling.common.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.FilterOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.*;
import java.io.*;
import jakarta.annotation.PreDestroy;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Calls editable, vendored LS source in a bounded local process, never an LS server. */
@Service
public class LsSourceAdapter {
    private final ObjectMapper json;
    private final String python;
    private final Path adapterRoot;
    private final Path workRoot;
    private final long timeoutSeconds;
    private final Semaphore slots = new Semaphore(2);
    private final Semaphore exportSlots=new Semaphore(1);
    @Value("${labeling.ls-source.worker-enabled:true}") private boolean workerEnabled;
    private final BlockingQueue<Worker> shortWorkers=new LinkedBlockingQueue<>(),exportWorkers=new LinkedBlockingQueue<>();
    private final java.util.Set<Worker> allWorkers=ConcurrentHashMap.newKeySet();
    private final ExecutorService readers=Executors.newCachedThreadPool(r->{Thread thread=new Thread(r,"ls-source-response");thread.setDaemon(true);return thread;});

    public LsSourceAdapter(ObjectMapper json,
            @Value("${labeling.ls-source.python:python}") String python,
            @Value("${labeling.ls-source.adapter-root:./ls-source-adapter}") String adapterRoot,
            @Value("${labeling.ls-source.work-root:./data/labeling/ls-jobs}") String workRoot,
            @Value("${labeling.ls-source.timeout-seconds:30}") long timeoutSeconds) {
        this.json = json;
        this.python = python;
        this.adapterRoot = Path.of(adapterRoot).toAbsolutePath().normalize();
        this.workRoot = Path.of(workRoot).toAbsolutePath().normalize();
        this.timeoutSeconds = Math.max(1, timeoutSeconds);
    }

    public JsonNode inspect(String labelConfig) {
        return withJob(job -> invoke(job, request("inspect", labelConfig)));
    }

    /** The caller supplies storage paths, never client-provided filesystem paths. */
    public JsonNode parseRecords(Path file, String name, boolean structured) {
        return withJob(job -> {
            Files.copy(file, job.resolve("source.input"));
            ObjectNode request = request("recordsParse", "<View/>");
            request.put("sourceName", name).put("structured", structured);
            JsonNode response = invoke(job, request);
            Path output = job.resolve("records.jsonl");
            if (Files.size(output) > 64L * 1024 * 1024) throw new IOException("record output limit");
            var records = json.createArrayNode();
            try (var reader = Files.newBufferedReader(output, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (records.size() >= 10000) throw new IOException("record count limit");
                    records.add(json.readTree(line));
                }
            }
            ((ObjectNode) response).set("records", records);
            return response;
        });
    }

    public JsonNode recordOperation(String action, JsonNode fields) {
        if (!java.util.Set.of("recordsConfig", "recordsValidate", "recordsIssues").contains(action))
            throw new IllegalArgumentException("record operation");
        return withJob(job -> {
            ObjectNode request = request(action, "<View/>");
            request.setAll((ObjectNode) fields);
            return invoke(job, request);
        });
    }

    public byte[] recordParquet(JsonNode rows) {
        return withJob(job -> {
            json.writeValue(job.resolve("rows.json").toFile(), rows);
            invoke(job, request("recordsParquet", "<View/>"));
            Path output = job.resolve("records.parquet");
            if (Files.size(output) > 64L * 1024 * 1024) throw new IOException("parquet output limit");
            return Files.readAllBytes(output);
        }, exportSlots);
    }

    public JsonNode templates() {
        return withJob(job -> invoke(job, request("templates", "<View/>")));
    }

    public JsonNode sample(String labelConfig) {
        return withJob(job -> invoke(job, request("sample", labelConfig)));
    }

    public JsonNode validateConfig(String labelConfig) {
        return withJob(job -> invoke(job, request("validate", labelConfig)));
    }

    public void validateNativeResult(JsonNode config,JsonNode envelope,String stage,JsonNode taskData,JsonNode metadata,Map<String,Path> resources){
        withJob(job->{
            ObjectNode request=request("validateResult",config.path("labelConfig").asText());
            request.set("envelope",envelope);request.put("stage",stage);request.set("completion",config.path("completion"));
            if(taskData!=null)request.set("taskData",taskData);if(metadata!=null)request.set("inputMetadata",metadata);
            ObjectNode manifest=request.putObject("resources");Path root=Files.createDirectory(job.resolve("resources"));
            for(var resource:resources.entrySet()){
                String name=UUID.randomUUID()+".input";Files.copy(resource.getValue(),root.resolve(name));manifest.put(resource.getKey(),name);
            }
            return invoke(job,request);
        });
    }

    public void validateTimes(String labelConfig,JsonNode result) {
        validateTimes(labelConfig,result,null,Map.of());
    }

    public void validateTimes(String labelConfig,JsonNode result,JsonNode taskData,Map<String,Path> resources) {
        withJob(job->{
            ObjectNode request=request("validateTimes",labelConfig);
            request.set("result",result);
            if(taskData!=null)request.set("taskData",taskData);
            ObjectNode manifest=request.putObject("resources");
            Path root=Files.createDirectory(job.resolve("resources"));
            for(var resource:resources.entrySet()) {
                String name=UUID.randomUUID()+".input";Files.copy(resource.getValue(),root.resolve(name));manifest.put(resource.getKey(),name);
            }
            return invoke(job,request);
        });
    }

    /** Internal API only: the business layer must select an authorized frozen snapshot.
     *  Resources are trusted paths obtained from ImportStorage, never HTTP parameters.
     *  Output is a ZIP streamed to the caller; the private working directory is removed.
     */
    public JsonNode writeExportZip(String labelConfig, String format, JsonNode tasks,
                                  Map<String, Path> resources, OutputStream output) {
        return writeExportZip(labelConfig,format,tasks,resources,output,false);
    }

    public JsonNode writeExportZip(String labelConfig, String format, JsonNode tasks,
                                  Map<String, Path> resources, OutputStream output,boolean bundleInputs) {
        if (tasks == null || !tasks.isArray()) {
            throw failure(HttpStatus.UNPROCESSABLE_ENTITY, "LS_INVALID_TASKS", "导出任务必须为数组");
        }
        return writeExportZipPrepared(labelConfig,format,sink->{for(JsonNode task:tasks)sink.write(task);},resources,output,bundleInputs);
    }

    public JsonNode writeExportZipPrepared(String labelConfig,String format,TaskProducer producer,
                    Map<String,Path> resources,OutputStream output,boolean bundleInputs) {
        return withJob(job -> {
            ObjectNode request = request("export", labelConfig);
            request.put("format", format);
            request.put("tasksPrepared",true);
            Path taskRoot=Files.createDirectory(job.resolve("tasks"));
            long[] index={0};
            producer.produce(task->json.writeValue(taskRoot.resolve(String.format("%012d.json",index[0]++)).toFile(),task));
            ObjectNode manifest = request.putObject("resources");
            Path resourceRoot = Files.createDirectory(job.resolve("resources"));
            for (var entry : resources.entrySet()) {
                Path source = entry.getValue();
                if (!Files.isRegularFile(source)) {
                    throw failure(HttpStatus.UNPROCESSABLE_ENTITY, "LS_RESOURCE_MISSING", "导出资源不存在");
                }
                String original = source.getFileName().toString();
                String suffix = original.contains(".") ? original.substring(original.lastIndexOf('.')) : "";
                if (!suffix.matches("\\.[A-Za-z0-9]{1,10}")) suffix = "";
                String name = UUID.randomUUID() + suffix;
                Files.copy(source, resourceRoot.resolve(name));
                manifest.put(entry.getKey(), name);
            }
            JsonNode result = invoke(job, request);
            Path exported = job.resolve("output");
            // Finish the archive without closing the response stream owned by the caller.
            OutputStream callerStream = new FilterOutputStream(output) {
                @Override public void close() throws IOException { flush(); }
            };
            try (ZipOutputStream zip = new ZipOutputStream(callerStream, StandardCharsets.UTF_8)) {
                for (JsonNode file : result.path("files")) {
                    String name = file.path("path").asText();
                    Path path = exported.resolve(name).normalize();
                    if (!path.startsWith(exported) || !Files.isRegularFile(path) || Files.isSymbolicLink(path)) {
                        throw failure(HttpStatus.SERVICE_UNAVAILABLE, "LS_INVALID_OUTPUT", "导出文件无效");
                    }
                    zip.putNextEntry(new ZipEntry(name));
                    Files.copy(path, zip);
                    zip.closeEntry();
                }
                if(bundleInputs) {
                    zip.putNextEntry(new ZipEntry("label_config.xml"));
                    zip.write(labelConfig.getBytes(StandardCharsets.UTF_8));zip.closeEntry();
                    for(var resource:resources.entrySet()) {
                        String name=resource.getKey();
                        Path relative=Path.of(name).normalize();
                        if(relative.isAbsolute() || relative.startsWith("..") || name.contains("\\")
                                || !(name.startsWith("resources/") || name.startsWith("images/")))
                            throw failure(HttpStatus.UNPROCESSABLE_ENTITY,"LS_INVALID_RESOURCE_NAME","导出资源名称无效");
                        zip.putNextEntry(new ZipEntry(name));
                        Files.copy(resource.getValue(),zip);zip.closeEntry();
                    }
                }
            }
            return result;
        },exportSlots);
    }

    @FunctionalInterface public interface TaskSink { void write(JsonNode task) throws IOException; }
    @FunctionalInterface public interface TaskProducer { void produce(TaskSink sink) throws IOException; }

    private ObjectNode request(String action, String xml) {
        if (xml == null || xml.isBlank() || xml.length() > 262144) {
            throw failure(HttpStatus.UNPROCESSABLE_ENTITY, "LS_INVALID_CONFIG", "标注配置不能为空或超过长度限制");
        }
        ObjectNode request = json.createObjectNode();
        request.put("protocolVersion", 1);
        request.put("action", action);
        request.put("labelConfig", xml);
        return request;
    }

    private JsonNode invoke(Path job, ObjectNode request) throws IOException, InterruptedException {
        if (!Files.isRegularFile(adapterRoot.resolve("adapter.py"))) {
            throw failure(HttpStatus.SERVICE_UNAVAILABLE, "LS_SOURCE_UNAVAILABLE", "LS 源码适配模块尚未配置");
        }
        json.writeValue(job.resolve("request.json").toFile(), request);
        String action = request.path("action").asText();
        if(workerEnabled)return invokeWorker(job,"export".equals(action),action);
        Path response = job.resolve("response.json");
        Process process = new ProcessBuilder(python, "-E", "-s", "-X", "utf8",
                adapterRoot.resolve("adapter.py").toString(), job.toString())
                .directory(adapterRoot.toFile())
                .redirectOutput(response.toFile())
                .redirectError(job.resolve("error.log").toFile())
                .start();
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                throw failure(HttpStatus.SERVICE_UNAVAILABLE, "LS_SOURCE_TIMEOUT", "配置解析或导出转换超时");
            }
            if (Files.size(response) == 0 || Files.size(response) > 2 * 1024 * 1024) {
                throw failure(HttpStatus.SERVICE_UNAVAILABLE, "LS_SOURCE_FAILED", "LS 源码适配模块运行失败");
            }
            JsonNode payload = json.readTree(response.toFile());
            if (payload == null || payload.path("protocolVersion").asInt() != 1) {
                throw failure(HttpStatus.SERVICE_UNAVAILABLE, "LS_SOURCE_PROTOCOL", "LS 源码适配模块响应无效");
            }
            if (!payload.path("ok").asBoolean() || process.exitValue() != 0) {
                String message = payload.path("error").path("message").asText();
                if (action.startsWith("records")) throw failure(HttpStatus.UNPROCESSABLE_ENTITY,"INVALID_RECORD_DATA",friendly(message));
                if (isConfigAction(action)) {
                    throw failure(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_LABEL_CONFIG", configFriendly(message));
                }
                if ("validateResult".equals(action) || "validateTimes".equals(action)) {
                    throw failure(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_LS_RESULT", friendly(message));
                }
                throw failure(HttpStatus.UNPROCESSABLE_ENTITY, "LS_CONVERSION_REJECTED", "标注结果或导出资源无效");
            }
            if (!payload.path("data").isObject()) {
                throw failure(HttpStatus.SERVICE_UNAVAILABLE, "LS_SOURCE_PROTOCOL", "LS 源码适配模块响应无效");
            }
            return payload.get("data");
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    private <T> T withJob(Job<T> action) {
        return withJob(action,slots);
    }
    private <T> T withJob(Job<T> action,Semaphore limit) {
        if (!limit.tryAcquire()) {
            throw failure(HttpStatus.SERVICE_UNAVAILABLE, "LS_SOURCE_BUSY", "转换任务繁忙，请稍后重试");
        }
        Path job = null;
        try {
            Files.createDirectories(workRoot);
            job = Files.createTempDirectory(workRoot, "job-");
            return action.run(job);
        } catch (InterruptedException exc) {
            Thread.currentThread().interrupt();
            throw failure(HttpStatus.SERVICE_UNAVAILABLE, "LS_SOURCE_INTERRUPTED", "转换任务已中断");
        } catch (IOException exc) {
            throw failure(HttpStatus.SERVICE_UNAVAILABLE, "LS_SOURCE_IO", "LS 源码适配模块暂不可用");
        } finally {
            if (job != null) {
                // Only this invocation's generated directory may be removed.
                if (job.startsWith(workRoot) && !job.equals(workRoot)) {
                    // Windows may release redirected worker file handles shortly
                    // after process termination. Retry only this private directory.
                    for (int attempt = 0; attempt < 10; attempt++) {
                        try (var paths = Files.walk(job)) {
                            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                                Files.deleteIfExists(path);
                            }
                            break;
                        } catch (IOException ignored) {
                            try {
                                Thread.sleep(50);
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    }
                }
            }
            limit.release();
        }
    }

    private JsonNode invokeWorker(Path job,boolean export,String action) throws IOException,InterruptedException {
        BlockingQueue<Worker> pool=export?exportWorkers:shortWorkers;Worker worker=pool.poll();
        if(worker==null||!worker.process.isAlive()){if(worker!=null)worker.close();worker=new Worker(export);}
        boolean healthy=false;
        try{
            worker.input.write(json.writeValueAsString(Map.of("job",job.toString())));worker.input.newLine();worker.input.flush();
            final Worker current=worker;Future<String> response=readers.submit(current.output::readLine);
            String line;
            try{line=response.get(timeoutSeconds,TimeUnit.SECONDS);}catch(TimeoutException exc){response.cancel(true);throw failure(HttpStatus.SERVICE_UNAVAILABLE,"LS_SOURCE_TIMEOUT","源码处理超时，请重试");}
            catch(ExecutionException exc){throw new IOException("源码响应不可读取",exc);}
            if(line==null||line.length()>2*1024*1024)throw new IOException("源码响应无效");
            JsonNode payload=json.readTree(line);if(payload.path("protocolVersion").asInt()!=1)throw new IOException("源码协议无效");
            healthy=true;
            if(!payload.path("ok").asBoolean()) {
                String message = payload.path("error").path("message").asText();
                if (action.startsWith("records")) throw failure(HttpStatus.UNPROCESSABLE_ENTITY,"INVALID_RECORD_DATA",friendly(message));
                if (isConfigAction(action)) {
                    throw failure(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_LABEL_CONFIG", configFriendly(message));
                }
                if ("validateResult".equals(action) || "validateTimes".equals(action)) {
                    throw failure(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_LS_RESULT", friendly(message));
                }
                throw failure(HttpStatus.UNPROCESSABLE_ENTITY, "LS_CONVERSION_REJECTED", "标注结果或导出资源无效");
            }
            return payload.path("data");
        }finally{if(healthy&&worker.process.isAlive())pool.offer(worker);else worker.close();}
    }
    private static boolean isConfigAction(String action) {
        return "inspect".equals(action) || "validate".equals(action) || "sample".equals(action);
    }
    private String configFriendly(String message) {
        if (message.contains("Choice") && message.contains("required property")) {
            return "分类控件还没有标签选项，请先添加至少一个分类标签，再保存或预览。";
        }
        return "标注配置未通过校验：" + (message.length() > 200 ? message.substring(0, 200) : message);
    }
    private String friendly(String message){
        if(message.startsWith("REQUIRED_"))return "存在尚未完成的必填标注："+message.substring(message.indexOf(':')+1);
        if(message.startsWith("LABEL_"))return "标签不属于当前规范或样本提供的标签范围";
        if(message.contains("IMAGE_SIZE"))return "标注中的原图尺寸与实际图片不一致";
        if(message.contains("OUT_OF")||message.contains("GEOMETRY"))return "标注坐标超出范围或区域结构无效";
        if(message.contains("EMPTY_REASON"))return "请完成标注，或按规范填写无标注原因";
        return "配置或标注结果校验未通过："+(message.length()>200?message.substring(0,200):message);
    }
    private final class Worker implements AutoCloseable {
        final Process process;final BufferedWriter input;final BufferedReader output;
        Worker(boolean export)throws IOException{
            Files.createDirectories(workRoot);
            process=new ProcessBuilder(python,"-E","-s","-u","-X","utf8",adapterRoot.resolve("adapter.py").toString(),"--worker")
                    .directory(adapterRoot.toFile()).redirectError(workRoot.resolve((export?"export":"interactive")+"-"+UUID.randomUUID()+".log").toFile()).start();
            input=new BufferedWriter(new OutputStreamWriter(process.getOutputStream(),StandardCharsets.UTF_8));
            output=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8));
            allWorkers.add(this);
        }
        public void close(){
            allWorkers.remove(this);
            var children=process.descendants().toList();
            children.forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            // Windows venv launchers spawn a real Python child which also owns stderr.
            // Both levels must exit before the private job/log directory is released.
            try {
                process.waitFor(5,TimeUnit.SECONDS);
                for(var child:children) {
                    try { child.onExit().get(5,TimeUnit.SECONDS); }
                    catch(ExecutionException | TimeoutException ignored) {}
                }
            }
            catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            try { input.close(); } catch(IOException ignored) {}
            try { output.close(); } catch(IOException ignored) {}
        }
    }
    @PreDestroy public void close(){allWorkers.forEach(Worker::close);readers.shutdownNow();}

    private static BusinessException failure(HttpStatus status, String code, String message) {
        return new BusinessException(status, code, message);
    }

    @FunctionalInterface
    private interface Job<T> {
        T run(Path directory) throws IOException, InterruptedException;
    }
}
