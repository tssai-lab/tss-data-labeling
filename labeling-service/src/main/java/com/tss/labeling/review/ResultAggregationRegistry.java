package com.tss.labeling.review;

import org.springframework.stereotype.Component;
import static com.tss.labeling.common.WorkflowStore.error;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ResultAggregationRegistry {
    private final Map<String, ResultAggregationStrategy> strategies;

    public ResultAggregationRegistry(List<ResultAggregationStrategy> registered) {
        var index = new LinkedHashMap<String, ResultAggregationStrategy>();
        for (ResultAggregationStrategy strategy : registered) {
            var descriptor = strategy.descriptor();
            if (descriptor.algorithmId() == null || descriptor.algorithmId().isBlank()
                    || descriptor.taskType() == null || descriptor.taskType().isBlank()
                    || descriptor.resultFormat() == null || descriptor.resultFormat().isBlank()
                    || descriptor.acceptedSourceKinds() == null || descriptor.acceptedSourceKinds().isEmpty()
                    || descriptor.minimumIndependentSources() < 1)
                throw new IllegalStateException("集成策略描述无效：" + descriptor.algorithmId());
            if (index.putIfAbsent(descriptor.algorithmId(), strategy) != null)
                throw new IllegalStateException("集成策略重复注册：" + descriptor.algorithmId());
        }
        strategies = Map.copyOf(index);
    }

    public ResultAggregationStrategy resolve(String algorithmId, String taskType, String resultFormat) {
        if (algorithmId == null || algorithmId.isBlank())
            throw error(422, "UNSUPPORTED_ALGORITHM", "必须指定已注册的集成策略");
        ResultAggregationStrategy strategy = strategies.get(algorithmId);
        if (strategy == null) throw error(422, "UNSUPPORTED_ALGORITHM", "集成策略未注册");
        var descriptor = strategy.descriptor();
        if (!descriptor.taskType().equals(taskType) || !descriptor.resultFormat().equals(resultFormat))
            throw error(422, "UNSUPPORTED_FORMAT", "集成策略与任务结果格式不匹配");
        return strategy;
    }

    public List<ResultAggregationStrategy.Descriptor> algorithms(String taskType) {
        return strategies.values().stream()
                .map(ResultAggregationStrategy::descriptor)
                .filter(descriptor -> taskType == null || taskType.isBlank() || descriptor.taskType().equals(taskType))
                .sorted(java.util.Comparator.comparing(ResultAggregationStrategy.Descriptor::algorithmId))
                .toList();
    }
}
