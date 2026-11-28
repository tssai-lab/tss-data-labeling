package com.tss.labeling.annotators;

import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class HumanPoolAnnotatorProvider implements AnnotatorProvider {
    private static final Descriptor DESCRIPTOR = new Descriptor(
            "human-pool",
            "1.0.0",
            "人工标注员",
            "HUMAN",
            "HUMAN_POOL",
            Set.of("multiple-annotators-per-sample", "drafts", "package-submission"),
            "由平台任务池分配给人工标注员执行");

    @Override
    public Descriptor descriptor() {
        return DESCRIPTOR;
    }
}
