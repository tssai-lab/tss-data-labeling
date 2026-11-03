package com.tss.platform.dto.lerobot;

import java.util.List;

public record LeRobotEpisodeSummaryDto(
        long episodeIndex,
        long length,
        double duration,
        List<String> tasks
) {
}
