package com.tss.platform.dto.lerobot;

import java.util.List;

public record LeRobotPointCloudDto(
        long episodeIndex,
        String featureKey,
        int dimensions,
        List<Long> frameIndices,
        List<List<List<Double>>> frames
) {
}
