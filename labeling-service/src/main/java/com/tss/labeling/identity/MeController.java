package com.tss.labeling.identity;

import com.tss.labeling.common.ApiResponse;
import com.tss.labeling.common.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/labeling/v1")
public class MeController {

    private final IdentityClient identityClient;
    private final ActorService actorService;

    public MeController(IdentityClient identityClient, ActorService actorService) {
        this.identityClient = identityClient;
        this.actorService = actorService;
    }

    @GetMapping("/me")
    public ApiResponse<MeResponse> me(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            HttpServletRequest request
    ) {
        IdentityUser user = identityClient.currentUser(authorization);
        ActorService.ActorContext actor = actorService.resolve(user);
        return ApiResponse.success(new MeResponse(
                actor.actorId().toString(),
                actor.labelingAdmin(),
                true,
                true
        ), requestId(request));
    }

    public static String requestId(HttpServletRequest request) {
        return (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
    }

    public record MeResponse(
            String actorId,
            boolean labelingAdmin,
            boolean canCreateProject,
            boolean canAnnotate
    ) {
    }
}
