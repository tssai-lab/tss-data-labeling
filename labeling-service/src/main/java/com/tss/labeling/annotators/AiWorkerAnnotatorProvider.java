package com.tss.labeling.annotators;

import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class AiWorkerAnnotatorProvider implements AnnotatorProvider {
    private static final Descriptor DESCRIPTOR = new Descriptor(
            "ai-agent-worker",
            "1.0.0",
            "模型标注员",
            "MODEL",
            "AI_AGENT",
            Set.of("worker-execution", "candidate-results", "confidence", "references"),
            "通过统一 Worker 契约执行；具体模型类型和版本由 Agent 配置声明");

    @Override
    public Descriptor descriptor() {
        return DESCRIPTOR;
    }
}
