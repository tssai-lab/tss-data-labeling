package com.tss.labeling.health;

import com.tss.labeling.common.ApiResponse;
import com.tss.labeling.common.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/labeling/v1")
public class HealthController {

    // 仅检查HTTP服务是否可响应，不代表所有依赖均正常。
    @GetMapping("/health")
    public ApiResponse<HealthResponse> health(
            HttpServletRequest request
    ) {
        String requestId = (String) request.getAttribute(
                RequestIdFilter.REQUEST_ID_ATTRIBUTE
        );

        HealthResponse result =
                new HealthResponse("UP", "tss-labeling-service");

        return ApiResponse.success(result, requestId);
    }

    public record HealthResponse(String status, String service) {
    }
}