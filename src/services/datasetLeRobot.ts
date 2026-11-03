import { request } from '@umijs/max';

export type LeRobotEpisodeSummary = {
  episodeIndex: number;
  length: number;
  duration: number;
  tasks: string[];
};

export type LeRobotDatasetInfo = {
  datasetVersionId: string;
  codebaseVersion: string;
  robotType: string;
  fps: number;
  totalEpisodes: number;
  totalFrames: number;
  totalTasks: number;
  features: Record<string, unknown>;
  episodes: LeRobotEpisodeSummary[];
};

export type LeRobotVideo = {
  key: string;
  url: string;
  offset: number;
  to: number;
};

export type LeRobotEpisode = {
  episodeIndex: number;
  fps: number;
  length: number;
  duration: number;
  tasks: string[];
  stateNames: string[];
  actionNames: string[];
  timestamps: number[];
  frameIndices: number[];
  state: number[][];
  action: number[][];
  videos: Record<string, LeRobotVideo>;
};

export type LeRobotPointCloud = {
  episodeIndex: number;
  featureKey: string;
  dimensions: 3 | 6;
  frameIndices: number[];
  frames: number[][][];
};

export function getLeRobotInfo(versionId: string) {
  return request<LeRobotDatasetInfo>(
    `/v2/dataset-versions/${encodeURIComponent(versionId)}/lerobot/info`,
    { timeout: 5 * 60 * 1000 },
  );
}

export function getLeRobotEpisode(versionId: string, episodeIndex: number) {
  return request<LeRobotEpisode>(
    `/v2/dataset-versions/${encodeURIComponent(versionId)}/lerobot/episodes/${episodeIndex}`,
    { timeout: 5 * 60 * 1000 },
  );
}

export function getLeRobotPointCloud(
  versionId: string,
  episodeIndex: number,
  feature?: string,
) {
  return request<LeRobotPointCloud>(
    `/v2/dataset-versions/${encodeURIComponent(versionId)}/lerobot/episodes/${episodeIndex}/point-cloud`,
    {
      params: { feature: feature || undefined },
      timeout: 5 * 60 * 1000,
    },
  );
}
