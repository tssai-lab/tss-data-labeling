import {
  Alert,
  Button,
  Card,
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

export default function NativeOutcome({
  project,
  version,
  reviews,
  snapshots,
  run,
  refresh,
  busy,
}: {
  project: NativeProject;
  version?: NativeSpec;
  reviews: any[];
  snapshots: any[];
  run: (action: () => Promise<void>) => Promise<void>;
  refresh: () => void;
  busy: boolean;
}) {
  const [name, setName] = useState('确认成果'),
    [snapshotId, setSnapshotId] = useState(''),
    [formats, setFormats] = useState<any[]>([]),
    [format, setFormat] = useState('JSON'),
    [jobs, setJobs] = useState<any[]>([]),
    [reload, setReload] = useState(0),
    [error, setError] = useState('');
  const selected = snapshotId || snapshots[0]?.finalSnapshotId;
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
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <Card title="确认成果与项目完成">
        <Typography.Paragraph>
          将已确认结果保存为成果版本，即可导出。完成项目后只读。
        </Typography.Paragraph>
        <p>
          当前选择规范：{version?.versionNo || '—'} · 已确认 {confirmed.length}{' '}
          个样本
        </p>
        <Space wrap>
          <Input
            aria-label="成果名称"
            value={name}
            maxLength={120}
            onChange={(e) => setName(e.target.value)}
          />
          <Button
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
          dataSource={snapshots}
          style={{ marginTop: 16 }}
          columns={[
            { title: '成果版本', dataIndex: 'name' },
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
                <Button onClick={() => setSnapshotId(row.finalSnapshotId)}>
                  选择此成果
                </Button>
              ),
            },
          ]}
        />
        <Button
          type="primary"
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
      <Card title="成果导出">
        {error && <Alert type="error" message={error} />}
        {!selected ? (
          <Alert message="请先保存成果版本。" />
        ) : (
          <>
            <p>
              所选成果：
              {snapshots.find(
                (snapshot) => snapshot.finalSnapshotId === selected,
              )?.name || selected}
            </p>
            <OutcomeDetails
              key={selected}
              snapshotId={selected}
              versions={project.specVersions}
            />
            <Space wrap>
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
              <Button
                loading={busy}
                disabled={
                  !formats.some((item) => item.name === format && item.enabled)
                }
                onClick={() =>
                  void run(async () => {
                    await post(
                      pluginExport ? `/projects/${project.projectId}/exports` : `/final-snapshots/${selected}/exports`,
                      pluginExport ? { format, includeOriginals: false, finalSnapshotIds: [selected] } : { format },
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
              {pluginExport ? '下载包包含插件结果文件和冻结规范、来源清单。' : '下载包包含所选格式、标注配置和引用资源。CSV／TSV 每行描述一个标注任务，区间等结构按对应格式保存。'}
            </Typography.Paragraph>
            <Table
              rowKey="exportId"
              size="small"
              dataSource={jobs}
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
                    <Tag>
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
    </Space>
  );
}
