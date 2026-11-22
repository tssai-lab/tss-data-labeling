import {
  Alert,
  Button,
  Card,
  Checkbox,
  Drawer,
  Form,
  Image,
  Input,
  Select,
  Space,
  Spin,
  Table,
  Typography,
} from 'antd';
import { useEffect, useMemo, useRef, useState } from 'react';
import { imageImportAdapter } from '@/services/archiveImport.mjs';
import {
  describeLabelingError,
  labelingBlob,
  labelingJson,
  operationKey,
  post,
  sampleBlob,
} from '@/services/labeling';
import {
  categoryOf,
  collectPages,
  type InputResource,
  type NativeProject,
  type NativeSample,
  type NativeSpec,
} from '@/services/nativeLabeling';
import ArchiveImportPanel from './ArchiveImportPanel';
import styles from './index.less';
import ProjectSampleEditor from './ProjectSampleEditor';

type RawInputPreview = {
  task?: { id: string; data: Record<string, unknown> };
  resources?: {
    field: string;
    resourceId: string;
    url: string;
    mediaType: string;
  }[];
  sourcePath: string;
  displayName?: string;
  rawData?: unknown;
  source?: { sourceTaskId?: string };
};

function RawSamplePreview({
  projectId,
  sampleId,
  taskType,
}: {
  projectId: string;
  sampleId: string;
  taskType: string;
}) {
  const [preview, setPreview] = useState<RawInputPreview>();
  const [images, setImages] = useState<{ field: string; url: string }[]>([]);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const controller = new AbortController();
    const objectUrls: string[] = [];
    setLoading(true);
    setError('');
    setPreview(undefined);
    setImages([]);
    const isTimeSeries = taskType === 'TIMESERIES_ANNOTATION';
    const inputPath = isTimeSeries
      ? `/projects/${projectId}/samples/${sampleId}/data`
      : `/projects/${projectId}/samples/${sampleId}/native-input`;
    void labelingJson<RawInputPreview>(inputPath, { signal: controller.signal })
      .then(async (data) => {
        if (isTimeSeries) {
          setPreview({
            sourcePath: data.source?.sourceTaskId || sampleId,
            rawData: data,
          });
          return;
        }
        const imageResources = (data.resources || []).filter((resource) =>
          resource.mediaType.startsWith('image/'),
        );
        if (!imageResources.length && taskType === 'IMAGE_CLASSIFICATION') {
          const blob = await sampleBlob(projectId, sampleId, controller.signal);
          if (controller.signal.aborted) return;
          const url = URL.createObjectURL(blob);
          objectUrls.push(url);
          setImages([{ field: 'image', url }]);
          setPreview({ ...data, rawData: data.task?.data || {} });
          return;
        }
        const loadedImages = await Promise.all(
          imageResources.map(async (resource) => {
            const blob = await labelingBlob(
              resource.url.replace('/api/labeling/v1', ''),
              controller.signal,
            );
            const url = URL.createObjectURL(blob);
            objectUrls.push(url);
            return { field: resource.field, url };
          }),
        );
        if (controller.signal.aborted) return;
        setPreview({ ...data, rawData: data.task?.data || {} });
        setImages(loadedImages);
      })
      .catch((reason: unknown) => {
        if (!controller.signal.aborted) setError(describeLabelingError(reason));
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });

    return () => {
      controller.abort();
      objectUrls.forEach(URL.revokeObjectURL);
    };
  }, [projectId, sampleId, taskType]);

  if (error) return <Alert type="error" showIcon message={error} />;
  if (loading || !preview)
    return (
      <Spin tip="正在读取原始样本">
        <div style={{ minHeight: 120 }} />
      </Spin>
    );

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Alert
        type="info"
        showIcon
        message="这是原始数据预览。保存标注规范后，才能选择样本并发布标注任务。"
      />
      <Typography.Text type="secondary">
        {preview.displayName || preview.sourcePath}
      </Typography.Text>
      {images.map((image) => (
        <div key={`${image.field}:${image.url}`}>
          <Typography.Text strong>{image.field}</Typography.Text>
          <div style={{ marginTop: 8 }}>
            <Image
              src={image.url}
              alt={image.field}
              style={{ maxHeight: 520 }}
            />
          </div>
        </div>
      ))}
      <Typography.Title level={5}>原始样本数据</Typography.Title>
      <pre style={{ maxHeight: 520, overflow: 'auto', whiteSpace: 'pre-wrap' }}>
        {JSON.stringify(preview.rawData ?? preview.task?.data ?? {}, null, 2)}
      </pre>
      {(preview.resources || []).some(
        (resource) => !resource.mediaType.startsWith('image/'),
      ) && (
        <Typography.Text type="secondary">
          此样本还引用了{' '}
          {
            (preview.resources || []).filter(
              (resource) => !resource.mediaType.startsWith('image/'),
            ).length
          }{' '}
          个数据文件；字段和文件信息已包含在上方原始数据中。
        </Typography.Text>
      )}
    </Space>
  );
}

