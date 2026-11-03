package com.tss.platform.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class KubernetesQuantityParserTest {

    @Test
    void parsesCpuUnitsReturnedByKubernetes() {
        assertThat(KubernetesQuantityParser.cpuCores("2")).isEqualTo(2.0);
        assertThat(KubernetesQuantityParser.cpuCores("250m")).isEqualTo(0.25);
        assertThat(KubernetesQuantityParser.cpuCores("128724u")).isEqualTo(0.128724);
        assertThat(KubernetesQuantityParser.cpuCores("128724µ")).isEqualTo(0.128724);
        assertThat(KubernetesQuantityParser.cpuCores("142272057n")).isEqualTo(0.142272057);
        assertThat(KubernetesQuantityParser.cpuCores(" ")).isZero();
    }

    @Test
    void rejectsInvalidCpuInsteadOfTurningItIntoHealthyZero() {
        assertThatIllegalArgumentException().isThrownBy(() -> KubernetesQuantityParser.cpuCores("12x"));
        assertThatIllegalArgumentException().isThrownBy(() -> KubernetesQuantityParser.cpuCores("-1m"));
        assertThatIllegalArgumentException().isThrownBy(() -> KubernetesQuantityParser.cpuCores("NaN"));
    }

    @Test
    void parsesBinaryAndDecimalMemoryUnits() {
        assertThat(KubernetesQuantityParser.memoryBytes("1Ki")).isEqualTo(1024);
        assertThat(KubernetesQuantityParser.memoryBytes("2Mi")).isEqualTo(2L * 1024 * 1024);
        assertThat(KubernetesQuantityParser.memoryBytes("3Gi")).isEqualTo(3L * 1024 * 1024 * 1024);
        assertThat(KubernetesQuantityParser.memoryBytes("1Ti")).isEqualTo(1024L * 1024 * 1024 * 1024);
        assertThat(KubernetesQuantityParser.memoryBytes("2G")).isEqualTo(2_000_000_000L);
        assertThat(KubernetesQuantityParser.memoryBytes("512")).isEqualTo(512);
        assertThat(KubernetesQuantityParser.memoryBytes(null)).isZero();
    }

    @Test
    void rejectsUnknownNegativeFractionalByteAndOverflowMemory() {
        assertThatIllegalArgumentException().isThrownBy(() -> KubernetesQuantityParser.memoryBytes("1Zi"));
        assertThatIllegalArgumentException().isThrownBy(() -> KubernetesQuantityParser.memoryBytes("-1Mi"));
        assertThatIllegalArgumentException().isThrownBy(() -> KubernetesQuantityParser.memoryBytes("0.5"));
        assertThatIllegalArgumentException().isThrownBy(() -> KubernetesQuantityParser.memoryBytes("9Ei"));
    }
}
