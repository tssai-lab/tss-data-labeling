import { CloudUploadOutlined, FileZipOutlined } from '@ant-design/icons';
import { Alert, Button, Progress, Space, Table, Tag, Typography } from 'antd';
import { useEffect, useRef, useState } from 'react';
import {
  ArchiveImportSession,
  archivePolicy,
  type ImportAdapter,
  type ImportState,
  validateArchive,
} from '@/services/archiveImport.mjs';
import type { Batch } from '@/services/labeling';
import styles from './index.less';

const labels: Record<string, string> = {
  IDLE: '待选择文件',
  HASHING: '计算文件校验值',
  UPLOADING: '上传中',
  PAUSED: '已暂停／待续传',
  VERIFYING: '校验并提交解析',
  PROCESSING: '后台解析中',
  READY: '解析完成',
  FAILED: '解析失败',
  ERROR: '操作失败',
};
export default function ArchiveImportPanel({
  scope,
  category,
  adapter,
  locked = false,
  onReady,
  nativeTasks = false,
}: {
  scope: string;
  category: string;
  nativeTasks?: boolean;
  adapter?: ImportAdapter;
  locked?: boolean;
  onReady?: (batch: Batch) => void;
}) {
  const engine = useRef<ArchiveImportSession | null>(null),
    mounted = useRef(false),
    picking = useRef(0);
  const [file, setFile] = useState<File>(),
    [selectionError, setSelectionError] = useState(''),
    [state, setState] = useState<ImportState>({
      stage: 'IDLE',
      hashProgress: 0,
      batch: null,
      error: '',
    });
  useEffect(() => {
    mounted.current = true;
    const session = new ArchiveImportSession({
      scope,
      adapter,
      storage: localStorage,
      onChange: (value) => {
        if (mounted.current) setState(value);
      },
    });
    engine.current = session;
    setState(session.state);
    void session.refresh();
    return () => {
      mounted.current = false;
      session.dispose();
      engine.current = null;
    };
  }, [scope, adapter]);
  useEffect(() => {
    if (state.batch?.status !== 'PROCESSING' || !adapter) return;
    const timer = setInterval(() => void engine.current?.refresh(), 1500);
    return () => clearInterval(timer);
  }, [state.batch?.status, adapter]);
  const working = ['HASHING', 'UPLOADING', 'VERIFYING'].includes(state.stage),
    batch = state.batch;
  useEffect(() => {
    const block = (e: BeforeUnloadEvent) => {
      if (working) {
        e.preventDefault();
        e.returnValue = '';
      }
    };
    window.addEventListener('beforeunload', block);
    return () => window.removeEventListener('beforeunload', block);
  }, [working]);
  return (
    <section className={styles.archiveImport} aria-label="ZIP 数据集导入">
      <div className={styles.archiveIntro}>
        <div className={styles.archiveIcon} aria-hidden="true">
          <FileZipOutlined />
        </div>
        <div>
          <Typography.Title level={5}>上传数据集</Typography.Title>
          <Typography.Text type="secondary">
            {nativeTasks
              ? '选择包含任务 JSON、CSV 或图片的 ZIP 文件。'
              : category === 'Computer Vision'
                ? '选择包含图片的 ZIP 文件。'
                : '选择时序数据 ZIP 文件。'}
            {adapter ? ' 支持暂停后继续上传。' : ' 当前仅检查 ZIP 文件格式。'}
          </Typography.Text>
        </div>
      </div>
      {!adapter && (
        <Alert
          type="info"
          message="当前项目尚未连接数据导入服务，暂不能上传 ZIP。"
          description="可选择文件检查文件头和大小，或展开下方小样本预览验证标注设置；本地预检不验证包内文件是否可解析。"
        />
      )}
      {locked && <Alert message="当前项目只读，无法导入数据。" />}
      {(selectionError || state.error) && (
        <Alert type="error" message={selectionError || state.error} />
      )}
      <label className={styles.archiveDropzone}>
        <input
          className={styles.archiveFileInput}
          aria-label="选择 ZIP 数据集"
          type="file"
          accept=".zip,application/zip"
          disabled={locked || state.busy}
          onChange={async (e) => {
            const selected = e.target.files?.[0];
            e.target.value = '';
            const version = ++picking.current;
            setFile(undefined);
            setSelectionError('');
            if (!selected) return;
            try {
              await validateArchive(selected);
              if (version === picking.current && mounted.current)
                setFile(selected);
            } catch (err) {
              if (version === picking.current && mounted.current)
                setSelectionError(
                  err instanceof Error ? err.message : String(err),
                );
            }
          }}
        />
        <CloudUploadOutlined className={styles.archiveUploadIcon} />
        <span className={styles.archiveDropTitle}>
          {file ? file.name : '点击选择 ZIP 文件'}
        </span>
        <span className={styles.archiveDropHint}>
          {file
            ? `${(file.size / 1024 / 1024).toFixed(2)} MiB · 已选择，尚未上传`
            : `ZIP 格式，最大 ${archivePolicy.maxArchiveBytes / 1024 / 1024 / 1024} GiB`}
        </span>
        <span className={styles.archiveChooseButton}>选择文件</span>
      </label>
      {engine.current?.receipt && !file && (
        <Typography.Text className={styles.archiveResumeHint} type="secondary">
          检测到未完成的上传记录。选择原 ZIP 文件后可继续。
        </Typography.Text>
      )}
      <div className={styles.archiveControls}>
        <div className={styles.archiveStatus}>
          <span className={styles.archiveStatusDot} data-stage={state.stage} />
          <span>
            {state.stage === 'IDLE' && file
              ? '已选择文件，尚未上传'
              : labels[state.stage] || state.stage}
          </span>
        </div>
        <Button
          type="primary"
          icon={<CloudUploadOutlined />}
          disabled={
            !adapter ||
            !file ||
            state.busy ||
            locked ||
            ['PROCESSING', 'READY', 'FAILED'].includes(state.stage)
          }
          onClick={() => {
            if (file) void engine.current?.run(file);
          }}
        >
          {engine.current?.receipt ? '继续上传' : '开始上传'}
        </Button>
        <Button disabled={!working} onClick={() => engine.current?.pause()}>
          暂停上传
        </Button>
        <Button
          disabled={!adapter || state.busy || !engine.current?.receipt?.batchId}
          onClick={() => void engine.current?.refresh()}
        >
          刷新导入状态
        </Button>
        {batch?.status === 'FAILED' && (
          <Button
            disabled={
              !adapter || !batch.originalComplete || state.busy || locked
            }
            onClick={() => void engine.current?.retry()}
          >
            重新解析
          </Button>
        )}
        <Button
          disabled={state.busy || locked || !engine.current?.receipt}
          onClick={() => {
            if (
              window.confirm(
                '是否清除本浏览器的续传记录？已有导入批次不会删除，下次开始上传将申请新批次。',
              )
            ) {
              try {
                engine.current?.reset();
                setSelectionError('');
              } catch {
                setSelectionError('无法清除续传记录，请检查浏览器存储。');
              }
            }
          }}
        >
          清除续传记录
        </Button>
      </div>
      {state.stage === 'HASHING' && (
        <Progress percent={state.hashProgress} aria-label="文件校验进度" />
      )}
      {['UPLOADING', 'VERIFYING'].includes(state.stage) && (
        <Progress percent={state.uploadProgress || 0} aria-label="上传进度" />
      )}
      {batch && (
        <>
          <div className={styles.archiveBatchInfo}>
            已接收 {(batch.receivedBytes / 1024 / 1024).toFixed(2)} /{' '}
            {(batch.sizeBytes / 1024 / 1024).toFixed(2)} MiB · 已解析有效样本{' '}
            {batch.validSampleCount || 0} · 已记录错误 {batch.errorCount || 0}
          </div>
          {batch.status === 'PROCESSING' && (
            <Alert message="文件已上传，正在解析。解析完成前不能用于发布。" />
          )}
          {batch.status === 'READY' && (
            <Space>
              <Tag color="green">数据可用</Tag>
              <Button disabled={!onReady} onClick={() => onReady?.(batch)}>
                查看解析样本
              </Button>
            </Space>
          )}
          {batch.status === 'FAILED' && !batch.originalComplete && (
            <Alert message="此批次已失败且原包未完整接收，不能重新解析。请清除续传记录后重新上传。" />
          )}
          {!!batch.errors?.length && (
            <Table
              size="small"
              rowKey={(_, i) => String(i)}
              dataSource={batch.errors || []}
              pagination={{ pageSize: 5 }}
              locale={{ emptyText: '暂无解析错误' }}
              columns={[
                { title: '文件', dataIndex: 'sourcePath' },
                { title: '错误原因', dataIndex: 'message' },
                { title: '错误代码', dataIndex: 'code' },
              ]}
            />
          )}
        </>
      )}
    </section>
  );
}
