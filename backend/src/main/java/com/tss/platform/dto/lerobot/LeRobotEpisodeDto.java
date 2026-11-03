package com.tss.platform.dto.lerobot;

import java.util.List;
import java.util.Map;

public record LeRobotEpisodeDto(
        long episodeIndex,
        int fps,
        long length,
        double duration,
        List<String> tasks,
        List<String> stateNames,
        List<String> actionNames,
        List<Double> timestamps,
        List<Long> frameIndices,
        List<List<Double>> state,
        List<List<Double>> action,
        Map<String, LeRobotVideoDto> videos
) {
}
