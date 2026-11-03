import { RedoOutlined, ReloadOutlined } from '@ant-design/icons';
import { Alert, Button, message, Progress, Space, Typography } from 'antd';
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  createOrOpenDatasetWorkspace,
  fetchMultimodalImportStatus,
  getDatasetWorkspace,
  type ImportRetryMode,
  MULTIMODAL_IMPORT_STATUS_LABEL,
  type MultimodalImportJob,
  type MultimodalImportStatus,
  retryMultimodalImport,
} from '@/services/platform';
import { formatUserErrorDetails, getApiErrorMessage } from '@/utils/apiError';
import { clearImportJobId } from '@/utils/importJobStorage';

const ACTIVE_IMPORT_STATUSES: MultimodalImportStatus[] = ['PENDING', 'RUNNING'];

type MultimodalImportBannerProps = {
  importJobId?: string | null;
  /** 用于失败后拉取 workspaceRevision（V2 重试必填） */
  datasetId?: string | null;
  workspaceId?: string | null;
  workspaceRevision?: number | null;
  initialStatus?: string | null;
  initialProgress?: number | null;
  initialErrorMessage?: string | null;
  onImportFinished?: () => void;
  onWorkspaceRevisionChange?: (revision: number) => void;
};

function pickRetryMode(
  job: MultimodalImportJob | null,
): ImportRetryMode | null {
  if (!job) return null;
  const modes = job.retryModes;
  if (modes?.includes('INCREMENTAL') && job.status === 'PARTIAL') {
    return 'INCREMENTAL';
  }
  if (modes?.includes('FULL') && job.status === 'FAILED') {
    return 'FULL';
  }
  if (job.status === 'PARTIAL') return 'INCREMENTAL';
  if (job.status === 'FAILED') return 'FULL';
  return null;
}

