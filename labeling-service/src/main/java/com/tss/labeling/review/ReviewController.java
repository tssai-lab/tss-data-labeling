package com.tss.labeling.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.tss.labeling.common.ApiResponse;
import com.tss.labeling.identity.ActorService;
import com.tss.labeling.identity.IdentityClient;
import com.tss.labeling.identity.MeController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/labeling/v1")
public class ReviewController {

    private final ReviewService reviews;
    private final IdentityClient identity;
    private final ActorService actors;

    public ReviewController(ReviewService reviews, IdentityClient identity, ActorService actors) {
        this.reviews = reviews;
        this.identity = identity;
        this.actors = actors;
    }

    private ActorService.ActorContext actor(String auth) {
        return actors.resolve(identity.currentUser(auth));
    }

    private ApiResponse<JsonNode> response(JsonNode value, HttpServletRequest req) {
        return ApiResponse.success(value, MeController.requestId(req));
    }

    @GetMapping("/submissions/{submissionId}/review")
    public ApiResponse<JsonNode> get(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable UUID submissionId,
            HttpServletRequest req) {
        return response(reviews.get(actor(auth), submissionId), req);
    }

    @PutMapping("/submissions/{submissionId}/review/items")
    public ApiResponse<JsonNode> reviewItems(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable UUID submissionId,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody ReviewItemsInput body,
            HttpServletRequest req) {
        return response(reviews.reviewItems(actor(auth), submissionId, body.items(), key), req);
    }

    @PostMapping("/submissions/{submissionId}/review/decide")
    public ApiResponse<JsonNode> decide(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable UUID submissionId,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody DecisionInput body,
            HttpServletRequest req) {
        return response(reviews.decide(actor(auth), submissionId, body, key), req);
    }

    @GetMapping("/submissions/{submissionId}/review/audit")
    public ApiResponse<JsonNode> audit(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable UUID submissionId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String cursor,
            HttpServletRequest req) {
        return response(reviews.audit(actor(auth), submissionId, limit, cursor), req);
    }

    public record ItemInput(
            @NotNull UUID sampleId,
            @NotBlank @Pattern(regexp = "ACCEPT|MODIFY|REJECT|CORRECTION") String action,
            @Size(max = 1000) String reason,
            JsonNode result) {
    }

    public record ReviewItemsInput(
            @NotEmpty @Size(max = 10000) List<@NotNull @Valid ItemInput> items) {
    }

    public record DecisionInput(
            @NotBlank @Pattern(regexp = "ACCEPT|REJECT|CORRECTION") String action,
            @Size(max = 1000) String reason) {
    }
}
