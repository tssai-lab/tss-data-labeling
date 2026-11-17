import { Alert, Button, Progress, Space, Table, Tag } from 'antd';
import { useEffect, useRef, useState } from 'react';
import {
  ArchiveImportSession,
  archivePolicy,
  type ImportAdapter,
  type ImportState,
  validateArchive,
} from '@/services/archiveImport.mjs';
import type { Batch } from '@/services/labeling';

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
    <section aria-label="ZIP 数据集导入">
      <h3>上传 ZIP 数据集</h3>
      <p>
        {nativeTasks
          ? '将任务 JSON 和它引用的 CSV、图片整理为 ZIP；原始数据也可先上传，再生成任务。'
          : category === 'Computer Vision'
            ? '将图片整理为 ZIP，支持单卷 ZIP64。'
            : '时序数据集使用 ZIP 导入；当前能耗 ZIP 解析流程尚未接入。'}{' '}
        {adapter
          ? '文件分片上传，支持暂停后续传。'
          : '当前可在本地检查 ZIP 文件头和大小。'}
      </p>
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
      <label>
        选择 ZIP
        <input
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
      </label>
      <p>
        {file
          ? `${file.name} · ${(file.size / 1024 / 1024).toFixed(2)} MiB（已选择，不代表已上传）`
          : engine.current?.receipt
            ? '存在续传记录，请重新选择原 ZIP 文件。'
            : `当前文件大小上限：${archivePolicy.maxArchiveBytes / 1024 / 1024 / 1024} GiB；实际上传以数据服务限制为准。`}
      </p>
      <Space wrap>
        <Tag>
          {state.stage === 'IDLE' && file
            ? '已选择文件，尚未上传'
            : labels[state.stage] || state.stage}
        </Tag>
        <Button
          type="primary"
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
      </Space>
      {state.stage === 'HASHING' && (
        <Progress percent={state.hashProgress} aria-label="文件校验进度" />
      )}
      {['UPLOADING', 'VERIFYING'].includes(state.stage) && (
        <Progress percent={state.uploadProgress || 0} aria-label="上传进度" />
      )}
      {batch && (
        <>
          <p>
            已接收 {(batch.receivedBytes / 1024 / 1024).toFixed(2)} /{' '}
            {(batch.sizeBytes / 1024 / 1024).toFixed(2)} MiB · 已解析有效样本{' '}
            {batch.validSampleCount || 0} · 已记录错误 {batch.errorCount || 0}
          </p>
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
        </>
      )}
    </section>
  );
}
