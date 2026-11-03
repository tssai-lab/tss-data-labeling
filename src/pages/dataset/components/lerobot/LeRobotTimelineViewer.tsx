import {
  CaretRightOutlined,
  CloseOutlined,
  PauseOutlined,
  StepBackwardOutlined,
  StepForwardOutlined,
} from '@ant-design/icons';
import { Alert, Button, Empty, Segmented, Select, Spin, Tooltip } from 'antd';
import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import {
  getLeRobotEpisode,
  getLeRobotInfo,
  type LeRobotDatasetInfo,
  type LeRobotEpisode,
} from '@/services/datasetLeRobot';
import { getApiErrorMessage } from '@/utils/apiError';
import JointTimelineChart from './JointTimelineChart';
import LeRobotPointCloudView from './LeRobotPointCloudView';
import './timeline.css';

const formatTime = (value: number) => {
  const minutes = Math.floor(value / 60);
  const seconds = Math.floor(value % 60);
  const millis = Math.floor((value % 1) * 1000);
  return `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}.${String(millis).padStart(3, '0')}`;
};

const cameraLabel = (key: string) => {
  const name = key.split('.').pop() || key;
  if (name === 'top') return '顶部相机';
  if (name === 'wrist') return '腕部相机';
  return name;
};

type Props = {
  versionId: string;
  onClose: () => void;
};

