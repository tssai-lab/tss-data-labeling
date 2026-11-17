import {
  Alert,
  Button,
  Card,
  Drawer,
  Form,
  Input,
  Select,
  Space,
  Table,
  Typography,
} from 'antd';
import { useEffect, useMemo, useRef, useState } from 'react';
import { imageImportAdapter } from '@/services/archiveImport.mjs';
import { labelingJson, operationKey, post } from '@/services/labeling';
import {
  categoryOf,
  type InputResource,
  type NativeProject,
  type NativeSample,
  type NativeSpec,
} from '@/services/nativeLabeling';
import ArchiveImportPanel from './ArchiveImportPanel';
import NativeEditor from './NativeEditor';

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
  const reviewState = (id: string) =>
    ['ACCEPTED', 'NEEDS_REVISION'].includes(reviewHeads.get(id) || '')
      ? reviewHeads.get(id)!
      : 'UNREVIEWED';
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
              { value: 'ALL', label: '全部审核状态' },
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
                  style={{
                    padding: 0,
                    height: 'auto',
                    whiteSpace: 'normal',
                    textAlign: 'left',
                  }}
                  disabled={!activeVersion}
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
            {
              title: '操作',
              render: (_, row) => (
                <Button
                  type="link"
                  disabled={!activeVersion}
                  onClick={() => setPreviewId(row.sampleId)}
                >
                  预览
                </Button>
              ),
              width: 90,
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
        {!!previewId && !!activeVersion && (
          <NativeEditor
            path={`/projects/${project.projectId}/samples/${previewId}/task?specVersionId=${activeVersion.specVersionId}`}
            sampleId={previewId}
            category={categoryOf(project)}
          />
        )}
      </Drawer>
      <Drawer
        title="导入与样本生成"
        open={importOpen}
        width="min(960px, calc(100vw - 32px))"
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
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          <Card>
            <ArchiveImportPanel
              scope={`native:${project.projectId}`}
              category={categoryOf(project)}
              adapter={adapter}
              nativeTasks
              locked={locked}
              onReady={refresh}
            />
          </Card>
          <Card title="已上传文件">
            <Typography.Paragraph>
              任务 JSON 直接导入。CSV
              可作为一份曲线样本，或按行生成样本；只有需要分组或调整字段时才使用“高级整理”。
            </Typography.Paragraph>
            <Table
              rowKey="resourceId"
              size="small"
              dataSource={resources}
              scroll={{ x: 700 }}
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
                            disabled={locked || busy}
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
                          disabled={locked || busy}
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
                          disabled={locked || busy}
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
            />
            {categoryOf(project) === 'Computer Vision' && (
              <Button
                disabled={locked || !selectedImages.length || busy}
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
                将所选图片生成任务
              </Button>
            )}
            {current && (
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
                      disabled={locked || !Object.keys(columns).length}
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
                      disabled={locked || !time || !channels.length || !field}
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
            )}
          </Card>
        </Space>
      </Drawer>
    </Space>
  );
}
