package com.tss.platform.service;

import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.repository.DatasetVersionRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatasetVersionLifecycleServiceTest {

    @Test
    void assertsReadyAndMutableDraftVersions() {
        DatasetVersionRepository repo = mock(DatasetVersionRepository.class);
        DatasetVersion ready = version("ready-1", "READY");
        DatasetVersion draft = version("draft-1", "DRAFT");
        when(repo.findByIdAndDeletedFalse(ready.getId())).thenReturn(Optional.of(ready));
        when(repo.findByIdAndDeletedFalse(draft.getId())).thenReturn(Optional.of(draft));
        DatasetVersionLifecycleService service = new DatasetVersionLifecycleService(repo);

        assertEquals(ready, service.assertReadyVersion(ready.getId()));
        assertEquals(draft, service.assertMutableDraftVersion(draft.getId()));
    }

    @Test
    void rejectsReadyAsMutableAndRejectsNonReadySources() {
        DatasetVersionRepository repo = mock(DatasetVersionRepository.class);
        DatasetVersion ready = version("ready-1", "READY");
        DatasetVersion deprecated = version("deprecated-1", "DEPRECATED");
        DatasetVersion archived = version("archived-1", "ARCHIVED");
        when(repo.findByIdAndDeletedFalse(ready.getId())).thenReturn(Optional.of(ready));
        when(repo.findByIdAndDeletedFalse(deprecated.getId())).thenReturn(Optional.of(deprecated));
        when(repo.findByIdAndDeletedFalse(archived.getId())).thenReturn(Optional.of(archived));
        DatasetVersionLifecycleService service = new DatasetVersionLifecycleService(repo);

        IllegalArgumentException mutableError = assertThrows(
                IllegalArgumentException.class,
                () -> service.assertMutableDraftVersion(ready.getId())
        );
        assertEquals(
                "dataset version must be mutable DRAFT: ready-1, status=READY",
                mutableError.getMessage()
        );

        for (DatasetVersion invalid : new DatasetVersion[]{deprecated, archived}) {
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> service.assertReadyVersion(invalid.getId())
            );
            assertEquals(
                    "dataset version must be READY: " + invalid.getId()
                            + ", status=" + invalid.getStatus(),
                    error.getMessage()
            );
        }
    }

    @Test
    void rejectsDeletedOrMissingVersion() {
        DatasetVersionRepository repo = mock(DatasetVersionRepository.class);
        when(repo.findByIdAndDeletedFalse("deleted-1")).thenReturn(Optional.empty());
        DatasetVersionLifecycleService service = new DatasetVersionLifecycleService(repo);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.assertReadyVersion("deleted-1")
        );

        assertEquals("dataset version not found: deleted-1", error.getMessage());
    }

    private static DatasetVersion version(String id, String status) {
        DatasetVersion version = new DatasetVersion();
        version.setId(id);
        version.setStatus(status);
        version.setDeleted(false);
        return version;
    }
}

