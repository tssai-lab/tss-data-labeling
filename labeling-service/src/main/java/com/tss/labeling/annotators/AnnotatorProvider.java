package com.tss.labeling.annotators;

import java.util.Set;

/** 标注员执行方式的可信适配器描述。 */
public interface AnnotatorProvider {
    Descriptor descriptor();

    record Descriptor(
            String id,
            String version,
            String name,
            String annotatorType,
            String executionMode,
            Set<String> capabilities,
            String description) {}
}
