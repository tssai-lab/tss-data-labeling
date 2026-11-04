package com.tss.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tss.platform.config.TrainingKubernetesProperties;
import com.tss.platform.training.ShellCommandRunner;
import com.tss.platform.training.TrainingEnvironmentService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class KubernetesResourceQuotaClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final KubernetesResourceQuotaClient client = new KubernetesResourceQuotaClient(
            mock(TrainingEnvironmentService.class),
            mock(ShellCommandRunner.class),
            objectMapper,
            new TrainingKubernetesProperties()
    );

    @Test
    void parsesActualHardLimitsAndUsesMaximumPodUsage() {
        KubernetesResourceQuotaClient.QuotaSnapshot snapshot = client.parse("""
                {
                  "spec":{"hard":{"pods":"10","count/pods":"10","count/jobs.batch":"20"}},
                  "status":{"used":{"pods":"2","count/pods":"4","count/jobs.batch":"7"}}
                }
                """);

        assertEquals(10, snapshot.podQuota());
        assertEquals(20, snapshot.jobQuota());
        assertEquals(4, snapshot.usedPods());
        assertEquals(7, snapshot.usedJobs());
    }

    @Test
    void rejectsDivergentPodQuotaAliasesAndInvalidQuantities() {
        assertThrows(IllegalStateException.class, () -> client.parse("""
                {
                  "spec":{"hard":{"pods":"9","count/pods":"10","count/jobs.batch":"20"}},
                  "status":{"used":{}}
                }
                """));
        assertThrows(IllegalStateException.class, () -> client.parse("""
                {
                  "spec":{"hard":{"pods":"10","count/jobs.batch":"2.5"}},
                  "status":{"used":{}}
                }
                """));
    }

    @Test
    void patchUpdatesBothPodQuotaKeysWithoutTouchingCpuOrMemory() throws Exception {
        JsonNode patch = objectMapper.readTree(client.buildPatch(8, 15));
        JsonNode hard = patch.path("spec").path("hard");

        assertEquals("8", hard.path("pods").asText());
        assertEquals("8", hard.path("count/pods").asText());
        assertEquals("15", hard.path("count/jobs.batch").asText());
        assertEquals(3, hard.size());
    }
}
