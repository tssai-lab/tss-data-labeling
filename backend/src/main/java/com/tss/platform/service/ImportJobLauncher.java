package com.tss.platform.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ImportJobLauncher {

    private static final Logger log = LoggerFactory.getLogger(ImportJobLauncher.class);

    private final TaskExecutor taskExecutor;
    private final ImportJobService importJobService;
    private final Set<String> queuedJobs = ConcurrentHashMap.newKeySet();

    public ImportJobLauncher(
            @Qualifier("importJobTaskExecutor") TaskExecutor taskExecutor,
            ImportJobService importJobService
    ) {
        this.taskExecutor = taskExecutor;
        this.importJobService = importJobService;
    }

    public void launch(String importJobId) {
        if (importJobId == null || importJobId.isBlank()) {
            throw new IllegalArgumentException("importJobId cannot be blank");
        }
        if (!queuedJobs.add(importJobId)) {
            return;
        }
        try {
            taskExecutor.execute(() -> {
                try {
                    importJobService.execute(importJobId);
                } catch (RuntimeException exception) {
                    log.warn("Import job {} was not started: {}", importJobId, exception.getMessage());
                } finally {
                    queuedJobs.remove(importJobId);
                }
            });
        } catch (RuntimeException exception) {
            queuedJobs.remove(importJobId);
            throw exception;
        }
    }
}
