package com.tss.platform.controller.v2;

import com.tss.platform.dto.lerobot.LeRobotPointCloudDto;
import com.tss.platform.service.LeRobotDatasetPreviewService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class V2LeRobotDatasetPreviewControllerTest {
    @Test
    void forwardsPointCloudFeatureAndEpisode() {
        LeRobotDatasetPreviewService service = mock(LeRobotDatasetPreviewService.class);
        LeRobotPointCloudDto expected = new LeRobotPointCloudDto(
                3,
                "observation.points.gripper_pcds",
                3,
                List.of(0L),
                List.of(List.of(List.of(0.1, 0.2, 0.3)))
        );
        when(service.pointCloud("version-1", 3, "observation.points.gripper_pcds"))
                .thenReturn(expected);

        LeRobotPointCloudDto result = new V2LeRobotDatasetPreviewController(service).pointCloud(
                "version-1",
                3,
                "observation.points.gripper_pcds"
        );

        assertEquals(expected, result);
        verify(service).pointCloud("version-1", 3, "observation.points.gripper_pcds");
    }

    @Test
    void streamsRequestedVideoRange() throws Exception {
        byte[] video = new byte[]{0, 1, 2, 3, 4, 5, 6, 7};
        Path targetDir = Path.of("target", "test-media");
        Files.createDirectories(targetDir);
        Path file = Files.createTempFile(targetDir, "video-", ".mp4");
        try {
            Files.write(file, video);

            LeRobotDatasetPreviewService service = mock(LeRobotDatasetPreviewService.class);
            when(service.requireMedia("version-1", "ticket-1"))
                    .thenReturn(new LeRobotDatasetPreviewService.MediaFile(
                            file,
                            video.length,
                            "video/mp4"
                    ));
            HttpServletRequest request = mock(HttpServletRequest.class);
            when(request.getHeader(HttpHeaders.RANGE)).thenReturn("bytes=2-5");

            ResponseEntity<InputStreamResource> response =
                    new V2LeRobotDatasetPreviewController(service)
                            .media("version-1", "ticket-1", request);

            assertEquals(HttpStatus.PARTIAL_CONTENT, response.getStatusCode());
            assertEquals("bytes 2-5/8", response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE));
            assertEquals(4L, response.getHeaders().getContentLength());
            try (InputStream input = response.getBody().getInputStream()) {
                assertArrayEquals(new byte[]{2, 3, 4, 5}, input.readAllBytes());
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
