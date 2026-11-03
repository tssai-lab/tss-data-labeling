package com.tss.platform.controller.v2;

import com.tss.platform.dto.lerobot.LeRobotDatasetInfoDto;
import com.tss.platform.dto.lerobot.LeRobotEpisodeDto;
import com.tss.platform.dto.lerobot.LeRobotPointCloudDto;
import com.tss.platform.service.LeRobotDatasetPreviewService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/v2/dataset-versions/{versionId}/lerobot")
public class V2LeRobotDatasetPreviewController {

    private static final Pattern RANGE = Pattern.compile("bytes=(\\d*)-(\\d*)");
    private final LeRobotDatasetPreviewService service;

    public V2LeRobotDatasetPreviewController(LeRobotDatasetPreviewService service) {
        this.service = service;
    }

    @GetMapping("/info")
    public LeRobotDatasetInfoDto info(@PathVariable String versionId) {
        return service.info(versionId);
    }

    @GetMapping("/episodes/{episodeIndex}")
    public LeRobotEpisodeDto episode(
            @PathVariable String versionId,
            @PathVariable long episodeIndex
    ) {
        return service.episode(versionId, episodeIndex);
    }

    @GetMapping("/episodes/{episodeIndex}/point-cloud")
    public LeRobotPointCloudDto pointCloud(
            @PathVariable String versionId,
            @PathVariable long episodeIndex,
            @RequestParam(required = false) String feature
    ) {
        return service.pointCloud(versionId, episodeIndex, feature);
    }

    @GetMapping("/media")
    public ResponseEntity<InputStreamResource> media(
            @PathVariable String versionId,
            @RequestParam String ticket,
            HttpServletRequest request
    ) {
        try {
            LeRobotDatasetPreviewService.MediaFile media = service.requireMedia(versionId, ticket);
            ByteRange range = parseRange(request.getHeader(HttpHeaders.RANGE), media.size());
            long length = range.end() - range.start() + 1;
            InputStreamResource body = new InputStreamResource(
                    new BoundedFileInputStream(media.path().toFile(), range.start(), length)
            );
            ResponseEntity.BodyBuilder response = ResponseEntity.status(
                            range.partial() ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK
                    )
                    .contentType(MediaType.parseMediaType(media.contentType()))
                    .contentLength(length)
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .header(HttpHeaders.CACHE_CONTROL, "private, max-age=60");
            if (range.partial()) {
                response.header(
                        HttpHeaders.CONTENT_RANGE,
                        "bytes " + range.start() + "-" + range.end() + "/" + media.size()
                );
            }
            return response.body(body);
        } catch (IOException exception) {
            throw new IllegalArgumentException("failed to open LeRobot video", exception);
        }
    }

    private ByteRange parseRange(String value, long size) {
        if (size <= 0) throw new IllegalArgumentException("LeRobot video is empty");
        if (value == null || value.isBlank()) return new ByteRange(0, size - 1, false);
        Matcher match = RANGE.matcher(value.trim());
        if (!match.matches()) throw new IllegalArgumentException("invalid media range");
        String first = match.group(1);
        String last = match.group(2);
        long start;
        long end;
        if (!first.isBlank()) {
            start = Long.parseLong(first);
            end = last.isBlank() ? size - 1 : Math.min(Long.parseLong(last), size - 1);
        } else if (!last.isBlank()) {
            long suffix = Long.parseLong(last);
            start = Math.max(size - suffix, 0);
            end = size - 1;
        } else {
            throw new IllegalArgumentException("invalid media range");
        }
        if (start < 0 || start > end || start >= size) {
            throw new IllegalArgumentException("media range is outside the file");
        }
        return new ByteRange(start, end, true);
    }

    private record ByteRange(long start, long end, boolean partial) { }

    private static final class BoundedFileInputStream extends InputStream {
        private final FileInputStream delegate;
        private long remaining;

        private BoundedFileInputStream(java.io.File file, long start, long length) throws IOException {
            this.delegate = new FileInputStream(file);
            this.remaining = length;
            delegate.getChannel().position(start);
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) return -1;
            int value = delegate.read();
            if (value >= 0) remaining -= 1;
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (remaining <= 0) return -1;
            int read = delegate.read(buffer, offset, (int) Math.min(length, remaining));
            if (read > 0) remaining -= read;
            return read;
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
