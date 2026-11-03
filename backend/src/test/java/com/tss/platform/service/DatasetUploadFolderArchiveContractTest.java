package com.tss.platform.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

/** 重构前建立的目录打包契约；这里只验证打包入口，权限与事务由原有上传用例单独验证。 */
class DatasetUploadFolderArchiveContractTest {
    @TempDir Path directory;
    private final DatasetUploadService service = mock(DatasetUploadService.class, CALLS_REAL_METHODS);

    private int write(List<MultipartFile> files, List<String> paths, String format) {
        return ReflectionTestUtils.invokeMethod(service, "writeCvFolderZip", directory.resolve("archive.zip"), files, paths, format);
    }

    private MultipartFile image() {
        return new MockMultipartFile("files", "image.png", "image/png", new byte[]{1, 2, 3});
    }

    @Test void normalizesRelativePathsAndKeepsBytesAndImageCount() throws Exception {
        assertEquals(1, write(List.of(image(), new MockMultipartFile("files", "labels.xml", "text/xml", "<label/>".getBytes())), List.of("cat\\image.png", "labels.xml"), "VOC"));
        try (var zip = new ZipInputStream(Files.newInputStream(directory.resolve("archive.zip")))) {
            assertEquals("cat/image.png", zip.getNextEntry().getName());
            assertArrayEquals(new byte[]{1, 2, 3}, zip.readAllBytes());
            assertEquals("labels.xml", zip.getNextEntry().getName());
            assertNull(zip.getNextEntry());
        }
    }

    @Test void rejectsTraversalAbsoluteDriveAndNullBytePaths() {
        for (String path : List.of("../image.png", "/image.png", "C:\\image.png", "folder/../image.png", "bad\u0000.png")) {
            var error = assertThrows(IllegalArgumentException.class, () -> write(List.of(image()), List.of(path), "NONE"));
            assertTrue(error.getMessage().contains("路径非法"));
        }
    }

    @Test void rejectsCollisionsAfterPathNormalization() {
        for (var paths : List.of(List.of("cat/image.png", "cat/./image.png"), List.of("a?.png", "a*.png"))) {
            var error = assertThrows(IllegalArgumentException.class, () -> write(List.of(image(), image()), paths, "NONE"));
            assertTrue(error.getMessage().contains("重复路径"));
        }
    }

    @Test void preservesFormatAllowlistAndEmptyFileRejection() {
        assertThrows(IllegalArgumentException.class, () -> write(List.of(image()), List.of("image.exe"), "OTHER"));
        assertThrows(IllegalArgumentException.class, () -> write(List.of(new MockMultipartFile("files", new byte[0])), List.of("empty.png"), "NONE"));
    }

    @Test void closesInputAndArchiveWhenReadingFails() throws Exception {
        var closed = new AtomicBoolean();
        MultipartFile file = new MockMultipartFile("files", "image.png", "image/png", new byte[]{1}) {
            @Override public ByteArrayInputStream getInputStream() {
                return new ByteArrayInputStream(new byte[]{1}) {
                    @Override public long transferTo(java.io.OutputStream out) throws IOException { throw new IOException("injected read failure"); }
                    @Override public void close() throws IOException { closed.set(true); super.close(); }
                };
            }
        };
        assertThrows(RuntimeException.class, () -> write(List.of(file), List.of("image.png"), "NONE"));
        assertTrue(closed.get());
        assertTrue(Files.deleteIfExists(directory.resolve("archive.zip")), "失败后文件句柄必须释放，允许外层清理临时文件");
    }
}
