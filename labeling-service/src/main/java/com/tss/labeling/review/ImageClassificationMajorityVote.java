package com.tss.labeling.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.Objects;

@Component
public class ImageClassificationMajorityVote implements ResultAggregationStrategy {
    private static final Descriptor DESCRIPTOR = new Descriptor(
            "majority-vote-v1",
            "IMAGE_CLASSIFICATION",
            "image-classification-v1",
            2,
            Set.of("HUMAN", "AI"),
            "按独立参与者的原始答案严格过半；平票或不足不生成建议，建议须另行审核",
            Map.of("type", "object", "properties", Map.of("minimumVotes", Map.of(
                    "type", "integer", "minimum", 2, "maximum", 100,
                    "default", 2, "description", "每个样本至少需要的独立答案数"))));

    private final ObjectMapper json;

    public ImageClassificationMajorityVote(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public Descriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public Evaluation aggregate(List<Candidate> candidates) {
        return aggregate(candidates, json.createObjectNode());
    }

    @Override
    public void validateParameters(JsonNode parameters) {
        if (parameters == null || parameters.isNull() || !parameters.isObject())
            throw new IllegalArgumentException("策略参数必须是 JSON 对象");
        var minimumVotes = parameters.get("minimumVotes");
        if (minimumVotes != null && (!minimumVotes.canConvertToInt()
                || minimumVotes.intValue() < 2 || minimumVotes.intValue() > 100))
            throw new IllegalArgumentException("minimumVotes 必须是 2 至 100 的整数");
        var fields = parameters.fieldNames();
        while (fields.hasNext()) {
            if (!Objects.equals(fields.next(), "minimumVotes"))
                throw new IllegalArgumentException("包含未知策略参数");
        }
    }

    @Override
    public Evaluation aggregate(List<Candidate> candidates, JsonNode parameters) {
        validateParameters(parameters);
        var votes = new TreeMap<String, Integer>();
        for (Candidate candidate : candidates) {
            String labelId = candidate.result().path("labelId").asText("").trim();
            if (!labelId.isEmpty()) votes.merge(labelId, 1, Integer::sum);
        }
        int independentSources = candidates.size();
        var winner = votes.entrySet().stream()
                .filter(entry -> entry.getValue() > independentSources / 2)
                .findFirst();
        int minimumVotes = parameters.path("minimumVotes").asInt(DESCRIPTOR.minimumIndependentSources());
        String outcome = independentSources < minimumVotes
                ? "INSUFFICIENT_INPUT"
                : winner.isEmpty() ? "TIE" : "SUGGESTED";
        JsonNode result = winner
                .<JsonNode>map(entry -> json.valueToTree(Map.of("labelId", entry.getKey())))
                .orElse(null);
        return new Evaluation(outcome, result, json.valueToTree(votes));
    }
}
