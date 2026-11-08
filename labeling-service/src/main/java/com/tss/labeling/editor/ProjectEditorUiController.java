package com.tss.labeling.editor;

import com.fasterxml.jackson.databind.JsonNode;
import com.tss.labeling.common.ApiResponse;
import com.tss.labeling.identity.MeController;
import com.tss.labeling.imports.ImportService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** Limited editor surface. Never proxies arbitrary Label Studio APIs or forwards service credentials. */
@RestController
@RequestMapping("/api/labeling/v1/project-editor-ui/{sessionId}")
public class ProjectEditorUiController {
    private final ProjectEditorService editor;private final ImportService imports;
    public ProjectEditorUiController(ProjectEditorService editor,ImportService imports){this.editor=editor;this.imports=imports;}
    @GetMapping("/launch")
    public ResponseEntity<Void> launch(@PathVariable UUID sessionId,@RequestParam String ticket,HttpServletRequest request){
        String cookie=editor.launch(sessionId,ticket);
        String path="/api/labeling/v1/project-editor-ui/"+sessionId+"/";
        return ResponseEntity.status(303).header("Location",path+"index.html")
            .header("Set-Cookie",ResponseCookie.from("editor_session",cookie).httpOnly(true).secure(request.isSecure()).sameSite("Strict").path(path).maxAge(1800).build().toString())
            .header("Cache-Control","no-store").header("Referrer-Policy","no-referrer").build();
    }
    private ProjectEditorService.Browser check(UUID id,String cookie){return editor.browser(id,cookie);}
    @ModelAttribute
    public void privateResponses(jakarta.servlet.http.HttpServletResponse response){
        response.setHeader("Cache-Control","private,no-store");response.setHeader("Referrer-Policy","no-referrer");
        response.setHeader("X-Content-Type-Options","nosniff");
    }
    @GetMapping(value="/bridge.js",produces="application/javascript;charset=UTF-8")
    public org.springframework.core.io.Resource script(@PathVariable UUID sessionId,@CookieValue(value="editor_session",required=false) String cookie){
        check(sessionId,cookie);return new ClassPathResource("editor-ui/bridge.js");
    }
    @GetMapping(value="/ui.css",produces="text/css;charset=UTF-8")
    public org.springframework.core.io.Resource style(@PathVariable UUID sessionId,@CookieValue(value="editor_session",required=false) String cookie){
        check(sessionId,cookie);return new ClassPathResource("editor-ui/ui.css");
    }
    @GetMapping(value="/index.html",produces="text/html;charset=UTF-8")
    public ResponseEntity<org.springframework.core.io.Resource> page(@PathVariable UUID sessionId,@CookieValue(value="editor_session",required=false) String cookie){
        check(sessionId,cookie);return ResponseEntity.ok().header("Cache-Control","no-store").header("Referrer-Policy","no-referrer")
            .header("Content-Security-Policy","default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' blob: data:; font-src 'self' data:; connect-src 'self'; worker-src 'self' blob:")
            .body(new ClassPathResource("editor-ui/index.html"));
    }
    @GetMapping("/context")
    public ApiResponse<Map<String,Object>> context(@PathVariable UUID sessionId,@CookieValue(value="editor_session",required=false) String cookie,HttpServletRequest request){
        var s=check(sessionId,cookie);return ApiResponse.success(Map.of("editorSessionId",sessionId,"projectId",s.project(),"specVersionId",s.spec(),"sampleId",s.sample(),"actorId",s.actor().actorId(),"actorDisplayName",s.actorDisplayName(),"sourcePath",s.sourcePath(),"config",s.config(),"draft",s.draft()),MeController.requestId(request));
    }
    @GetMapping("/image")
    public ResponseEntity<org.springframework.core.io.Resource> image(@PathVariable UUID sessionId,@CookieValue(value="editor_session",required=false) String cookie){
        var s=check(sessionId,cookie);var f=imports.file(s.actor(),s.project().toString(),s.sample().toString());
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(f.mediaType())).contentLength(f.sizeBytes()).header("Cache-Control","private,no-store").header("X-Content-Type-Options","nosniff").body(f.resource());
    }
    @PutMapping("/result")
    public ApiResponse<ProjectEditorService.Result> save(@PathVariable UUID sessionId,@CookieValue(value="editor_session",required=false) String cookie,
            @Valid @RequestBody Save input,HttpServletRequest request){
        var s=check(sessionId,cookie);return ApiResponse.success(editor.save(s.actor(),s.project(),s.sample(),sessionId,input.baseRevision(),input.result()),MeController.requestId(request));
    }
    public record Save(@NotNull @Min(0) Long baseRevision,@NotNull JsonNode result) {}
}
