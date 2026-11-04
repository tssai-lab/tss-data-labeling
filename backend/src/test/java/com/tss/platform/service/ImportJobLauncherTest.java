package com.tss.platform.service;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ImportJobLauncherTest {

    @Test
    void delegatesImportToManagedTaskExecutor() {
        ImportJobService importJobService = mock(ImportJobService.class);
        TaskExecutor directExecutor = Runnable::run;
        ImportJobLauncher launcher = new ImportJobLauncher(directExecutor, importJobService);

        launcher.launch("ijob-1");

        verify(importJobService).execute("ijob-1");
    }

    @Test
    void doesNotQueueTheSamePendingJobTwice() {
        ImportJobService importJobService = mock(ImportJobService.class);
        List<Runnable> queued = new ArrayList<>();
        TaskExecutor collectingExecutor = queued::add;
        ImportJobLauncher launcher = new ImportJobLauncher(collectingExecutor, importJobService);

        launcher.launch("ijob-1");
        launcher.launch("ijob-1");

        assertEquals(1, queued.size());
        queued.get(0).run();
        verify(importJobService).execute("ijob-1");
    }
}
