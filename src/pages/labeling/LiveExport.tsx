import { Alert, App, Button, Card, Input, Space } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { describeLabelingError } from '@/services/labeling';
import { type ExportJob, ownerApi } from '@/services/labelingOwner';
import StatusBadge from './StatusBadge';
import useOwnerAction from './useOwnerAction';

export default function LiveExport({
  projectId,
  snapshotIds,
  taskType,
  active = true,
  onBusyChange,
}: {
  projectId: string;
  snapshotIds: string[];
  taskType?: string;
  active?: boolean;
  onBusyChange?: (busy: boolean) => void;
}) {
  const { modal } = App.useApp(),
    action = useOwnerAction(onBusyChange);
  const [job, setJob] = useState<ExportJob>();
  const [known, setKnown] = useState(''),
    [error, setError] = useState('');
  const [loading, setLoading] = useState(false),
    [downloading, setDownloading] = useState(false);
  const version = useRef(0),
    lock = useRef(false);
  const format: 'energy-jsonl-zip' | 'classification-csv-zip' =
    taskType === 'TIMESERIES_ANNOTATION'
      ? 'energy-jsonl-zip'
      : 'classification-csv-zip';
  async function read(id: string) {
    if (lock.current) return;
    lock.current = true;
    const current = ++version.current;
    setLoading(true);
    setError('');
    try {
      const value = await ownerApi.exportStatus(id);
      if (current !== version.current) return;
      if (value.projectId !== projectId) throw new Error('导出不属于当前项目');
      setJob(value);
      setKnown(value.exportId);
    } catch (e) {
      if (current === version.current) setError(describeLabelingError(e));
    } finally {
      lock.current = false;
      if (current === version.current) setLoading(false);
    }
  }
  useEffect(
    () => () => {
      ++version.current;
    },
    [projectId],
  );
  useEffect(() => {
    if (!active || !job || job.status !== 'QUEUED' || error) return;
    const timer = setInterval(() => {
      if (document.visibilityState === 'visible') void read(job.exportId);
    }, 3000);
    return () => clearInterval(timer);
  }, [active, job?.exportId, job?.status, error]);
  async function create() {
    const ids = [...new Set(snapshotIds)];
    if (!ids.length || ids.length > 100) return;
    modal.confirm({
      title: `导出 ${ids.length} 份已冻结成果？`,
      content:
        '生成正式 ZIP，图片分类包含 CSV，能耗包含 JSONL。仅包含冻结成果，不包含原始文件；重叠冲突由后端拒绝。',
      onOk: async () => {
        const body = {
          finalSnapshotIds: ids,
          format,
          includeOriginals: false as const,
        };
        const value = await action.run(
          `export:${projectId}`,
          body,
          (key) => ownerApi.export(projectId, body, key),
          '导出任务已创建',
        );
        if (value) {
          ++version.current;
          setJob(value);
          setKnown(value.exportId);
          setError('');
        }
      },
    });
  }
  async function download() {
    if (job?.status !== 'SUCCEEDED' || downloading) return;
    setDownloading(true);
    setError('');
    try {
      const blob = await ownerApi.downloadExport(job.exportId);
      const url = URL.createObjectURL(blob),
        link = document.createElement('a');
      link.href = url;
      link.download = `labeling-${job.exportId}.zip`;
      link.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (e) {
      setError(describeLabelingError(e));
    } finally {
      setDownloading(false);
    }
  }
  return (
    <Card title="正式成果导出" style={{ marginTop: 16 }}>
      <p>
        从下方成果列表勾选快照后创建导出。服务器异步生成真实 ZIP，下载有效期为 7
        天。
      </p>
      {(error || action.error) && (
        <Alert type="error" message={error || action.error} />
      )}
      <Button
        type="primary"
        disabled={
          !snapshotIds.length ||
          snapshotIds.length > 100 ||
          action.busy ||
          loading
        }
        onClick={() => void create()}
      >
        导出勾选的 {snapshotIds.length} 份成果
      </Button>
      <details style={{ marginTop: 12 }}>
        <summary>恢复已有导出任务</summary>
        <Space wrap>
          <Input
            aria-label="导出任务编号"
            value={known}
            onChange={(e) => setKnown(e.target.value)}
            disabled={loading || action.busy}
            placeholder="从操作记录复制导出编号"
          />
          <Button
            disabled={!known.trim() || loading || action.busy}
            onClick={() => void read(known.trim())}
          >
            查询导出
          </Button>
        </Space>
      </details>
      {job && (
        <>
          <p>
            <StatusBadge status={job.status}>
              {
                { QUEUED: '生成中', SUCCEEDED: '已生成', FAILED: '失败' }[
                  job.status
                ]
              }
            </StatusBadge>
            {job.exportId}
          </p>
          {job.errorCode && <Alert type="error" message={job.errorCode} />}
          <p>
            到期时间：
            {job.expiresAt
              ? new Date(job.expiresAt).toLocaleString('zh-CN')
              : '生成后确定'}
          </p>
          {job.sha256 && (
            <p style={{ overflowWrap: 'anywhere' }}>
              ZIP SHA-256：{job.sha256}
            </p>
          )}
          <Space>
            <Button
              disabled={loading || action.busy}
              onClick={() => void read(job.exportId)}
            >
              刷新状态
            </Button>
            <Button
              disabled={
                job.status !== 'SUCCEEDED' ||
                downloading ||
                Date.parse(job.expiresAt) <= Date.now()
              }
              loading={downloading}
              onClick={() => void download()}
            >
              下载正式 ZIP
            </Button>
          </Space>
        </>
      )}
    </Card>
  );
}