const LeRobotTimelineViewer: React.FC<Props> = ({ versionId, onClose }) => {
  const [info, setInfo] = useState<LeRobotDatasetInfo>();
  const [episode, setEpisode] = useState<LeRobotEpisode>();
  const [selectedEpisode, setSelectedEpisode] = useState<number>();
  const [currentTime, setCurrentTime] = useState(0);
  const [speed, setSpeed] = useState(1);
  const [playing, setPlaying] = useState(false);
  const [visualization, setVisualization] = useState<'timeline' | 'pointcloud'>(
    'timeline',
  );
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string>();
  const videoRefs = useRef<Record<string, HTMLVideoElement | null>>({});
  const animationRef = useRef<number | undefined>(undefined);
  const lastTickRef = useRef(0);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    getLeRobotInfo(versionId)
      .then((data) => {
        if (cancelled) return;
        setInfo(data);
        setSelectedEpisode(data.episodes[0]?.episodeIndex);
      })
      .catch((reason) => !cancelled && setError(getApiErrorMessage(reason)))
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, [versionId]);

  useEffect(() => {
    if (selectedEpisode == null) return;
    let cancelled = false;
    setPlaying(false);
    setLoading(true);
    setError(undefined);
    getLeRobotEpisode(versionId, selectedEpisode)
      .then((data) => {
        if (cancelled) return;
        setEpisode(data);
        setCurrentTime(0);
      })
      .catch((reason) => !cancelled && setError(getApiErrorMessage(reason)))
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, [selectedEpisode, versionId]);

  const frame = useMemo(() => {
    if (!episode) return 0;
    return Math.max(
      0,
      Math.min(episode.length - 1, Math.round(currentTime * episode.fps)),
    );
  }, [currentTime, episode]);

  const syncVideos = useCallback(
    (time: number, force = false) => {
      if (!episode) return;
      Object.entries(episode.videos).forEach(([key, spec]) => {
        const video = videoRefs.current[key];
        if (!video) return;
        const target = spec.offset + time;
        if (force || Math.abs(video.currentTime - target) > 0.08)
          video.currentTime = target;
        video.playbackRate = speed;
      });
    },
    [episode, speed],
  );

  const setTime = useCallback(
    (value: number, forceVideo = true) => {
      if (!episode) return;
      const next = Math.max(
        0,
        Math.min(value, Math.max(0, episode.duration - 1 / episode.fps)),
      );
      setCurrentTime(next);
      if (forceVideo) syncVideos(next, true);
    },
    [episode, syncVideos],
  );

  const pause = useCallback(() => {
    setPlaying(false);
    Object.values(videoRefs.current).forEach((video) => {
      video?.pause();
    });
    if (animationRef.current) cancelAnimationFrame(animationRef.current);
  }, []);

  useEffect(() => pause, [pause]);

  const tick = useCallback(
    (now: number) => {
      if (!episode) return;
      const primaryKey = Object.keys(episode.videos)[0];
      const primary = primaryKey ? videoRefs.current[primaryKey] : null;
      const primarySpec = primaryKey ? episode.videos[primaryKey] : null;
      const videoTime =
        primary && primarySpec && !primary.paused
          ? primary.currentTime - primarySpec.offset
          : null;
      const elapsed = ((now - lastTickRef.current) / 1000) * speed;
      lastTickRef.current = now;
      const next =
        videoTime != null && Number.isFinite(videoTime)
          ? videoTime
          : currentTime + elapsed;
      if (next >= episode.duration - 1 / episode.fps) {
        setTime(episode.duration, true);
        pause();
        return;
      }
      setCurrentTime(next);
      syncVideos(next, false);
      animationRef.current = requestAnimationFrame(tick);
    },
    [currentTime, episode, pause, setTime, speed, syncVideos],
  );

  const play = useCallback(() => {
    if (!episode) return;
    const atEnd = currentTime >= episode.duration - 2 / episode.fps;
    const start = atEnd ? 0 : currentTime;
    if (atEnd) setCurrentTime(0);
    syncVideos(start, true);
    Object.values(videoRefs.current).forEach((video) => {
      video?.play().catch(() => undefined);
    });
    setPlaying(true);
    lastTickRef.current = performance.now();
    animationRef.current = requestAnimationFrame(tick);
  }, [currentTime, episode, syncVideos, tick]);

  useEffect(() => {
    const keydown = (event: KeyboardEvent) => {
      if ((event.target as HTMLElement)?.matches('input,select,textarea'))
        return;
      if (event.code === 'Space') {
        event.preventDefault();
        playing ? pause() : play();
      }
      if (event.code === 'ArrowLeft' && episode) {
        pause();
        setTime(currentTime - 1 / episode.fps);
      }
      if (event.code === 'ArrowRight' && episode) {
        pause();
        setTime(currentTime + 1 / episode.fps);
      }
    };
    window.addEventListener('keydown', keydown);
    return () => window.removeEventListener('keydown', keydown);
  }, [currentTime, episode, pause, play, playing, setTime]);

  if (loading && !episode)
    return (
      <div className="lerobot-center">
        <Spin size="large" tip="正在准备 LeRobot 数据..." />
      </div>
    );
  if (error && !episode)
    return (
      <div className="lerobot-center">
        <Alert
          type="error"
          showIcon
          message="无法加载时序数据"
          description={error}
        />
      </div>
    );
  if (!info || !episode)
    return (
      <div className="lerobot-center">
        <Empty description="该数据集没有可回放的 Episode" />
      </div>
    );

  const names = episode.stateNames.length
    ? episode.stateNames
    : (episode.state[0] || []).map((_, index) => `joint_${index}`);
  const progress = currentTime / Math.max(episode.duration, 0.001);
  const pointCloudFeatures = Object.entries(info.features)
    .filter(([key, raw]) => {
      const feature = raw as { shape?: number[] } | undefined;
      const dimensions = feature?.shape?.[feature.shape.length - 1];
      return (
        /point|pcd|cloud|lidar/i.test(key) &&
        (dimensions === 3 || dimensions === 6)
      );
    })
    .map(([key]) => key);
  const videoEntries = Object.entries(episode.videos);
  const videoCount = videoEntries.length;
  const videoColumns =
    videoCount <= 2 ? 1 : videoCount <= 4 ? 2 : videoCount <= 9 ? 3 : 4;
  const videoRows = Math.max(1, Math.ceil(videoCount / videoColumns));
  const chartColumns = names.length <= 3 ? 1 : names.length <= 8 ? 2 : 3;
  const chartRows = Math.max(1, Math.ceil(names.length / chartColumns));
  const pointCloudColumns =
    pointCloudFeatures.length <= 1 ? 1 : pointCloudFeatures.length <= 4 ? 2 : 3;
  const pointCloudRows = Math.max(
    1,
    Math.ceil(pointCloudFeatures.length / pointCloudColumns),
  );
  const leftShare = Math.max(
    34,
    Math.min(
      58,
      34 +
        Math.min(Math.max(videoCount - 1, 0), 4) * 5 -
        Math.max(names.length - 6, 0),
    ),
  );
  const workspaceStyle = {
    '--lerobot-left-share': `${leftShare}%`,
    '--lerobot-video-columns': videoColumns,
    '--lerobot-video-rows': videoRows,
    '--lerobot-chart-columns': chartColumns,
    '--lerobot-chart-rows': chartRows,
    '--lerobot-pointcloud-columns': pointCloudColumns,
    '--lerobot-pointcloud-rows': pointCloudRows,
  } as React.CSSProperties;

  return (
    <div className="lerobot-viewer">
      <header className="lerobot-toolbar">
        <div className="lerobot-title-block">
          <strong>LeRobot 数据回放</strong>
          <span>
            {info.robotType.toUpperCase()} · {info.totalEpisodes} Episodes ·{' '}
            {info.totalFrames.toLocaleString()} Frames · {info.fps} FPS
          </span>
        </div>
        <div className="lerobot-episode-control">
          Episode
          <Select
            value={selectedEpisode}
            onChange={setSelectedEpisode}
            options={info.episodes.map((item) => ({
              value: item.episodeIndex,
              label: `Episode ${String(item.episodeIndex).padStart(2, '0')} · ${item.duration.toFixed(1)}s · ${item.length} 帧`,
            }))}
            popupMatchSelectWidth={300}
          />
        </div>
        <Tooltip title="关闭">
          <Button
            className="lerobot-close"
            type="text"
            aria-label="关闭时序预览"
            icon={<CloseOutlined />}
            onClick={onClose}
          />
        </Tooltip>
      </header>

      <main className="lerobot-main">
        <div className="lerobot-controls">
          <Tooltip title={playing ? '暂停' : '播放'}>
            <Button
              type="primary"
              icon={playing ? <PauseOutlined /> : <CaretRightOutlined />}
              onClick={playing ? pause : play}
            />
          </Tooltip>
          <Tooltip title="上一帧">
            <Button
              icon={<StepBackwardOutlined />}
              onClick={() => {
                pause();
                setTime(currentTime - 1 / episode.fps);
              }}
            />
          </Tooltip>
          <Tooltip title="下一帧">
            <Button
              icon={<StepForwardOutlined />}
              onClick={() => {
                pause();
                setTime(currentTime + 1 / episode.fps);
              }}
            />
          </Tooltip>
          <div className="lerobot-time">
            <strong>{formatTime(currentTime)}</strong>
            <span>/</span>
            <span>{formatTime(episode.duration)}</span>
          </div>
          <div className="lerobot-speed">
            速度
            <Select
              value={speed}
              onChange={(value) => {
                setSpeed(value);
                Object.values(videoRefs.current).forEach((video) => {
                  if (video) video.playbackRate = value;
                });
              }}
              options={[0.25, 0.5, 1, 1.5, 2].map((value) => ({
                value,
                label: `${value}×`,
              }))}
            />
          </div>
          <span className="lerobot-frame">
            Frame {frame} / {episode.length - 1}
          </span>
        </div>

        {error && (
          <Alert
            type="warning"
            showIcon
            message={error}
            closable
            onClose={() => setError(undefined)}
          />
        )}
        <div className="lerobot-workspace" style={workspaceStyle}>
          <section className="lerobot-video-grid">
            {videoEntries.map(([key, spec]) => (
              <article className="lerobot-video-panel" key={key}>
                <div>
                  <strong>{cameraLabel(key)}</strong>
                  <span>{key}</span>
                </div>
                <video
                  ref={(node) => {
                    videoRefs.current[key] = node;
                  }}
                  src={spec.url}
                  muted
                  playsInline
                  preload="metadata"
                />
              </article>
            ))}
          </section>

          <section className={`lerobot-timeline-section is-${visualization}`}>
            <div className="lerobot-section-heading">
              <Segmented
                className="lerobot-visualization-switch"
                size="small"
                value={visualization}
                onChange={(value) =>
                  setVisualization(value as 'timeline' | 'pointcloud')
                }
                options={[
                  { label: '折线图', value: 'timeline' },
                  ...(pointCloudFeatures.length
                    ? [{ label: '点云图', value: 'pointcloud' }]
                    : []),
                ]}
              />
              <div>
                <h2>
                  {visualization === 'pointcloud'
                    ? `点云视图（${pointCloudFeatures.length} 组）`
                    : '关节时间轴'}
                </h2>
                <p>{episode.tasks.join(' · ')}</p>
              </div>
              <div className="lerobot-legend">
                <span>
                  <i className="state" />
                  观测状态
                </span>
                <span>
                  <i className="action" />
                  动作指令
                </span>
              </div>
            </div>
            {visualization === 'timeline' && (
              <div className="lerobot-joints">
                {names.map((name, joint) => (
                  <JointTimelineChart
                    key={name}
                    name={name}
                    state={episode.state.map((row) => row[joint] ?? 0)}
                    action={episode.action.map((row) => row[joint] ?? 0)}
                    progress={progress}
                    stateValue={episode.state[frame]?.[joint] ?? 0}
                    actionValue={episode.action[frame]?.[joint] ?? 0}
                  />
                ))}
              </div>
            )}
            {visualization === 'pointcloud' &&
              pointCloudFeatures.length > 0 && (
                <div className="lerobot-pointcloud-grid">
                  {pointCloudFeatures.map((feature) => (
                    <LeRobotPointCloudView
                      key={feature}
                      versionId={versionId}
                      episodeIndex={episode.episodeIndex}
                      frame={frame}
                      feature={feature}
                    />
                  ))}
                </div>
              )}
          </section>
        </div>
      </main>

      <footer className="lerobot-scrubber">
        <div>
          <strong>
            {formatTime(currentTime)} / {formatTime(episode.duration)}
          </strong>
          <span>
            Frame {frame} / {episode.length - 1}
          </span>
        </div>
        <input
          type="range"
          min={0}
          max={episode.duration}
          step={1 / episode.fps}
          value={currentTime}
          onChange={(event) => {
            pause();
            setTime(Number(event.target.value));
          }}
        />
        <div className="lerobot-ticks">
          {[0, 0.25, 0.5, 0.75, 1].map((value) => (
            <span key={value}>{(episode.duration * value).toFixed(1)}s</span>
          ))}
        </div>
      </footer>
      {loading && (
        <div className="lerobot-loading">
          <Spin size="large" tip="正在加载 Episode..." />
        </div>
      )}
    </div>
  );
};

export default LeRobotTimelineViewer;
