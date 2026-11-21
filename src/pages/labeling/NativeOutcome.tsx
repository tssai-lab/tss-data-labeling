import {
  Alert,
  Button,
  Card,
  Checkbox,
  Input,
  Modal,
  Select,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd';
import { useEffect, useState } from 'react';
import {
  describeLabelingError,
  labelingBlob,
  labelingJson,
  operationKey,
  post,
} from '@/services/labeling';
import {
  collectPages,
  type NativeProject,
  type NativeSpec,
} from '@/services/nativeLabeling';
import { formatDisplayDateTime } from '@/utils/formatDateTime';
import OutcomeDetails from './OutcomeDetails';
import { isLegacyProjectSpec } from '@/services/projectCompatibility.mjs';
import { fileSizeName, sampleName } from './resultDisplay';
import styles from './outcome.less';

function displaySnapshotName(snapshot: {
  name?: string;
  createdAt?: string;
  finalSnapshotId?: string;
}): string {
  const name = snapshot.name?.trim();
  if (name && name !== '确认成果') return name;
  const createdAt = formatDisplayDateTime(snapshot.createdAt);
  return `成果版本 · ${createdAt !== '-' ? createdAt : snapshot.finalSnapshotId?.slice(0, 8) || '未命名'}`;
}

export default function NativeOutcome({
  project,
  version,
  reviews,
  snapshots,
  onVersionChange,
  run,
  refresh,
  busy,
}: {
  project: NativeProject;
  version?: NativeSpec;
  reviews: any[];
  snapshots: any[];
  onVersionChange: (id: string) => void;
  run: (action: () => Promise<void>) => Promise<void>;
  refresh: () => void;
  busy: boolean;
}) {
  const [name, setName] = useState(''),
    [renameId, setRenameId] = useState(''),
    [renameName, setRenameName] = useState(''),
    [snapshotId, setSnapshotId] = useState(''),
    [formats, setFormats] = useState<any[]>([]),
    [format, setFormat] = useState('JSON'),
    [includeOriginals, setIncludeOriginals] = useState(false),
    [jobs, setJobs] = useState<any[]>([]),
    [reload, setReload] = useState(0),
    [error, setError] = useState('');
  const orderedSnapshots = [...snapshots].sort((a, b) =>
    String(b.createdAt || '').localeCompare(String(a.createdAt || '')) ||
    String(b.finalSnapshotId).localeCompare(String(a.finalSnapshotId)),
  );
  const selected = snapshotId || orderedSnapshots[0]?.finalSnapshotId;
  const selectedSnapshot = snapshots.find((snapshot) => snapshot.finalSnapshotId === selected);
  const selectedSpec = project.specVersions.find((spec) => spec.specVersionId === selectedSnapshot?.specVersionId);
  const pluginExport = selectedSpec ? isLegacyProjectSpec(selectedSpec, project.taskType)
    : project.specVersions.length > 0 && project.specVersions.every((spec) => isLegacyProjectSpec(spec, project.taskType));
  const [outcomeSelection, setOutcomeSelection] = useState<React.Key[]>([]);
  const accepted = reviews
    .filter((row) => row.status === 'ACCEPTED')
    .map((row) => ({ ...row, key: `${row.sampleId}:${row.specVersionId}` }));
  const confirmed = reviews.filter(
    (review) =>
      review.specVersionId === version?.specVersionId &&
      review.status === 'ACCEPTED',
  );
  const pending = jobs.some((job) => job.status === 'QUEUED');
  useEffect(() => {
    if (!selected) {
      setFormats([]);
      setJobs([]);
      return;
    }
    const abort = new AbortController();
    setError('');
    void Promise.all([
      labelingJson<any>(`/final-snapshots/${selected}/${pluginExport ? 'plugin-export-formats' : 'export-formats'}`, {
        signal: abort.signal,
      }),
      collectPages<any>(pluginExport ? `/projects/${project.projectId}/exports?snapshotId=${selected}` : `/final-snapshots/${selected}/exports`, abort.signal),
    ])
      .then(([value, list]) => {
        if (!abort.signal.aborted) {
          setFormats(value.formats);
          setFormat((current) =>
            value.formats.some(
              (item: any) => item.name === current && item.enabled,
            )
              ? current
              : value.formats.find((item: any) => item.enabled)?.name || '',
          );
          setJobs(list.map((job) => pluginExport && job.status === 'SUCCEEDED' ? { ...job, status: 'READY' } : job));
        }
      })
      .catch((e) => {
        if (!abort.signal.aborted) setError(describeLabelingError(e));
      });
    return () => abort.abort();
  }, [selected, reload, pluginExport, project.projectId]);
  useEffect(() => {
    if (!pending) return;
    const timer = setInterval(() => setReload((value) => value + 1), 2000);
    return () => clearInterval(timer);
  }, [pending]);
  return (
    <div className={styles.workspace}>
      <div className={styles.columns}>
      <Card className={styles.panel} title="固定成果版本">
        <Typography.Paragraph>
          选择标注规范版本，保存该版本中已确认的样本。相同样本和标注内容只保存一份成果，已有成果可直接改名。
        </Typography.Paragraph>
        <label className={styles.nameField}>
          <span>标注规范版本</span>
          <Select
            aria-label="选择标注规范版本"
            className={styles.specSelect}
            value={version?.specVersionId}
            onChange={onVersionChange}
            options={project.specVersions.map((spec) => ({
              value: spec.specVersionId,
              label: `第 ${spec.versionNo} 版 · ${reviews.filter((review) => review.specVersionId === spec.specVersionId && review.status === 'ACCEPTED').length} 个已确认样本`,
            }))}
          />
        </label>
        <Space wrap className={`${styles.actionRow} ${styles.saveRow}`}>
          <label className={styles.nameField}>
            <span>成果版本名称</span>
            <Input
              className={styles.snapshotNameInput}
              placeholder="例如：第一批质检结果"
              value={name}
              maxLength={120}
              onChange={(e) => setName(e.target.value)}
            />
          </label>
          <Button
            type="primary"
            disabled={
              project.status === 'COMPLETED' ||
              !confirmed.length ||
              !version ||
              !name.trim()
            }
            loading={busy}
            onClick={() =>
              void run(async () => {
                const snapshot = await post<any>(
                  `/projects/${project.projectId}/final-snapshots`,
                  {
                    name,
                    specVersionId: version!.specVersionId,
                    sampleIds: confirmed.map((row) => row.sampleId),
                  },
                  operationKey(),
                );
                setSnapshotId(snapshot.finalSnapshotId);
                refresh();
              })
            }
          >
            保存成果版本
          </Button>
        </Space>
        <p className={styles.fieldHelp}>结果内容相同的成果不能重复保存。如需调整名称，请使用列表中的“改名”。</p>
        <details style={{ marginTop: 16 }}>
          <summary>汇总不同规范的成果</summary>
          <Typography.Paragraph type="secondary">
            每个样本选择一份已确认结果。导出时按规范分组，分别保留标签和配置。
          </Typography.Paragraph>
          <Table<any>
            rowKey="key"
            size="small"
            dataSource={accepted}
            rowSelection={{
              selectedRowKeys: outcomeSelection,
              onChange: setOutcomeSelection,
              hideSelectAll: true,
              getCheckboxProps: (row) => ({
                disabled:
                  project.status === 'COMPLETED' ||
                  accepted.some(
                    (other) =>
                      other.sampleId === row.sampleId &&
                      other.key !== row.key &&
                      outcomeSelection.includes(other.key),
                  ),
              }),
            }}
            columns={[
              {
                title: '样本',
                render: (_, row) => (
                  <span title={row.sampleId}>{sampleName(row)}</span>
                ),
                ellipsis: true,
              },
              {
                title: '规范',
                render: (_, row) =>
                  `第 ${project.specVersions.find((spec) => spec.specVersionId === row.specVersionId)?.versionNo || '—'} 版`,
              },
            ]}
          />
          <Button
            disabled={
              project.status === 'COMPLETED' ||
              !outcomeSelection.length ||
              !name.trim()
            }
            loading={busy}
            onClick={() =>
              void run(async () => {
                const snapshot = await post<any>(
                  `/projects/${project.projectId}/final-snapshots`,
                  {
                    name,
                    selections: accepted
                      .filter((row) => outcomeSelection.includes(row.key))
                      .map((row) => ({
                        sampleId: row.sampleId,
                        specVersionId: row.specVersionId,
                      })),
                  },
                  operationKey(),
                );
                setSnapshotId(snapshot.finalSnapshotId);
                setOutcomeSelection([]);
                refresh();
              })
            }
          >
            保存汇总成果（{outcomeSelection.length} 个样本）
          </Button>
        </details>
        <Table
          rowKey="finalSnapshotId"
          size="small"
          dataSource={orderedSnapshots}
          scroll={{ x: 690 }}
          pagination={{ pageSize: 5, showSizeChanger: false }}
          style={{ marginTop: 16 }}
          rowClassName={(row) => row.finalSnapshotId === selected ? styles.selectedRow : ''}
          columns={[
            { title: '成果版本', render: (_, row) => displaySnapshotName(row) },
            { title: '样本数', dataIndex: 'sampleCount' },
            {
              title: '规范范围',
              render: (_, row) =>
                row.specVersionId
                  ? `第 ${project.specVersions.find((spec) => spec.specVersionId === row.specVersionId)?.versionNo || '—'} 版`
                  : '多个规范版本',
            },
            {
              title: '创建时间',
              dataIndex: 'createdAt',
              render: formatDisplayDateTime,
            },
            {
              title: '操作',
              render: (_, row) => (
                <Space size={6}>
                  <Button
                    type={row.finalSnapshotId === selected ? 'primary' : 'default'}
                    onClick={() => setSnapshotId(row.finalSnapshotId)}
                  >
                    {row.finalSnapshotId === selected ? '当前成果' : '选择成果'}
                  </Button>
                  <Button onClick={() => {
                    setRenameId(row.finalSnapshotId);
                    setRenameName(row.name === '确认成果' ? '' : row.name || '');
                  }}>改名</Button>
                  <Button
                    danger
                    disabled={project.status === 'COMPLETED'}
                    onClick={() =>
                      Modal.confirm({
                        title: '删除成果版本？',
                        content: '删除后无法恢复；已有导出记录的成果版本不能删除。',
                        okText: '删除',
                        cancelText: '取消',
                        okButtonProps: { danger: true },
                        onOk: () =>
                          run(async () => {
                            await labelingJson(`/final-snapshots/${row.finalSnapshotId}`, {
                              method: 'DELETE',
                            });
                            if (selected === row.finalSnapshotId) {
                              setSnapshotId(
                                orderedSnapshots.find(
                                  (snapshot) => snapshot.finalSnapshotId !== row.finalSnapshotId,
                                )?.finalSnapshotId || '',
                              );
                            }
                            refresh();
                          }),
                      })
                    }
                  >
                    删除
                  </Button>
                </Space>
              ),
            },
          ]}
        />
        <Modal
          title="修改成果版本名称"
          open={Boolean(renameId)}
          okText="保存名称"
          okButtonProps={{ disabled: !renameName.trim(), loading: busy }}
          onCancel={() => setRenameId('')}
          onOk={() => void run(async () => {
            await labelingJson(`/final-snapshots/${renameId}`, {
              method: 'PATCH',
              headers: { 'Content-Type': 'application/json', 'Idempotency-Key': operationKey() },
              body: JSON.stringify({ name: renameName.trim() }),
            });
            setRenameId('');
            refresh();
          })}
        >
          <Input
            aria-label="新的成果版本名称"
            value={renameName}
            maxLength={120}
            placeholder="输入新的成果版本名称"
            onChange={(event) => setRenameName(event.target.value)}
          />
        </Modal>
        <Button
          className={styles.completeButton}
          disabled={!selected || project.status === 'COMPLETED'}
          loading={busy}
          onClick={() =>
            Modal.confirm({
              title: '确认完成项目？',
              content:
                '完成后不能修改或发布任务。请先结束已发布任务、确认结果并保存最新成果。',
              onOk: () =>
                run(async () => {
                  await post(
                    `/projects/${project.projectId}/complete`,
                    {
                      finalSnapshotId: selected,
                      baseRevision: project.revision,
                    },
                    operationKey(),
                  );
                  refresh();
                }),
            })
          }
        >
          完成项目
        </Button>
      </Card>
      <Card className={styles.panel} title="生成交付文件">
        {error && <Alert type="error" message={error} />}
        {!selected ? (
          <Alert message="请先保存成果版本。" />
        ) : (
          <>
            <div className={styles.selectedSnapshot}>
              <span>当前导出范围</span>
              <strong>{selectedSnapshot ? displaySnapshotName(selectedSnapshot) : selected}</strong>
              <small>{selectedSnapshot?.sampleCount || 0} 个样本 · 固定成果版本</small>
            </div>
            <OutcomeDetails
              key={selected}
              snapshotId={selected}
              versions={project.specVersions}
            />
            <Space wrap className={styles.actionRow}>
              <Select
                aria-label="导出格式"
                style={{ minWidth: 280 }}
                value={format}
                onChange={setFormat}
                options={formats.map((item) => ({
                  value: item.name,
                  label:
                    (
                      {
                        'classification-csv-zip': '图片分类 CSV · ZIP',
                        'energy-jsonl-zip': '能耗标注 JSONL · ZIP',
                        JSON: '任务与标注 JSON',
                        JSON_MIN: '精简标注 JSON',
                        CSV: 'CSV 表格',
                        TSV: 'TSV 表格',
                        COCO: 'COCO 检测格式',
                        YOLO: 'YOLO 检测格式',
                      } as Record<string, string>
                    )[item.name] || item.title,
                  disabled: !item.enabled,
                }))}
              />
              <Checkbox
                checked={pluginExport ? includeOriginals : true}
                disabled={!pluginExport}
                onChange={(event) => setIncludeOriginals(event.target.checked)}
              >
                在导出包中包含原始数据集
              </Checkbox>
              <Button
                type="primary"
                loading={busy}
                disabled={
                  !formats.some((item) => item.name === format && item.enabled)
                }
                onClick={() =>
                  void run(async () => {
                    await post(
                      pluginExport ? `/projects/${project.projectId}/exports` : `/final-snapshots/${selected}/exports`,
                      pluginExport ? { format, includeOriginals, finalSnapshotIds: [selected] } : { format },
                      operationKey(),
                    );
                    setReload((value) => value + 1);
                  })
                }
              >
                生成导出文件
              </Button>
            </Space>
            <Typography.Paragraph type="secondary" style={{ marginTop: 10 }}>
              {pluginExport ? '勾选后，原始样本会一并放入 ZIP 的 originals 目录，并在清单中记录来源路径和文件摘要。' : '当前标注模板会自动将原始资源与标注结果一并打包；JSON 文件中的资源路径对应压缩包内的原始文件。'}
            </Typography.Paragraph>
            <Table
              rowKey="exportId"
              size="small"
              dataSource={jobs}
              pagination={{ pageSize: 5, showSizeChanger: false }}
              scroll={{ x: 650 }}
              columns={[
                {
                  title: '生成时间',
                  dataIndex: 'createdAt',
                  render: formatDisplayDateTime,
                },
                {
                  title: '文件大小',
                  dataIndex: 'fileSize',
                  render: fileSizeName,
                },
                { title: '格式', dataIndex: 'format' },
                {
                  title: '状态',
                  render: (_, row) => (
                    <Tag color={row.status === 'READY' ? 'success' : row.status === 'QUEUED' ? 'processing' : 'error'}>
                      {row.status === 'QUEUED'
                        ? '排队／生成中'
                        : row.status === 'EXPIRED'
                          ? '已过期'
                        : row.status === 'READY'
                          ? '可下载'
                          : `生成失败（${row.errorCode || '未知错误'}）`}
                    </Tag>
                  ),
                },
                {
                  title: '操作',
                  render: (_, row) => (
                    <Button
                      disabled={row.status !== 'READY' || busy}
                      onClick={() =>
                        void run(async () => {
                          const blob = await labelingBlob(
                              `/exports/${row.exportId}/${pluginExport ? 'download' : 'file'}`,
                            ),
                            url = URL.createObjectURL(blob);
                          const anchor = document.createElement('a');
                          anchor.href = url;
                          anchor.download = `annotations-${row.format}-${row.exportId}.zip`;
                          anchor.click();
                          window.setTimeout(
                            () => URL.revokeObjectURL(url),
                            1000,
                          );
                        })
                      }
                    >
                      下载
                    </Button>
                  ),
                },
              ]}
            />
          </>
        )}
      </Card>
      </div>
    </div>
  );
}
