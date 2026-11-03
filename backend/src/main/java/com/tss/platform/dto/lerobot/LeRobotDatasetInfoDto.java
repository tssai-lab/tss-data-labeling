package com.tss.platform.dto.lerobot;

import java.util.List;
import java.util.Map;

public record LeRobotDatasetInfoDto(
        String datasetVersionId,
        String codebaseVersion,
        String robotType,
        int fps,
        long totalEpisodes,
        long totalFrames,
        long totalTasks,
        Map<String, Object> features,
        List<LeRobotEpisodeSummaryDto> episodes
) {
}
