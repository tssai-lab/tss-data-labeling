package com.tss.platform.training;

import com.tss.platform.config.TrainingKubernetesProperties;
import com.tss.platform.training.plan.TrainingRunSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

/**
 * Resolves the image that executes one immutable RunSpec.
 *
 * <p>The base worker image stays reusable. When approved code contains a
 * deterministic requirements.txt, this service builds and pushes a derived
 * image identified by the RunSpec environment fingerprint. Dependencies are
 * therefore installed before a Pod is scheduled, never inside a live task.
 * A shared registry is required so every Kubernetes node sees the same image.</p>
 */
@Component
public class TrainingRuntimeImageService {

    private static final Logger LOG = LoggerFactory.getLogger(TrainingRuntimeImageService.class);
    private static final Pattern SHA256 = Pattern.compile("^[a-f0-9]{64}$");
    private static final Pattern REPOSITORY = Pattern.compile("^[a-z0-9][a-z0-9._/:/-]*$");
    private static final Pattern PIP_INDEX_URL = Pattern.compile(
            "^https?://[A-Za-z0-9.-]+(?::[0-9]{1,5})?(?:/[A-Za-z0-9._~%/-]*)?/?$"
    );

    private final TrainingKubernetesProperties properties;
    private final ShellCommandRunner commandRunner;
    private final ConcurrentMap<String, Object> imageLocks = new ConcurrentHashMap<>();

    public TrainingRuntimeImageService(
            TrainingKubernetesProperties properties,
            ShellCommandRunner commandRunner
    ) {
        this.properties = properties;
        this.commandRunner = commandRunner;
    }

    public String resolveImage(TrainingRunSpec runSpec) {
        Objects.requireNonNull(runSpec, "runSpec");
        String baseImage = baseImage(runSpec);
        List<String> requirements = runSpec.inputs().code().requirements();
        if (requirements == null || requirements.isEmpty()) {
            return baseImage;
        }
        if (!properties.isRuntimeImageBuildEnabled()) {
            LOG.warn("training code declares requirements.txt, but automatic runtime image building is disabled; falling back to base image: {}", baseImage);
            return baseImage;
        }
        String repository = requireRepository();
        String fingerprint = runSpec.runtime().environmentFingerprint();
        if (fingerprint == null || !SHA256.matcher(fingerprint).matches()) {
            throw new IllegalStateException("RunSpec environment fingerprint is missing or invalid");
        }
        String image = repository + ":deps-" + fingerprint.substring(0, 24);
        synchronized (imageLocks.computeIfAbsent(image, ignored -> new Object())) {
            if (canPull(image)) {
                return image;
            }
            buildAndPush(image, baseImage, requirements);
            return image;
        }
    }

    private String baseImage(TrainingRunSpec runSpec) {
        String image = runSpec.runtime().image();
        if (image == null || image.isBlank() || image.contains("\n") || image.contains("\r")) {
            throw new IllegalStateException("RunSpec base runtime image is invalid");
        }
        String digest = runSpec.runtime().imageDigest();
        return digest == null || digest.isBlank() ? image : image + "@" + digest;
    }

    private String requireRepository() {
        String repository = properties.getRuntimeImageRepository() == null
                ? "" : properties.getRuntimeImageRepository().trim();
        if (!REPOSITORY.matcher(repository).matches() || repository.endsWith("/") || repository.contains("@")) {
            throw new IllegalStateException(
                    "training.kubernetes.runtime-image-repository must be a shared registry repository without a tag"
            );
        }
        return repository;
    }

    private boolean canPull(String image) {
        ShellCommandRunner.CommandResult result = commandRunner.run(
                List.of(properties.getRuntimeImageDockerPath(), "pull", image),
                null,
                properties.getRuntimeImageBuildTimeoutSeconds()
        );
        return result.success();
    }

    private void buildAndPush(String image, String baseImage, List<String> requirements) {
        Path context = createContext(image);
        try {
            Files.writeString(context.resolve("requirements.txt"), String.join("\n", requirements) + "\n",
                    StandardCharsets.UTF_8);
            Files.writeString(context.resolve("Dockerfile"), dockerfile(baseImage), StandardCharsets.UTF_8);

            List<String> buildCommand = new ArrayList<>();
            buildCommand.add(properties.getRuntimeImageDockerPath());
            buildCommand.add("build");
            if (!baseImageAvailableLocally(baseImage)) {
                buildCommand.add("--pull");
            }
            buildCommand.add("--tag");
            buildCommand.add(image);
            buildCommand.add(".");
            ShellCommandRunner.CommandResult build = commandRunner.run(
                    List.copyOf(buildCommand),
                    context,
                    properties.getRuntimeImageBuildTimeoutSeconds()
            );
            if (!build.success()) {
                throw commandFailure("build", image, build);
            }
            ShellCommandRunner.CommandResult push = commandRunner.run(
                    List.of(properties.getRuntimeImageDockerPath(), "push", image),
                    context,
                    properties.getRuntimeImageBuildTimeoutSeconds()
            );
            if (!push.success()) {
                throw commandFailure("push", image, push);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("cannot prepare runtime image build context: " + exception.getMessage(), exception);
        }
    }


    private boolean baseImageAvailableLocally(String baseImage) {
        return commandRunner.run(
                List.of(
                        properties.getRuntimeImageDockerPath(),
                        "image",
                        "inspect",
                        baseImage
                ),
                null,
                properties.getRuntimeImageBuildTimeoutSeconds()
        ).success();
    }

    private Path createContext(String image) {
        try {
            Path root = Path.of(properties.getRuntimeImageBuildDirectory()).toAbsolutePath().normalize();
            Files.createDirectories(root);
            String tag = image.substring(image.lastIndexOf(":deps-") + 6);
            return Files.createTempDirectory(root, "runtime-" + tag + "-");
        } catch (IOException exception) {
            throw new IllegalStateException("cannot create runtime image build directory: " + exception.getMessage(), exception);
        }
    }

    private String dockerfile(String baseImage) {
        String pipIndex = normalizedPipIndexUrl();
        int pipTimeout = Math.max(15, Math.min(
                properties.getRuntimeImagePipTimeoutSeconds(), 600
        ));
        int pipRetries = Math.max(0, Math.min(
                properties.getRuntimeImagePipRetries(), 20
        ));
        return "FROM " + baseImage + "\n"
                + "USER root\n"
                + "COPY requirements.txt /tmp/tss-requirements.txt\n"
                + "RUN python -m pip install --no-cache-dir --disable-pip-version-check"
                + " --timeout " + pipTimeout
                + " --retries " + pipRetries
                + (pipIndex == null ? "" : " --index-url " + pipIndex)
                + " -r /tmp/tss-requirements.txt"
                + " && rm -f /tmp/tss-requirements.txt\n"
                + "USER 10001\n";
    }

    private String normalizedPipIndexUrl() {
        String value = properties.getRuntimeImagePipIndexUrl();
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (!PIP_INDEX_URL.matcher(normalized).matches()) {
            throw new IllegalStateException(
                    "training.kubernetes.runtime-image-pip-index-url is invalid"
            );
        }
        return normalized;
    }

    private IllegalStateException commandFailure(
            String operation,
            String image,
            ShellCommandRunner.CommandResult result
    ) {
        String output = result.output() == null ? "" : result.output().trim();
        if (output.length() > 1000) {
            output = output.substring(output.length() - 1000);
        }
        return new IllegalStateException("runtime image " + operation + " failed for " + image
                + ": " + result.errorMessage() + (output.isBlank() ? "" : "\n" + output));
    }
}
