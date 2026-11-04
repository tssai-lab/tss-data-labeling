package com.tss.platform.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GpuDeviceObservationStoreTest {

    @Test
    void missingIdentityAndExpiredObservationsAreNotUsable() {
        GpuDeviceObservationStore store = new GpuDeviceObservationStore();
        Instant now = Instant.now();
        store.update("node-1", List.of(
                new GpuDeviceObservationStore.DeviceObservation("RTX", 16384L, 8192L)),
                now.minus(GpuDeviceObservationStore.MAX_AGE).minusSeconds(1));

        assertThat(store.fresh(null, now)).isEmpty();
        assertThat(store.fresh("", now)).isEmpty();
        assertThat(store.fresh("node-1", now)).isEmpty();
    }
}
