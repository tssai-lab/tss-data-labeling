package com.tss.platform.training;

import com.tss.platform.config.TrainingKubernetesProperties;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** 为指定 GPU UUID 生成可重复应用的 DRA 申请模板。 */
public final class GpuDeviceClaimManifestBuilder {

    private static final Pattern GPU_UUID = Pattern.compile("^GPU-[A-Za-z0-9-]{8,}$");

    private final TrainingKubernetesProperties properties;

    public GpuDeviceClaimManifestBuilder(TrainingKubernetesProperties properties) {
        this.properties = properties;
    }

    public boolean exactSelectionEnabled() {
        return properties.isExactGpuSelectionEnabled();
    }

    public String claimTemplateName(String gpuUuid) {
        String uuid = requireGpuUuid(gpuUuid);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(uuid.getBytes(StandardCharsets.UTF_8));
            return "tss-gpu-" + HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public String prependClaimTemplate(String jobYaml, String gpuUuid) {
        if (!exactSelectionEnabled()) {
            throw new IllegalStateException("exact GPU selection is disabled");
        }
        if (properties.getClientMode() != TrainingKubernetesProperties.ClientMode.KUBECTL) {
            throw new IllegalStateException("exact GPU selection requires the kubectl Kubernetes client");
        }
        String uuid = requireGpuUuid(gpuUuid);
        String className = properties.getGpuDeviceClassName();
        String templateName = claimTemplateName(uuid);
        String expression = "device.attributes['gpu.nvidia.com'].type == 'gpu' && "
                + "device.attributes['gpu.nvidia.com'].uuid == '" + uuid + "'";
        return """
                apiVersion: resource.k8s.io/v1
                kind: ResourceClaimTemplate
                metadata:
                  name: %s
                  namespace: %s
                  labels:
                    app.kubernetes.io/part-of: tss-platform
                    tss.ai/gpu-uuid-hash: %s
                spec:
                  spec:
                    devices:
                      requests:
                        - name: gpu
                          exactly:
                            deviceClassName: %s
                            selectors:
                              - cel:
                                  expression: "%s"
                ---
                %s""".formatted(
                templateName,
                properties.getNamespace(),
                templateName.substring("tss-gpu-".length()),
                className,
                expression,
                jobYaml
        );
    }

    private String requireGpuUuid(String value) {
        String uuid = value == null ? "" : value.trim();
        if (!GPU_UUID.matcher(uuid).matches()) {
            throw new IllegalStateException("selected GPU UUID is missing or invalid");
        }
        return uuid;
    }
}
