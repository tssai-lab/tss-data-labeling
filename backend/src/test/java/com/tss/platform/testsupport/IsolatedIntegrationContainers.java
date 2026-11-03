package com.tss.platform.testsupport;

import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import java.util.Arrays;
import org.testcontainers.containers.GenericContainer;

/** 只给本轮独立测试加资源和端口约束，不修改业务容器或默认 CI 配置。 */
public final class IsolatedIntegrationContainers {
    private IsolatedIntegrationContainers() {}

    public static void configure(GenericContainer<?>... containers) {
        String run = System.getenv("TSS_GOVERNANCE_TEST_RUN");
        if (run == null || run.isBlank()) return;
        if (!run.matches("[a-zA-Z0-9_-]{1,80}")) {
            throw new IllegalArgumentException("测试运行标识无效");
        }
        for (GenericContainer<?> container : containers) {
            container.withLabel("com.tss.governance.run", run);
            container.withImagePullPolicy(new LocalOnlyImagePullPolicy());
            container.withCreateContainerCmdModifier(command -> {
                command.getHostConfig()
                        .withNanoCPUs(1_000_000_000L)
                        .withMemory(1024L * 1024 * 1024)
                        .withMemorySwap(1024L * 1024 * 1024)
                        .withPortBindings(Arrays.stream(command.getExposedPorts())
                                .map(port -> new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", 0), port))
                                .toArray(PortBinding[]::new));
            });
        }
    }
}
