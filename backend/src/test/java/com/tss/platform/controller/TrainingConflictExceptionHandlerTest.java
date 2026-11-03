package com.tss.platform.controller;

import com.tss.platform.service.TrainingExperimentService;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TrainingConflictExceptionHandlerTest {
    @Test
    void concurrentUpdateReturnsAnExplicitConflictInsteadOfAnUnknownServerError() throws Exception {
        TrainingExperimentService service = mock(TrainingExperimentService.class);
        when(service.getByIdOrExperimentId("task"))
                .thenThrow(new ObjectOptimisticLockingFailureException("training", "task"));
        MockMvcBuilders.standaloneSetup(new TrainingTaskController(service))
                .setControllerAdvice(new TrainingConflictExceptionHandler()).build()
                .perform(get("/api/task/detail").param("id", "task"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorMessage").value("任务状态已被更新，请刷新后重试"));
    }
}
