package com.tss.labeling.annotators;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class AnnotatorProviderRegistry {
    private final Map<String, AnnotatorProvider> providers;
    private final Map<String, AnnotatorProvider> executionModes;

    public AnnotatorProviderRegistry(List<AnnotatorProvider> registered) {
        var byVersion = new LinkedHashMap<String, AnnotatorProvider>();
        var byMode = new LinkedHashMap<String, AnnotatorProvider>();
        for (AnnotatorProvider provider : registered) {
            var descriptor = provider.descriptor();
            String key = key(descriptor.id(), descriptor.version());
            if (byVersion.putIfAbsent(key, provider) != null)
                throw new IllegalStateException("标注员提供方重复注册：" + key);
            if (byMode.putIfAbsent(descriptor.executionMode(), provider) != null)
                throw new IllegalStateException("执行模式重复注册：" + descriptor.executionMode());
        }
        if (byVersion.isEmpty()) throw new IllegalStateException("至少需要注册一个标注员提供方");
        providers = Map.copyOf(byVersion);
        executionModes = Map.copyOf(byMode);
    }

    public AnnotatorProvider require(String id, String version) {
        AnnotatorProvider provider = providers.get(key(id, version));
        if (provider == null) throw new IllegalArgumentException("标注员提供方版本未注册：" + id + "@" + version);
        return provider;
    }

    public String executionMode(String providerId) {
        return providers.values().stream()
                .filter(provider -> provider.descriptor().id().equals(providerId))
                .map(provider -> provider.descriptor().executionMode())
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("标注员提供方未注册：" + providerId));
    }

    public Collection<AnnotatorProvider.Descriptor> descriptors() {
        return providers.values().stream()
                .map(AnnotatorProvider::descriptor)
                .sorted(java.util.Comparator.comparing(AnnotatorProvider.Descriptor::id)
                        .thenComparing(AnnotatorProvider.Descriptor::version))
                .toList();
    }

    public AnnotatorProvider forExecutionMode(String mode) {
        AnnotatorProvider provider = executionModes.get(mode);
        if (provider == null) throw new IllegalArgumentException("执行模式未注册：" + mode);
        return provider;
    }

    private static String key(String id, String version) {
        return id + "@" + version;
    }
}
