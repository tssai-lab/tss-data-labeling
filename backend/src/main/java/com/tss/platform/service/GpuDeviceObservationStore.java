package com.tss.platform.service;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Short-lived runtime facts; deliberately not persisted as business data. */
@Component
public class GpuDeviceObservationStore {

    public static final Duration MAX_AGE = Duration.ofSeconds(90);

    private final Map<String, NodeObservation> byNode = new ConcurrentHashMap<>();

    public void update(String nodeId, List<DeviceObservation> devices, Instant observedAt) {
        if (nodeId == null || nodeId.isBlank() || devices == null || devices.isEmpty() || observedAt == null) {
            return;
        }
        byNode.put(nodeId, new NodeObservation(List.copyOf(devices), observedAt));
    }

    public Optional<NodeObservation> fresh(String nodeId, Instant now) {
        if (nodeId == null || nodeId.isBlank() || now == null) {
            return Optional.empty();
        }
        NodeObservation observation = byNode.get(nodeId);
        if (observation == null || observation.observedAt().isBefore(now.minus(MAX_AGE))) {
            return Optional.empty();
        }
        return Optional.of(observation);
    }

    public record DeviceObservation(
            String hostGpuIndex,
            String uuid,
            String modelName,
            Long totalMemoryMiB,
            Long freeMemoryMiB,
            Double utilizationRate,
            Double temperatureCelsius
    ) {
        /** 兼容旧测试和旧采集调用；这类记录不会被当成可精确选择的物理卡。 */
        public DeviceObservation(String modelName, Long totalMemoryMiB, Long freeMemoryMiB) {
            this(null, null, modelName, totalMemoryMiB, freeMemoryMiB, null, null);
        }
    }

    public record NodeObservation(List<DeviceObservation> devices, Instant observedAt) {
    }
}
