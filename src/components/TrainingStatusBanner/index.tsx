import {
  CheckCircleOutlined,
  CloseCircleOutlined,
  LoadingOutlined,
  PauseCircleOutlined,
  SyncOutlined,
} from '@ant-design/icons';
import { Alert, Progress, Space, Typography } from 'antd';
import React from 'react';
import { isActiveTaskStatus } from '@/utils/trainingMetrics';
import {
  buildTrainingSuccessSummary,
  getTrainingProgressStatus,
  getTrainingStatusText,
  normalizeTrainingProgress,
} from '@/utils/trainingStatusDisplay';

export type TrainingStatusBannerProps = {
  status?: string;
  progress?: number;
  errorMessage?: string | null;
  taskName?: string;
  lastUpdatedAt?: string;
  pollIntervalMs?: number;
  metrics?: Record<string, unknown>;
  style?: React.CSSProperties;
};

const TrainingStatusBanner: React.FC<TrainingStatusBannerProps> = ({
  status,
  progress,
  errorMessage,
  taskName,
  lastUpdatedAt,
  pollIntervalMs,
  metrics,
  style,
}) => {
  if (!status) return null;

  const percent = normalizeTrainingProgress(progress);
  const progressStatus = getTrainingProgressStatus(status);
  const statusLabel = getTrainingStatusText(status);
  const successSummary = buildTrainingSuccessSummary(metrics);
  const polling = isActiveTaskStatus(status);

  const progressBlock = (
    <div style={{ marginTop: 12 }}>
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          marginBottom: 8,
          flexWrap: 'wrap',
          gap: 8,
        }}
      >
        <Typography.Text strong style={{ fontSize: 15 }}>
          训练进度 {percent}%
        </Typography.Text>
        {polling && pollIntervalMs && (
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            <SyncOutlined spin style={{ marginRight: 6 }} />每{' '}
            {pollIntervalMs / 1000}s 自动刷新
            {lastUpdatedAt ? ` · 更新于 ${lastUpdatedAt}` : ''}
          </Typography.Text>
        )}
      </div>
      <Progress
        percent={percent}
        status={progressStatus}
        strokeWidth={status === 'running' || status === 'queued' ? 14 : 12}
        showInfo={false}
      />
    </div>
  );

  if (status === 'success') {
    return (
      <Alert
        type="success"
        showIcon
        icon={<CheckCircleOutlined style={{ fontSize: 22 }} />}
        message={
          <Typography.Text strong style={{ fontSize: 18 }}>
            训练已成功完成
          </Typography.Text>
        }
        description={
          <Space direction="vertical" size={8} style={{ width: '100%' }}>
            {taskName && (
              <Typography.Text>
                任务：<strong>{taskName}</strong>
              </Typography.Text>
            )}
            {successSummary && (
              <Typography.Text type="success">{successSummary}</Typography.Text>
            )}
            {!successSummary && (
              <Typography.Text type="secondary">
                可在下方查看训练指标与产物文件。
              </Typography.Text>
            )}
            {progressBlock}
          </Space>
        }
        style={{ marginBottom: 16, ...style }}
      />
    );
  }

  if (status === 'failed') {
    return (
      <Alert
        type="error"
        showIcon
        icon={<CloseCircleOutlined style={{ fontSize: 22 }} />}
        message={
          <Typography.Text strong style={{ fontSize: 18 }}>
            训练失败
          </Typography.Text>
        }
        description={
          <Space direction="vertical" size={8} style={{ width: '100%' }}>
            {taskName && (
              <Typography.Text>
                任务：<strong>{taskName}</strong>
              </Typography.Text>
            )}
            <Typography.Paragraph
              style={{
                margin: 0,
                whiteSpace: 'pre-wrap',
                wordBreak: 'break-word',
                fontFamily: 'monospace',
                fontSize: 13,
              }}
            >
              {errorMessage?.trim() ||
                '后端未返回具体失败原因，请查看训练日志。'}
            </Typography.Paragraph>
            {progressBlock}
          </Space>
        }
        style={{ marginBottom: 16, ...style }}
      />
    );
  }

  if (status === 'stopped') {
    return (
      <Alert
        type="warning"
        showIcon
        icon={<PauseCircleOutlined style={{ fontSize: 22 }} />}
        message={
          <Typography.Text strong style={{ fontSize: 18 }}>
            训练已停止
          </Typography.Text>
        }
        description={progressBlock}
        style={{ marginBottom: 16, ...style }}
      />
    );
  }

  if (status === 'running') {
    return (
      <Alert
        type="info"
        showIcon
        icon={<LoadingOutlined style={{ fontSize: 22 }} spin />}
        message={
          <Typography.Text strong style={{ fontSize: 18 }}>
            {statusLabel}
          </Typography.Text>
        }
        description={
          <Space direction="vertical" size={8} style={{ width: '100%' }}>
            {taskName && (
              <Typography.Text type="secondary">{taskName}</Typography.Text>
            )}
            <Typography.Text type="secondary">
              训练任务正在执行，页面将自动刷新进度与指标。
            </Typography.Text>
            {progressBlock}
          </Space>
        }
        style={{ marginBottom: 16, ...style }}
      />
    );
  }

  if (status === 'scheduled') {
    return (
      <Alert
        type="info"
        showIcon
        icon={<SyncOutlined style={{ fontSize: 22 }} spin />}
        message={
          <Typography.Text strong style={{ fontSize: 18 }}>
            {statusLabel}
          </Typography.Text>
        }
        description={
          <Space direction="vertical" size={8} style={{ width: '100%' }}>
            <Typography.Text type="secondary">
              任务已分配至计算节点，正在准备启动。
            </Typography.Text>
            {progressBlock}
          </Space>
        }
        style={{ marginBottom: 16, ...style }}
      />
    );
  }

  if (status === 'queued') {
    return (
      <Alert
        type="warning"
        showIcon
        icon={<SyncOutlined style={{ fontSize: 22 }} spin />}
        message={
          <Typography.Text strong style={{ fontSize: 18 }}>
            {statusLabel}
          </Typography.Text>
        }
        description={
          <Space direction="vertical" size={8} style={{ width: '100%' }}>
            <Typography.Text type="secondary">
              任务已提交，等待调度资源后开始训练。
            </Typography.Text>
            {progressBlock}
          </Space>
        }
        style={{ marginBottom: 16, ...style }}
      />
    );
  }

  return (
    <Alert
      type="info"
      showIcon
      message={
        <Typography.Text strong style={{ fontSize: 16 }}>
          {statusLabel}
        </Typography.Text>
      }
      description={progressBlock}
      style={{ marginBottom: 16, ...style }}
    />
  );
};

export default TrainingStatusBanner;