export default function NativeDataPreparation({
  project,
  samples,
  resources,
  refresh,
  suggest,
  run,
  busy,
  onGuard,
  version,
  reviews = [],
  onPublish,
}: {
  version?: NativeSpec;
  reviews?: { sampleId: string; specVersionId: string; status: string }[];
  onPublish?: (ids: string[]) => void;
  project: NativeProject;
  samples: NativeSample[];
  resources: InputResource[];
  refresh: () => void;
  suggest: (xml: string) => void;
  run: (action: () => Promise<void>) => Promise<void>;
  busy: boolean;
  onGuard?: (guard: () => boolean) => void;
}) {
  const adapter = useMemo(
    () => imageImportAdapter(project.projectId, labelingJson),
    [project.projectId],
  );
  const [resourceId, setResourceId] = useState(''),
    [time, setTime] = useState(''),
    [channels, setChannels] = useState<string[]>([]),
    [groups, setGroups] = useState<string[]>([]),
    [timeType, setTimeType] = useState('number'),
    [zone, setZone] = useState('Asia/Shanghai'),
    [field, setField] = useState('series'),
    [unit, setUnit] = useState(''),
    [timeUnit, setTimeUnit] = useState('s');
  const [importOpen, setImportOpen] = useState(!samples.length),
    [query, setQuery] = useState(''),
    [previewId, setPreviewId] = useState('');
  const [previewExpanded, setPreviewExpanded] = useState(true);
  const [selectedSamples, setSelectedSamples] = useState<React.Key[]>([]),
    [status, setStatus] = useState('ALL');
  const activeVersion = version || project.specVersions[0];
  const reviewHeads = useMemo(
    () =>
      new Map(
        reviews
          .filter((row) => row.specVersionId === activeVersion?.specVersionId)
          .map((row) => [row.sampleId, row.status]),
      ),
    [reviews, activeVersion?.specVersionId],
  );
  const [resultSampleIds, setResultSampleIds] = useState<Set<string> | null>(
    null,
  );
  const [resultStatusError, setResultStatusError] = useState(false);
  useEffect(() => {
    const controller = new AbortController();
    setResultStatusError(false);
    if (!activeVersion) {
      setResultSampleIds(new Set());
      return () => controller.abort();
    }
    setResultSampleIds(null);
    void collectPages<{ sampleId: string; hasResult: boolean }>(
      `/projects/${project.projectId}/results?specVersionId=${encodeURIComponent(activeVersion.specVersionId)}`,
      controller.signal,
    )
      .then((rows) => {
        if (!controller.signal.aborted)
          setResultSampleIds(
            new Set(
              rows.filter((row) => row.hasResult).map((row) => row.sampleId),
            ),
          );
      })
      .catch(() => {
        if (!controller.signal.aborted) setResultStatusError(true);
      });
    return () => controller.abort();
  }, [project.projectId, activeVersion?.specVersionId]);
  const reviewState = (
    id: string,
  ):
    | 'ACCEPTED'
    | 'NEEDS_REVISION'
    | 'UNREVIEWED'
    | 'NOT_ANNOTATED'
    | 'LOADING'
    | 'ERROR' => {
    const reviewed = reviewHeads.get(id);
    if (reviewed === 'ACCEPTED' || reviewed === 'NEEDS_REVISION')
      return reviewed;
    if (resultStatusError) return 'ERROR';
    if (resultSampleIds === null) return 'LOADING';
    return resultSampleIds.has(id) ? 'UNREVIEWED' : 'NOT_ANNOTATED';
  };
  const visibleSamples = samples.filter(
    (row) =>
      `${row.metadata.displayName || ''} ${row.sourcePath}`
        .toLowerCase()
        .includes(query.toLowerCase()) &&
      (status === 'ALL' || reviewState(row.sampleId) === status),
  );
  const previewIndex = visibleSamples.findIndex(
    (row) => row.sampleId === previewId,
  );
  useEffect(
    () => setSelectedSamples([]),
    [project.projectId, activeVersion?.specVersionId],
  );
  const previousCount = useRef(samples.length);
  useEffect(() => {
    if (previousCount.current === 0 && samples.length > 0) setImportOpen(false);
    previousCount.current = samples.length;
  }, [samples.length]);
  const [selectedImages, setSelectedImages] = useState<React.Key[]>([]);
  const resourceSnapshot = useMemo(
    () =>
      JSON.stringify(
        resources
          .map((row) => ({
            resourceId: row.resourceId,
            contentSha256: row.contentSha256,
            columns: row.metadata.columns || row.metadata.inputFields || [],
            rowCount: row.metadata.rowCount || 0,
            previewRows: row.metadata.previewRows || [],
          }))
          .sort((a, b) => a.resourceId.localeCompare(b.resourceId)),
      ),
    [resources],
  );
  const [confirmedResourceSnapshot, setConfirmedResourceSnapshot] =
    useState('');
  const parseConfirmed =
    Boolean(resourceSnapshot) && confirmedResourceSnapshot === resourceSnapshot;
  const totalParsedRows = resources.reduce(
    (total, row) => total + (row.metadata.rowCount || 0),
    0,
  );
  const parsedFields = Array.from(
    new Set(
      resources.flatMap(
        (row) => row.metadata.columns || row.metadata.inputFields || [],
      ),
    ),
  );
  const imageResourceCount = resources.filter((row) =>
    row.mediaType.startsWith('image/'),
  ).length;
  const [mode, setMode] = useState(
    categoryOf(project) === 'Computer Vision' ? 'rows' : 'timeseries',
  );
  const [columns, setColumns] = useState<Record<string, string>>({});
  const [resourceFields, setResourceFields] = useState<string[]>([]),
    [jsonFields, setJsonFields] = useState<string[]>([]),
    [delimiter, setDelimiter] = useState(',');
  const pending = useRef(false);
  pending.current = Boolean(resourceId);
  useEffect(() => {
    onGuard?.(
      () => !pending.current || window.confirm('字段映射尚未应用，是否离开？'),
    );
    const beforeUnload = (event: BeforeUnloadEvent) => {
      if (pending.current) {
        event.preventDefault();
        event.returnValue = '';
      }
    };
    window.addEventListener('beforeunload', beforeUnload);
    return () => {
      onGuard?.(() => true);
      window.removeEventListener('beforeunload', beforeUnload);
    };
  }, [onGuard]);
  const current = resources.find(
    (resource) => resource.resourceId === resourceId,
  );
  const options =
    current?.metadata.columns?.map((value) => ({ value, label: value })) || [];
  const locked = !['DRAFT', 'OPEN'].includes(project.status);
  return (
    <Space direction="vertical" style={{ width: '100%' }} size="middle">
      <Card
        title={`标注样本（${samples.length}）`}
        extra={
          <Space wrap>
            <Input.Search
              aria-label="搜索标注样本"
              placeholder="搜索样本"
              allowClear
              onChange={(event) => setQuery(event.target.value)}
              style={{ width: 220 }}
            />
            <Button
              type="primary"
              disabled={locked || busy}
              onClick={() => setImportOpen(true)}
            >
              导入数据
            </Button>
          </Space>
        }
      >
        <Space wrap style={{ marginBottom: 12 }}>
          <Select
            aria-label="筛选样本审核状态"
            value={status}
            onChange={setStatus}
            style={{ width: 150 }}
            options={[
              { value: 'ALL', label: '全部状态' },
              { value: 'NOT_ANNOTATED', label: '未标注' },
              { value: 'UNREVIEWED', label: '待核查' },
              { value: 'ACCEPTED', label: '已确认' },
              { value: 'NEEDS_REVISION', label: '需重标' },
            ]}
          />
          <Typography.Text type="secondary">
            已选择 {selectedSamples.length} 个样本
          </Typography.Text>
          {!activeVersion && !!samples.length && (
            <Typography.Text type="secondary">
              保存标注设置后，可预览和发布样本。
            </Typography.Text>
          )}
          {!!selectedSamples.length && (
            <Button type="link" onClick={() => setSelectedSamples([])}>
              清空选择
            </Button>
          )}
          {onPublish && (
            <Button
              disabled={
                locked ||
                busy ||
                !activeVersion ||
                !selectedSamples.length ||
                selectedSamples.length > 10000
              }
              onClick={() => onPublish(selectedSamples.map(String))}
            >
              发布所选样本
            </Button>
          )}
        </Space>
        <Table
          rowKey="sampleId"
          size="small"
          dataSource={visibleSamples}
          rowSelection={{
            selectedRowKeys: selectedSamples,
            onChange: setSelectedSamples,
            preserveSelectedRowKeys: true,
            getCheckboxProps: (row) => ({
              disabled:
                locked ||
                busy ||
                !activeVersion ||
                reviewState(row.sampleId) === 'ACCEPTED',
            }),
          }}
          pagination={{
            defaultPageSize: 20,
            showSizeChanger: true,
            showTotal: (total) => `共 ${total} 个样本`,
          }}
          columns={[
            {
              title: '样本',
              render: (_, row) => (
                <Button
                  type="link"
                  aria-label={`预览样本 ${row.metadata.displayName || row.sourcePath}`}
                  style={{
                    padding: 0,
                    height: 'auto',
                    whiteSpace: 'normal',
                    textAlign: 'left',
                  }}
                  onClick={() => setPreviewId(row.sampleId)}
                >
                  {row.metadata.displayName || row.sourcePath}
                </Button>
              ),
            },
            {
              title: '审核状态',
              width: 110,
              render: (_, row) =>
                ({
                  ACCEPTED: '已确认',
                  NEEDS_REVISION: '需重标',
                  UNREVIEWED: '待核查',
                  NOT_ANNOTATED: '未标注',
                  LOADING: '状态读取中',
                  ERROR: '状态读取失败',
                })[reviewState(row.sampleId)],
            },
            {
              title: '样本编号',
              render: (_, row) => (
                <Typography.Text
                  title={row.sampleId}
                  copyable={{ text: row.sampleId }}
                >
                  {row.sampleId.slice(0, 8)}
                </Typography.Text>
              ),
              width: 160,
            },
          ]}
          scroll={{ x: 700 }}
          locale={{
            emptyText: samples.length
              ? '没有符合筛选条件的样本。'
              : '尚未导入样本，点击“导入数据”开始。',
          }}
        />
      </Card>
      <Drawer
        title="样本预览"
        extra={
          <Space>
            <Button
              aria-label="预览上一条样本"
              disabled={previewIndex <= 0}
              onClick={() =>
                setPreviewId(visibleSamples[previewIndex - 1].sampleId)
              }
            >
              上一条
            </Button>
            <span>
              {previewIndex + 1} / {visibleSamples.length}
            </span>
            <Button
              aria-label="预览下一条样本"
              disabled={
                previewIndex < 0 || previewIndex >= visibleSamples.length - 1
              }
              onClick={() =>
                setPreviewId(visibleSamples[previewIndex + 1].sampleId)
              }
            >
              下一条
            </Button>
          </Space>
        }
        open={!!previewId}
        width="min(1320px, calc(100vw - 32px))"
        onClose={() => setPreviewId('')}
        destroyOnClose
      >
        {!!previewId &&
          (activeVersion ? (
            <ProjectSampleEditor
              project={project}
              version={activeVersion}
              sampleId={previewId}
            />
          ) : (
            <RawSamplePreview
              projectId={project.projectId}
              sampleId={previewId}
              taskType={project.taskType}
            />
          ))}
      </Drawer>
      <Drawer
        title="导入与样本生成"
        rootClassName={styles.importDrawer}
        open={importOpen}
        width="min(1080px, calc(100vw - 24px))"
        onClose={() => {
          if (
            !busy &&
            (!resourceId || window.confirm('字段映射尚未应用，是否放弃？'))
          ) {
            setResourceId('');
            setImportOpen(false);
          }
        }}
      >
        <div className={styles.importDrawerContent}>
          <section className={styles.importUploadSection}>
            <ArchiveImportPanel
              scope={`native:${project.projectId}`}
              category={categoryOf(project)}
              adapter={adapter}
              nativeTasks
              locked={locked}
              previewExpanded={previewExpanded}
              onReady={() => {
                if (!previewExpanded) refresh();
                setPreviewExpanded((open) => !open);
              }}
            />
          </section>
          <div id="parsed-files-preview" hidden={!previewExpanded}>
            <Card
              className={styles.importFilesCard}
              title="文件解析预览"
              extra={
                <Typography.Text type="secondary">
                  {resources.length} 个文件
                </Typography.Text>
              }
            >
              <section className={styles.importSummary} aria-label="解析统计">
                <div>
                  <strong>{imageResourceCount}</strong>
                  <span>图片文件</span>
                </div>
                <div>
                  <strong>{totalParsedRows}</strong>
                  <span>CSV 数据行</span>
                </div>
                <div>
                  <strong>{samples.length}</strong>
                  <span>标注样本</span>
                </div>
                <div className={styles.importFieldsSummary}>
                  <span>识别字段</span>
                  <strong>
                    {parsedFields.length
                      ? parsedFields.join(' · ')
                      : '等待文件解析'}
                  </strong>
                </div>
              </section>
              <Typography.Text className={styles.importHint} type="secondary">
                JSON 任务可直接导入；CSV
                可按文件或按行生成样本，需要调整字段时再使用“高级整理”。
              </Typography.Text>
              <Table
                className={styles.importResourceTable}
                rowKey="resourceId"
                size="small"
                dataSource={resources}
                scroll={{ x: 700 }}
                pagination={false}
                locale={{
                  emptyText: (
                    <div className={styles.importEmpty}>
                      <span
                        className={styles.importEmptyIcon}
                        aria-hidden="true"
                      >
                        ZIP
                      </span>
                      <strong>还没有解析文件</strong>
                      <span>上传 ZIP 后，文件和样例会显示在这里。</span>
                    </div>
                  ),
                }}
                rowSelection={{
                  selectedRowKeys: selectedImages,
                  onChange: setSelectedImages,
                  getCheckboxProps: (r) => ({
                    disabled: !r.mediaType.startsWith('image/') || locked,
                  }),
                }}
                columns={[
                  { title: '文件', dataIndex: 'sourcePath' },
                  { title: '类型', dataIndex: 'mediaType' },
                  {
                    title: '数据行数',
                    render: (_, r) => r.metadata.rowCount ?? '—',
                  },
                  {
                    title: '操作',
                    render: (_, r) =>
                      r.mediaType === 'text/csv' ? (
                        <Space wrap>
                          {categoryOf(project) === 'Time Series Analysis' && (
                            <Button
                              disabled={locked || !parseConfirmed || busy}
                              onClick={() =>
                                void run(async () => {
                                  const xml = new DOMParser().parseFromString(
                                    activeVersion?.editorConfig.labelConfig ||
                                      '<View/>',
                                    'application/xml',
                                  );
                                  const objects =
                                    xml.querySelectorAll('TimeSeries');
                                  if (objects.length > 1)
                                    throw Error(
                                      '当前规范包含多个数据对象，请使用任务 JSON 指定各字段。',
                                    );
                                  const field =
                                    objects[0]
                                      ?.getAttribute('value')
                                      ?.replace(/^\$/, '') || 'csv';
                                  await post(
                                    `/projects/${project.projectId}/imports/${r.batchId}/tasks`,
                                    {
                                      tasks: [
                                        {
                                          data: { [field]: r.sourcePath },
                                          meta: { displayName: r.sourcePath },
                                        },
                                      ],
                                    },
                                    operationKey(),
                                  );
                                  setImportOpen(false);
                                  refresh();
                                })
                              }
                            >
                              文件作为一个样本
                            </Button>
                          )}
                          <Button
                            disabled={locked || !parseConfirmed || busy}
                            onClick={() =>
                              void run(async () => {
                                const columns = r.metadata.columns || [];
                                if (!columns.length)
                                  throw Error('CSV 没有有效列。');
                                await post(
                                  `/projects/${project.projectId}/input-resources/${r.resourceId}/task-mapping`,
                                  {
                                    mode: 'rows',
                                    columnMapping: Object.fromEntries(
                                      columns.map((column) => [column, column]),
                                    ),
                                    delimiter: r.metadata.delimiter || ',',
                                  },
                                  operationKey(),
                                );
                                setImportOpen(false);
                                refresh();
                              })
                            }
                          >
                            每行一个样本
                          </Button>
                          <Button
                            aria-label="配置字段映射"
                            disabled={locked || !parseConfirmed || busy}
                            onClick={() => {
                              setResourceId(r.resourceId);
                              setTime('');
                              setChannels([]);
                              setGroups([]);
                              setDelimiter(r.metadata.delimiter || ',');
                              setColumns(
                                Object.fromEntries(
                                  (r.metadata.columns || []).map((name) => [
                                    name,
                                    name,
                                  ]),
                                ),
                              );
                              setResourceFields([]);
                              setJsonFields([]);
                            }}
                          >
                            高级整理
                          </Button>
                        </Space>
                      ) : null,
                  },
                ]}
                expandable={{
                  expandedRowRender: (resource) => {
                    const fields =
                      resource.metadata.columns ||
                      resource.metadata.inputFields ||
                      [];
                    const rows = resource.metadata.previewRows || [];
                    return (
                      <Space direction="vertical" style={{ width: '100%' }}>
                        <Typography.Text>
                          识别字段：
                          {fields.length ? fields.join('、') : '无字段信息'}
                        </Typography.Text>
                        {resource.mediaType.startsWith('image/') && (
                          <ResourceImagePreview
                            projectId={project.projectId}
                            resource={resource}
                          />
                        )}
                        {rows.length > 0 ? (
                          <div style={{ overflowX: 'auto' }}>
                            <Table
                              size="small"
                              pagination={false}
                              dataSource={rows.map((values, index) => ({
                                key: index,
                                values,
                              }))}
                              columns={fields.map((name, index) => ({
                                title: name,
                                render: (_, row) => row.values[index] ?? '',
                              }))}
                            />
                          </div>
                        ) : !resource.mediaType.startsWith('image/') ? (
                          <Typography.Text type="secondary">
                            当前文件没有可展示的行样例。
                          </Typography.Text>
                        ) : null}
                      </Space>
                    );
                  },
                  rowExpandable: (resource) =>
                    Boolean(
                      resource.metadata.columns?.length ||
                        resource.metadata.inputFields?.length ||
                        resource.metadata.previewRows?.length ||
                        resource.mediaType.startsWith('image/'),
                    ),
                }}
              />
            </Card>
            <div className={styles.importConfirmBar}>
              <div>
                <Checkbox
                  checked={parseConfirmed}
                  disabled={!resources.length || locked || busy}
                  onChange={(event) =>
                    setConfirmedResourceSnapshot(
                      event.target.checked ? resourceSnapshot : '',
                    )
                  }
                >
                  已核对文件、字段和样例
                </Checkbox>
                {!parseConfirmed && resources.length > 0 && (
                  <Typography.Text type="secondary">
                    新增或替换文件后，请重新确认解析结果。
                  </Typography.Text>
                )}
              </div>
              {categoryOf(project) === 'Computer Vision' && (
                <Button
                  type="primary"
                  disabled={
                    locked || !parseConfirmed || !selectedImages.length || busy
                  }
                  onClick={() =>
                    void run(async () => {
                      const batches = new Map<string, InputResource[]>();
                      for (const resource of resources.filter((r) =>
                        selectedImages.includes(r.resourceId),
                      ))
                        batches.set(resource.batchId, [
                          ...(batches.get(resource.batchId) || []),
                          resource,
                        ]);
                      for (const [batch, files] of batches)
                        await post(
                          `/projects/${project.projectId}/imports/${batch}/tasks`,
                          {
                            tasks: files
                              .sort((a, b) =>
                                a.resourceId.localeCompare(b.resourceId),
                              )
                              .map((resource) => ({
                                data: { image: resource.sourcePath },
                              })),
                          },
                          operationKey(),
                        );
                      setSelectedImages([]);
                      setImportOpen(false);
                      refresh();
                    })
                  }
                >
                  生成所选图片样本
                  {selectedImages.length ? `（${selectedImages.length}）` : ''}
                </Button>
              )}
            </div>
          </div>
          {current && (
            <Card className={styles.importMappingCard} title="整理 CSV 字段">
              <Form
                disabled={busy || locked}
                layout="vertical"
                style={{ maxWidth: 780, marginTop: 20 }}
              >
                <Typography.Title level={5}>
                  {current.sourcePath}
                </Typography.Title>
                <Form.Item label="生成方式">
                  <Select
                    aria-label="CSV 生成方式"
                    value={mode}
                    onChange={setMode}
                    options={[
                      {
                        value: 'timeseries',
                        label: '按时间和分组生成时序样本',
                      },
                      { value: 'rows', label: '每行生成一个样本' },
                    ]}
                  />
                </Form.Item>
                <Form.Item label="分隔符">
                  <Input
                    readOnly
                    value={
                      delimiter === ','
                        ? '逗号'
                        : delimiter === ';'
                          ? '分号'
                          : '制表符'
                    }
                  />
                </Form.Item>
                {mode === 'rows' ? (
                  <>
                    <Typography.Paragraph>
                      选择来源列并填写任务字段名。引用资源使用相对于此 CSV
                      的同包路径；JSON 字段可保存数组或对象。
                    </Typography.Paragraph>
                    <Table
                      size="small"
                      pagination={false}
                      rowKey="value"
                      dataSource={options}
                      rowSelection={{
                        selectedRowKeys: Object.keys(columns),
                        onChange: (keys) =>
                          setColumns(
                            Object.fromEntries(
                              keys.map((key) => [
                                String(key),
                                columns[String(key)] || String(key),
                              ]),
                            ),
                          ),
                        getCheckboxProps: () => ({ disabled: locked || busy }),
                      }}
                      columns={[
                        { title: '来源列', dataIndex: 'label' },
                        {
                          title: '任务字段',
                          render: (_, row) => (
                            <Input
                              aria-label={`任务字段 ${row.value}`}
                              disabled={
                                locked ||
                                busy ||
                                !Object.hasOwn(columns, row.value)
                              }
                              value={columns[row.value] || ''}
                              onChange={(e) =>
                                setColumns({
                                  ...columns,
                                  [row.value]: e.target.value,
                                })
                              }
                            />
                          ),
                        },
                      ]}
                    />
                    <Form.Item label="引用资源字段">
                      <Select
                        mode="multiple"
                        value={resourceFields}
                        onChange={setResourceFields}
                        options={Object.values(columns)
                          .filter(Boolean)
                          .map((value) => ({ value, label: value }))}
                      />
                    </Form.Item>
                    <Form.Item label="JSON 字段">
                      <Select
                        mode="multiple"
                        value={jsonFields}
                        onChange={setJsonFields}
                        options={Object.values(columns)
                          .filter(Boolean)
                          .map((value) => ({ value, label: value }))}
                      />
                    </Form.Item>
                    <Button
                      type="primary"
                      loading={busy}
                      disabled={
                        locked ||
                        !parseConfirmed ||
                        !Object.keys(columns).length
                      }
                      onClick={() =>
                        void run(async () => {
                          if (
                            Object.values(columns).some(
                              (value) => !value.trim(),
                            ) ||
                            new Set(Object.values(columns)).size !==
                              Object.keys(columns).length
                          )
                            throw Error('任务字段名不能为空或重复');
                          await post(
                            `/projects/${project.projectId}/input-resources/${current.resourceId}/task-mapping`,
                            {
                              mode: 'rows',
                              columnMapping: Object.fromEntries(
                                Object.entries(columns).map(
                                  ([source, target]) => [target, source],
                                ),
                              ),
                              resourceFields,
                              jsonFields,
                              delimiter,
                            },
                            operationKey(),
                          );
                          pending.current = false;
                          setResourceId('');
                          setImportOpen(false);
                          refresh();
                        })
                      }
                    >
                      生成样本
                    </Button>
                  </>
                ) : (
                  <>
                    <Form.Item
                      label="输入字段名"
                      extra="与标注设置中的数据字段对应，默认无需修改。"
                    >
                      <Input
                        value={field}
                        onChange={(e) => setField(e.target.value)}
                      />
                    </Form.Item>
                    <Form.Item label="时间列" required>
                      <Select
                        aria-label="时间列"
                        options={options}
                        value={time || undefined}
                        onChange={setTime}
                      />
                    </Form.Item>
                    <Form.Item label="数值通道" required>
                      <Select
                        aria-label="数值通道"
                        mode="multiple"
                        options={options.filter((o) => o.value !== time)}
                        value={channels}
                        onChange={setChannels}
                      />
                    </Form.Item>
                    <Form.Item label="样本分组列">
                      <Select
                        aria-label="样本分组列"
                        mode="multiple"
                        options={options}
                        value={groups}
                        onChange={setGroups}
                        placeholder="例如站点、日期；每组生成一个任务"
                      />
                    </Form.Item>
                    <Form.Item label="时间类型">
                      <Select
                        value={timeType}
                        onChange={setTimeType}
                        options={[
                          { value: 'number', label: '数值时间轴' },
                          { value: 'timestamp', label: '日期／时间' },
                        ]}
                      />
                    </Form.Item>
                    {timeType === 'timestamp' ? (
                      <Form.Item label="无时区日期／时间的所属时区">
                        <Select
                          value={zone}
                          onChange={setZone}
                          options={[
                            { value: 'Asia/Shanghai', label: '北京时间' },
                            { value: 'UTC', label: 'UTC' },
                          ]}
                        />
                      </Form.Item>
                    ) : (
                      <Form.Item label="时间单位">
                        <Input
                          value={timeUnit}
                          onChange={(e) => setTimeUnit(e.target.value)}
                        />
                      </Form.Item>
                    )}
                    <Form.Item label="通道单位（可不填）">
                      <Input
                        value={unit}
                        onChange={(e) => setUnit(e.target.value)}
                      />
                    </Form.Item>
                    <Alert
                      type="info"
                      showIcon
                      message="按分组和时间排序；空值保留为缺测，不自动补零或修改负值。"
                    />
                    <Button
                      type="primary"
                      loading={busy}
                      disabled={
                        locked ||
                        !parseConfirmed ||
                        !time ||
                        !channels.length ||
                        !field
                      }
                      style={{ marginTop: 14 }}
                      onClick={() =>
                        void run(async () => {
                          const result = await post<{
                            suggestedConfig: string;
                          }>(
                            `/projects/${project.projectId}/input-resources/${current.resourceId}/task-mapping`,
                            {
                              mode: 'timeseries',
                              delimiter,
                              timeColumn: time,
                              channelColumns: channels,
                              groupColumns: groups,
                              timeType,
                              timezone: zone,
                              timeUnit,
                              fieldName: field,
                              units: Object.fromEntries(
                                channels.map((name) => [name, unit]),
                              ),
                            },
                            operationKey(),
                          );
                          pending.current = false;
                          setResourceId('');
                          setImportOpen(false);
                          suggest(result.suggestedConfig);
                          refresh();
                        })
                      }
                    >
                      生成样本并设置标注
                    </Button>
                  </>
                )}
              </Form>
            </Card>
          )}
        </div>
      </Drawer>
    </Space>
  );
}

function ResourceImagePreview({
  projectId,
  resource,
}: {
  projectId: string;
  resource: InputResource;
}) {
  const [url, setUrl] = useState('');
  const [error, setError] = useState('');
  useEffect(() => {
    const abort = new AbortController();
    let objectUrl = '';
    setUrl('');
    setError('');
    void labelingBlob(
      `/projects/${projectId}/input-resources/${resource.resourceId}/file`,
      abort.signal,
    )
      .then((blob) => {
        if (abort.signal.aborted) return;
        objectUrl = URL.createObjectURL(blob);
        setUrl(objectUrl);
      })
      .catch(() => {
        if (!abort.signal.aborted) setError('图片预览加载失败');
      });
    return () => {
      abort.abort();
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [projectId, resource.resourceId]);
  if (error) return <Typography.Text type="danger">{error}</Typography.Text>;
  if (!url)
    return <Typography.Text type="secondary">图片加载中…</Typography.Text>;
  return (
    <Image
      src={url}
      alt={resource.metadata.displayName || resource.sourcePath}
      style={{ maxWidth: 320, maxHeight: 220, objectFit: 'contain' }}
    />
  );
}
