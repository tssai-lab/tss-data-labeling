package com.tss.labeling.identity;

import com.tss.labeling.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.UUID;
import static org.springframework.http.HttpStatus.BAD_REQUEST;

@RestController
@RequestMapping("/api/labeling/v1/admin/users")
public class ActorAdminController {
    private static final Logger log = LoggerFactory.getLogger(ActorAdminController.class);
    private final IdentityClient identity;
    private final ActorService actors;
    private final ActorAdminService admin;

    public ActorAdminController(IdentityClient identity, ActorService actors, ActorAdminService admin) {
        this.identity = identity;
        this.actors = actors;
        this.admin = admin;
    }

    @GetMapping
    public ApiResponse<List<ActorAdminService.UserView>> list(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset, HttpServletRequest request) {
        UUID caller = actors.resolve(identity.currentUser(authorization)).actorId();
        return ApiResponse.success(admin.list(caller, limit, offset), MeController.requestId(request));
    }

    @PatchMapping("/{actorId}")
    public ApiResponse<Void> update(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String actorId, @Valid @RequestBody UpdateUser body, HttpServletRequest request) {
        UUID caller = actors.resolve(identity.currentUser(authorization)).actorId();
        UUID target;
        try { target = UUID.fromString(actorId); }
        catch (IllegalArgumentException exception) { throw new ResponseStatusException(BAD_REQUEST); }
        admin.update(caller, target, body.status(), body.labelingAdmin());
        // 业务事务提交后记录管理操作，不包含登录令牌或原始业务数据。
        log.info("标注用户权限变更 caller={} target={} status={} admin={} reason={} requestId={}",
                caller, target, body.status(), body.labelingAdmin(),
                body.reason().replaceAll("[\\p{Cntrl}]", " "), MeController.requestId(request));
        return ApiResponse.success(null, MeController.requestId(request));
    }

    public record UpdateUser(String status, Boolean labelingAdmin,
                             @NotBlank @Size(max = 500) String reason) {}
}