const MultimodalImportBanner: React.FC<MultimodalImportBannerProps> = ({
  importJobId,
  datasetId,
  workspaceId: workspaceIdProp,
  workspaceRevision: workspaceRevisionProp,
  initialStatus,
  initialProgress,
  initialErrorMessage,
  onImportFinished,
  onWorkspaceRevisionChange,
}) => {
  const [job, setJob] = useState<MultimodalImportJob | null>(null);
  const [loading, setLoading] = useState(false);
  const [retrying, setRetrying] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [localRevision, setLocalRevision] = useState<number | null>(
    workspaceRevisionProp ?? null,
  );

  useEffect(() => {
    if (typeof workspaceRevisionProp === 'number') {
      setLocalRevision(workspaceRevisionProp);
    }
  }, [workspaceRevisionProp]);

  const loadStatus = useCallback(
    async (silent = false) => {
      if (!importJobId) return;
      if (!silent) setLoading(true);
      setError(null);
      try {
        const res = await fetchMultimodalImportStatus(importJobId, {
          skipErrorHandler: true,
        });
        const data = res?.data;
        if (data) {
          setJob(data);
          if (typeof data.workspaceRevision === 'number') {
            setLocalRevision(data.workspaceRevision);
            onWorkspaceRevisionChange?.(data.workspaceRevision);
          }
        }
      } catch (e: unknown) {
        const msg = getApiErrorMessage(e, '');
        if (
          datasetId &&
          (/IMPORT_JOB_NOT_FOUND/i.test(msg) || /404/.test(msg))
        ) {
          clearImportJobId(datasetId);
        }
        if (!silent) {
          setError(getApiErrorMessage(e));
        }
      } finally {
        if (!silent) setLoading(false);
      }
    },
    [importJobId, onWorkspaceRevisionChange, datasetId],
  );

  useEffect(() => {
    if (!importJobId) {
      setJob(null);
      return;
    }
    loadStatus(false);
  }, [importJobId, loadStatus]);

  const status = (job?.status ?? initialStatus ?? undefined) as
    | MultimodalImportStatus
    | string
    | undefined;
  const progress = job?.progress ?? initialProgress ?? 0;
  const errorMessage =
    job?.errorMessage ??
    job?.userError?.errorMessage ??
    initialErrorMessage ??
    undefined;

  const resolveWorkspaceRevision = useCallback(async (): Promise<{
    revision: number;
    workspaceId?: string;
  }> => {
    if (typeof localRevision === 'number') {
      return {
        revision: localRevision,
        workspaceId: workspaceIdProp || job?.workspaceId || undefined,
      };
    }
    if (typeof job?.workspaceRevision === 'number') {
      return {
        revision: job.workspaceRevision,
        workspaceId: job.workspaceId || workspaceIdProp || undefined,
      };
    }
    const knownWsId = workspaceIdProp || job?.workspaceId || undefined;
    if (knownWsId) {
      const ws = await getDatasetWorkspace(knownWsId, {
        skipErrorHandler: true,
      });
      return { revision: ws.workspaceRevision, workspaceId: ws.workspaceId };
    }
    if (datasetId) {
      const ws = await createOrOpenDatasetWorkspace(datasetId, null, {
        skipErrorHandler: true,
      });
      return { revision: ws.workspaceRevision, workspaceId: ws.workspaceId };
    }
    throw new Error('缺少工作区 revision，无法重试导入。请刷新详情页后重试。');
  }, [localRevision, workspaceIdProp, job, datasetId]);

  const handleRetry = async () => {
    if (!importJobId) return;
    const mode = pickRetryMode(job);
    if (!mode) {
      message.warning('当前导入状态不可重试');
      return;
    }
    setRetrying(true);
    setError(null);
    try {
      let { revision } = await resolveWorkspaceRevision();
      try {
        const res = await retryMultimodalImport(
          importJobId,
          { mode, expectedWorkspaceRevision: revision },
          { skipErrorHandler: true },
        );
        if (res?.data) {
          setJob(res.data);
          if (typeof res.data.workspaceRevision === 'number') {
            setLocalRevision(res.data.workspaceRevision);
            onWorkspaceRevisionChange?.(res.data.workspaceRevision);
          }
        }
      } catch (firstError: unknown) {
        const msg = getApiErrorMessage(firstError, '');
        if (/WORKSPACE_REVISION_CONFLICT|revision/i.test(msg) && datasetId) {
          const refreshed = await resolveWorkspaceRevision();
          revision = refreshed.revision;
          // 冲突后再拉一次工作区
          if (refreshed.workspaceId) {
            const ws = await getDatasetWorkspace(refreshed.workspaceId, {
              skipErrorHandler: true,
            });
            revision = ws.workspaceRevision;
          } else if (datasetId) {
            const ws = await createOrOpenDatasetWorkspace(datasetId, null, {
              skipErrorHandler: true,
            });
            revision = ws.workspaceRevision;
          }
          const res = await retryMultimodalImport(
            importJobId,
            { mode, expectedWorkspaceRevision: revision },
            { skipErrorHandler: true },
          );
          if (res?.data) {
            setJob(res.data);
            if (typeof res.data.workspaceRevision === 'number') {
              setLocalRevision(res.data.workspaceRevision);
              onWorkspaceRevisionChange?.(res.data.workspaceRevision);
            }
          }
        } else {
          throw firstError;
        }
      }
      message.success(
        mode === 'INCREMENTAL'
          ? '已提交增量重试（仅失败样本），请稍候'
          : '已提交全量重试，请稍候',
      );
      await loadStatus(true);
    } catch (e: unknown) {
      setError(getApiErrorMessage(e));
    } finally {
      setRetrying(false);
    }
  };

  useEffect(() => {
    if (!importJobId || !status) return;
    if (!ACTIVE_IMPORT_STATUSES.includes(status as MultimodalImportStatus)) {
      if (status === 'SUCCESS') {
        if (datasetId) {
          clearImportJobId(datasetId);
        }
        onImportFinished?.();
      }
      return;
    }
    const timer = window.setInterval(() => {
      void loadStatus(true);
    }, 5000);
    return () => window.clearInterval(timer);
  }, [importJobId, status, loadStatus, onImportFinished, datasetId]);

  const retryMode = useMemo(() => pickRetryMode(job), [job]);
  const canRetry =
    !!retryMode &&
    (job?.retryable !== false || status === 'FAILED' || status === 'PARTIAL');

  if (!importJobId) {
    return null;
  }

  if (status === 'SUCCESS') {
    return (
      <Alert
        type="success"
        showIcon
        message="多模态数据导入已完成"
        description="版本已准备完成，可在下方浏览样本。"
        style={{ marginBottom: 16 }}
      />
    );
  }

  if (status === 'PARTIAL') {
    return (
      <Alert
        type="warning"
        showIcon
        message="多模态数据部分导入成功"
        description={
          <Space direction="vertical" style={{ width: '100%' }}>
            <span>
              {errorMessage ||
                '部分样本导入失败。成功样本保留在 DRAFT，该版本不可发布为 READY，请增量重试失败样本。'}
            </span>
            {(job?.importedSamples != null || job?.failedSamples != null) && (
              <Typography.Text type="secondary">
                成功 {job?.importedSamples ?? 0}
                {job?.totalSamples != null ? ` / ${job.totalSamples}` : ''}
                {job?.failedSamples != null
                  ? `，失败 ${job.failedSamples}`
                  : ''}
              </Typography.Text>
            )}
            <Space>
              {canRetry && (
                <Button
                  type="primary"
                  size="small"
                  icon={<RedoOutlined />}
                  loading={retrying}
                  onClick={() => void handleRetry()}
                >
                  增量重试失败样本
                </Button>
              )}
              <Button
                size="small"
                icon={<ReloadOutlined />}
                loading={loading}
                onClick={() => loadStatus(false)}
              >
                刷新
              </Button>
              {error && (
                <Typography.Text type="danger">{error}</Typography.Text>
              )}
            </Space>
          </Space>
        }
        style={{ marginBottom: 16 }}
      />
    );
  }

  if (status === 'FAILED') {
    const errorCode = job?.errorCode || job?.userError?.errorCode;
    const structuredDetails = formatUserErrorDetails(job?.userError?.details);
    const errorDetails =
      structuredDetails ||
      job?.errorDetailsJson ||
      (job?.userError?.details
        ? JSON.stringify(job.userError.details, null, 2)
        : null);
    return (
      <Alert
        type="error"
        showIcon
        message="多模态数据导入失败"
        description={
          <Space direction="vertical" style={{ width: '100%' }}>
            <span>
              {errorMessage ||
                '请检查 manifest / 目录结构与 zip 内容后重试（FULL）。'}
            </span>
            {(errorCode || errorDetails) && (
              <Typography.Paragraph
                copyable
                style={{ marginBottom: 0, whiteSpace: 'pre-wrap' }}
                type="secondary"
              >
                {[
                  errorCode ? `errorCode: ${errorCode}` : null,
                  errorDetails ? `details: ${errorDetails}` : null,
                ]
                  .filter(Boolean)
                  .join('\n')}
              </Typography.Paragraph>
            )}
            <Space>
              {canRetry && (
                <Button
                  type="primary"
                  size="small"
                  icon={<RedoOutlined />}
                  loading={retrying}
                  onClick={() => void handleRetry()}
                >
                  全量重试导入
                </Button>
              )}
              <Button
                size="small"
                icon={<ReloadOutlined />}
                loading={loading}
                onClick={() => loadStatus(false)}
              >
                刷新
              </Button>
              {error && (
                <Typography.Text type="danger">{error}</Typography.Text>
              )}
            </Space>
          </Space>
        }
        style={{ marginBottom: 16 }}
      />
    );
  }

  if (
    !status ||
    !ACTIVE_IMPORT_STATUSES.includes(status as MultimodalImportStatus)
  ) {
    return null;
  }

  return (
    <Alert
      type="info"
      showIcon
      message={
        <Space>
          <span>
            多模态数据导入中（
            {MULTIMODAL_IMPORT_STATUS_LABEL[status as MultimodalImportStatus] ??
              status}
            ）
          </span>
          <Button
            size="small"
            icon={<ReloadOutlined />}
            loading={loading}
            onClick={() => loadStatus(false)}
          >
            刷新
          </Button>
        </Space>
      }
      description={
        <Space direction="vertical" style={{ width: '100%' }}>
          <Progress percent={Math.min(100, Math.max(0, progress))} />
          {job?.totalSamples != null && (
            <Typography.Text type="secondary">
              已导入 {job.importedSamples ?? 0} / {job.totalSamples} 个样本
              {job.failedSamples != null && job.failedSamples > 0
                ? `（失败 ${job.failedSamples}）`
                : ''}
            </Typography.Text>
          )}
          {error && <Typography.Text type="danger">{error}</Typography.Text>}
        </Space>
      }
      style={{ marginBottom: 16 }}
    />
  );
};

export default MultimodalImportBanner;
