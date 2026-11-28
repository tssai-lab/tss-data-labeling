package com.tss.labeling.review;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 对同一样本的独立标注结果生成可审核的集成建议。 */
public interface ResultAggregationStrategy {
    Descriptor descriptor();

    Evaluation aggregate(List<Candidate> candidates);

    default void validateParameters(JsonNode parameters) {
        if (parameters != null && !parameters.isNull() && !parameters.isEmpty())
            throw new IllegalArgumentException("该策略不接受额外参数");
    }

    default Evaluation aggregate(List<Candidate> candidates, JsonNode parameters) {
        validateParameters(parameters);
        return aggregate(candidates);
    }

    record Descriptor(
            String algorithmId,
            String taskType,
            String resultFormat,
            int minimumIndependentSources,
            Set<String> acceptedSourceKinds,
            String description,
            Map<String, Object> parametersSchema) {
        public Descriptor(
                String algorithmId,
                String taskType,
                String resultFormat,
                int minimumIndependentSources,
                Set<String> acceptedSourceKinds,
                String description) {
            this(algorithmId, taskType, resultFormat, minimumIndependentSources,
                    acceptedSourceKinds, description, Map.of("type", "object", "properties", Map.of()));
        }
    }

    record Candidate(UUID actorId, String sourceKind, JsonNode result) {}

    record Evaluation(String outcome, JsonNode result, JsonNode evidence) {}
}
