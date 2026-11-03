package com.tss.platform.service;

import com.tss.platform.entity.ComputeServer;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.regex.Pattern;

/** 把短期 GPU 指标整理成可复核的物理卡目标；UUID 是绑定依据，编号只用于页面展示。 */
@Service
public class GpuDeviceTargetService {

    private static final Pattern GPU_UUID = Pattern.compile("^GPU-[A-Za-z0-9-]{8,}$");
    private static final Pattern GPU_INDEX = Pattern.compile("^[0-9]+$");

    private final GpuDeviceObservationStore observationStore;

    public GpuDeviceTargetService(GpuDeviceObservationStore observationStore) {
        this.observationStore = observationStore;
    }

    public List<GpuDeviceTarget> freshTargets(List<ComputeServer> candidates) {
        Instant now = Instant.now();
        List<GpuDeviceTarget> result = new ArrayList<>();
        for (ComputeServer server : candidates == null ? List.<ComputeServer>of() : candidates) {
            // 精确选卡最终按 Kubernetes 节点名落位；缺少映射时不向用户提供这张卡。
            String nodeName = trim(server.getK8sNodeName());
            if (nodeName == null) continue;
            var observation = observationStore.fresh(server.getServerIp(), now)
                    .or(() -> observationStore.fresh(nodeName, now));
            if (observation.isEmpty()) continue;
            for (GpuDeviceObservationStore.DeviceObservation device : observation.get().devices()) {
                String uuid = trim(device.uuid());
                String index = trim(device.hostGpuIndex());
                String model = trim(device.modelName());
                if (uuid == null || !GPU_UUID.matcher(uuid).matches()
                        || index == null || !GPU_INDEX.matcher(index).matches()
                        || model == null || device.totalMemoryMiB() == null
                        || device.totalMemoryMiB() <= 0) {
                    continue;
                }
                result.add(new GpuDeviceTarget(
                        targetId(uuid), server, nodeName, index, uuid, model,
                        device.totalMemoryMiB(), device.freeMemoryMiB(),
                        device.utilizationRate(), device.temperatureCelsius(),
                        observation.get().observedAt()
                ));
            }
        }
        Map<String, Integer> uuidCounts = new HashMap<>();
        result.forEach(target -> uuidCounts.merge(target.uuid(), 1, Integer::sum));
        result.removeIf(target -> uuidCounts.getOrDefault(target.uuid(), 0) != 1);
        result.sort(Comparator.comparing(GpuDeviceTarget::nodeName)
                .thenComparingInt(target -> Integer.parseInt(target.hostGpuIndex())));
        return List.copyOf(result);
    }

    public String scopedTargetId(String scope, GpuDeviceTarget target) {
        return digestId("hw-", firstText(scope, "gpu") + "\n" + target.uuid());
    }

    private String targetId(String uuid) {
        return digestId("gpu-", uuid);
    }

    private static String digestId(String prefix, String source) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8));
            return prefix + HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String firstText(String value, String fallback) {
        String normalized = trim(value);
        return normalized == null ? fallback : normalized;
    }

    public record GpuDeviceTarget(
            String physicalTargetId,
            ComputeServer server,
            String nodeName,
            String hostGpuIndex,
            String uuid,
            String model,
            Long totalMemoryMiB,
            Long freeMemoryMiB,
            Double utilizationRate,
            Double temperatureCelsius,
            Instant observedAt
    ) {
        public String displayName() {
            String host = firstText(server.getHostname(), nodeName);
            return model + " · " + host + " · GPU " + hostGpuIndex;
        }
    }
}
