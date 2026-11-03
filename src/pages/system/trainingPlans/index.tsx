import {
  CheckCircleOutlined,
  DownloadOutlined,
  EditOutlined,
  EyeOutlined,
  FileTextOutlined,
  ReloadOutlined,
  StopOutlined,
  UploadOutlined,
  WarningOutlined,
} from '@ant-design/icons';
import { PageContainer } from '@ant-design/pro-components';
import { history, Link, useAccess } from '@umijs/max';
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Divider,
  Drawer,
  Empty,
  Input,
  List,
  Modal,
  message,
  Space,
  Table,
  Tag,
  Tabs,
  Tooltip,
  Typography,
  Upload,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  disableTrainingPlan,
  downloadCvCpuTrainingPlanTemplate,
  fetchAdminTrainingPlan,
  fetchAdminTrainingPlans,
  previewTrainingPlanYaml,
  publishTrainingPlanYaml,
  type TrainingPlanDefinition,
  type TrainingPlanDetail,
  type TrainingPlanIssue,
  type TrainingPlanPreview,
  type TrainingPlanSummary,
} from '@/services/system/trainingPlans';
import {
  canPublishTrainingPlan,
  getTrainingPlanRequestError,
  validateTrainingPlanYamlFile,
} from './trainingPlanUi.mjs';

const { Dragger } = Upload;

function formatDateTime(value?: string | null): string {
  if (!value) return '-';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}

function sourceTag(source: string) {
  return source === 'ONLINE' ? (
    <Tag color="blue">在线发布</Tag>
  ) : (
    <Tag>平台内置</Tag>
  );
}

function statusTag(status: string) {
  return status === 'ACTIVE' ? (
    <Tag color="success">已启用</Tag>
  ) : (
    <Tag>已停用</Tag>
  );
}

function categoryTag(category?: string | null) {
  const color =
    category === 'CV' ? 'geekblue' : category === 'NLP' ? 'purple' : 'default';
  return <Tag color={color}>{category || '未分类'}</Tag>;
}

function joinValues(values?: Array<string | number> | null): string {
  return values?.length ? values.join('、') : '-';
}

function DefinitionSummary({
  definition,
}: {
  definition: TrainingPlanDefinition;
}) {
  const runtime = definition.runtimes?.[0];
  const profile = runtime?.resourceProfiles?.[0];
  return (
    <Descriptions bordered size="small" column={{ xs: 1, md: 2 }}>
      <Descriptions.Item label="方案 ID">
        <Typography.Text code>{definition.id}</Typography.Text>
      </Descriptions.Item>
      <Descriptions.Item label="版本">
        <Typography.Text code>{definition.version}</Typography.Text>
      </Descriptions.Item>
      <Descriptions.Item label="名称">
        {definition.displayName}
      </Descriptions.Item>
      <Descriptions.Item label="类别">
        {categoryTag(definition.category)}
      </Descriptions.Item>
      <Descriptions.Item label="Schema">
        <Typography.Text code>{definition.schemaVersion}</Typography.Text>
      </Descriptions.Item>
      <Descriptions.Item label="训练模式">
        {joinValues(definition.trainingModes)}
      </Descriptions.Item>
      <Descriptions.Item label="模型兼容规范" span={2}>
        {joinValues(definition.inputs?.model?.acceptedSpecIds)}
      </Descriptions.Item>
      <Descriptions.Item label="数据集兼容规范" span={2}>
        {joinValues(definition.inputs?.dataset?.acceptedSpecIds)}
      </Descriptions.Item>
      <Descriptions.Item label="运行镜像" span={2}>
        <Typography.Text
          code
          copyable={runtime?.image ? { text: runtime.image } : undefined}
        >
          {runtime?.image || '-'}
        </Typography.Text>
      </Descriptions.Item>
      <Descriptions.Item label="设备 / 资源档位">
        {runtime ? `${runtime.deviceType} / ${profile?.id || '-'}` : '-'}
      </Descriptions.Item>
      <Descriptions.Item label="CPU / 内存上限">
        {profile
          ? `${profile.cpuLimit || '-'} / ${profile.memoryLimit || '-'}`
          : '-'}
      </Descriptions.Item>
      <Descriptions.Item label="入口">
        {definition.execution
          ? `${definition.execution.interpreter || '-'} ${definition.execution.entrypoint || '-'}`
          : '-'}
      </Descriptions.Item>
      <Descriptions.Item label="最长运行">
        {definition.security?.maxRuntimeSeconds
          ? `${definition.security.maxRuntimeSeconds} 秒`
          : '-'}
      </Descriptions.Item>
      {definition.description ? (
        <Descriptions.Item label="说明" span={2}>
          {definition.description}
        </Descriptions.Item>
      ) : null}
    </Descriptions>
  );
}

function IssueList({
  title,
  issues,
  type,
}: {
  title: string;
  issues: TrainingPlanIssue[];
  type: 'error' | 'warning';
}) {
  if (!issues.length) return null;
  return (
    <Card size="small" title={`${title}（${issues.length}）`}>
      <List
        size="small"
        dataSource={issues}
        renderItem={(issue) => (
          <List.Item>
            <Space align="start">
              {type === 'error' ? (
                <WarningOutlined style={{ color: '#ff4d4f', marginTop: 4 }} />
              ) : (
                <WarningOutlined style={{ color: '#faad14', marginTop: 4 }} />
              )}
              <div>
                <Space wrap>
                  <Typography.Text code>{issue.code}</Typography.Text>
                  {issue.path ? <Tag>{issue.path}</Tag> : null}
                </Space>
                <div>{issue.message}</div>
              </div>
            </Space>
          </List.Item>
        )}
      />
    </Card>
  );
}

const TrainingPlansPage: React.FC = () => {
  const access = useAccess();
  const [plans, setPlans] = useState<TrainingPlanSummary[]>([]);
  const [loading, setLoading] = useState(false);
  const [selectedFile, setSelectedFile] = useState<File>();
  const [yamlInputMode, setYamlInputMode] = useState<'editor' | 'upload'>(
    'editor',
  );
  const [yamlText, setYamlText] = useState('');
  const [editorFileName, setEditorFileName] = useState(
    'training-plan-online.yaml',
  );
  const [editingKey, setEditingKey] = useState<string>();
  const [preview, setPreview] = useState<TrainingPlanPreview>();
  const [previewing, setPreviewing] = useState(false);
  const [publishing, setPublishing] = useState(false);
  const [downloading, setDownloading] = useState(false);
  const [disablingKey, setDisablingKey] = useState<string>();
  const [detail, setDetail] = useState<TrainingPlanDetail>();
  const [detailOpen, setDetailOpen] = useState(false);
  const [detailLoading, setDetailLoading] = useState(false);

  useEffect(() => {
    if (!access.canAccessTrainingPlans) history.replace('/403');
  }, [access.canAccessTrainingPlans]);

  const loadPlans = useCallback(async () => {
    setLoading(true);
    try {
      const result = await fetchAdminTrainingPlans({ skipErrorHandler: true });
      setPlans(Array.isArray(result) ? result : []);
    } catch (error: unknown) {
      message.error(getTrainingPlanRequestError(error, '训练方案列表加载失败'));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (access.canAccessTrainingPlans) void loadPlans();
  }, [access.canAccessTrainingPlans, loadPlans]);

  const editorFile = useMemo(
    () =>
      yamlText
        ? new File([yamlText], editorFileName, { type: 'application/yaml' })
        : undefined,
    [editorFileName, yamlText],
  );
  const activeFile = yamlInputMode === 'editor' ? editorFile : selectedFile;

  const resetSelectedFile = useCallback(() => {
    setSelectedFile(undefined);
    setYamlText('');
    setEditorFileName('training-plan-online.yaml');
    setPreview(undefined);
  }, []);

  const handlePreview = useCallback(async () => {
    const fileError = validateTrainingPlanYamlFile(activeFile);
    if (fileError || !activeFile) {
      message.warning(fileError || '请选择 YAML 文件');
      return;
    }
    setPreviewing(true);
    setPreview(undefined);
    try {
      const result = await previewTrainingPlanYaml(activeFile, {
        skipErrorHandler: true,
      });
      setPreview(result);
      if (result.publishable) {
        message.success('校验通过，可以确认发布');
      } else {
        message.warning('校验未通过，请按错误路径修改 YAML 后重新选择文件');
      }
    } catch (error: unknown) {
      message.error(getTrainingPlanRequestError(error, 'YAML 预览失败'));
    } finally {
      setPreviewing(false);
    }
  }, [activeFile]);

  const executePublish = useCallback(
    async (file: File, expectedSha256: string) => {
      setPublishing(true);
      try {
        const result = await publishTrainingPlanYaml(file, expectedSha256, {
          skipErrorHandler: true,
        });
        message.success(
          `训练方案 ${result.summary.planId}@${result.summary.planVersion} 已发布`,
        );
        resetSelectedFile();
        await loadPlans();
      } catch (error: unknown) {
        message.error(getTrainingPlanRequestError(error, '训练方案发布失败'));
        throw error;
      } finally {
        setPublishing(false);
      }
    },
    [loadPlans, resetSelectedFile],
  );

  const confirmPublish = useCallback(() => {
    if (
      !activeFile ||
      !preview ||
      !canPublishTrainingPlan(activeFile, preview)
    ) {
      message.warning('请先使用当前文件完成校验');
      return;
    }
    const file = activeFile;
    const sha256 = preview.sha256;
    Modal.confirm({
      title: `发布 ${preview.definition?.id || '-'}@${preview.definition?.version || '-'}？`,
      width: 620,
      content: (
        <Space direction="vertical" style={{ width: '100%' }}>
          <Alert
            type="warning"
            showIcon
            message="发布后该版本内容不可原地修改"
            description="如需变更，请提高 YAML 中的版本号后重新发布。新版本会成为活动版本，历史版本会保留。"
          />
          <Typography.Text>即将发布文件：{file.name}</Typography.Text>
          <Typography.Text code copyable={{ text: sha256 }}>
            SHA-256：{sha256}
          </Typography.Text>
        </Space>
      ),
      okText: '确认发布',
      cancelText: '取消',
      onOk: () => executePublish(file, sha256),
    });
  }, [activeFile, executePublish, preview]);

  const editPlanYaml = useCallback(async (plan: TrainingPlanSummary) => {
    const key = `${plan.planId}@${plan.planVersion}`;
    setEditingKey(key);
    try {
      const result = await fetchAdminTrainingPlan(
        plan.planId,
        plan.planVersion,
        { skipErrorHandler: true },
      );
      setYamlInputMode('editor');
      setYamlText(result.yamlContent || '');
      setEditorFileName(`${plan.planId}-${plan.planVersion}.yaml`);
      setSelectedFile(undefined);
      setPreview(undefined);
      message.info('已载入 YAML。修改 version 后先校验，再发布为新版本。');
      document
        .getElementById('training-plan-yaml-editor')
        ?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    } catch (error: unknown) {
      message.error(getTrainingPlanRequestError(error, 'YAML 加载失败'));
    } finally {
      setEditingKey(undefined);
    }
  }, []);

  const openDetail = useCallback(async (plan: TrainingPlanSummary) => {
    setDetail(undefined);
    setDetailOpen(true);
    setDetailLoading(true);
    try {
      const result = await fetchAdminTrainingPlan(
        plan.planId,
        plan.planVersion,
        {
          skipErrorHandler: true,
        },
      );
      setDetail(result);
    } catch (error: unknown) {
      message.error(getTrainingPlanRequestError(error, '训练方案详情加载失败'));
      setDetailOpen(false);
    } finally {
      setDetailLoading(false);
    }
  }, []);

  const confirmDisable = useCallback(
    (plan: TrainingPlanSummary) => {
      const key = `${plan.planId}@${plan.planVersion}`;
      Modal.confirm({
        title: `停用 ${key}？`,
        content:
          '停用后只能阻止新任务选择该方案，不会删除方案，也不会影响已创建任务、历史日志和训练结果。再次上传完全相同的 YAML 可恢复该版本。',
        okText: '确认停用',
        okButtonProps: { danger: true },
        cancelText: '取消',
        onOk: async () => {
          setDisablingKey(key);
          try {
            await disableTrainingPlan(plan.planId, plan.planVersion, {
              skipErrorHandler: true,
            });
            message.success(`${key} 已停用`);
            await loadPlans();
          } catch (error: unknown) {
            message.error(
              getTrainingPlanRequestError(error, '训练方案停用失败'),
            );
            throw error;
          } finally {
            setDisablingKey(undefined);
          }
        },
      });
    },
    [loadPlans],
  );

  const downloadTemplate = useCallback(async () => {
    setDownloading(true);
    try {
      await downloadCvCpuTrainingPlanTemplate({
        skipErrorHandler: true,
      });
      message.success('已交给浏览器下载 CV/CPU YAML 模板');
    } catch (error: unknown) {
      message.error(getTrainingPlanRequestError(error, '模板下载失败'));
    } finally {
      setDownloading(false);
    }
  }, []);

  const columns = useMemo<ColumnsType<TrainingPlanSummary>>(
    () => [
      {
        title: '方案',
        key: 'plan',
        render: (_, plan) => (
          <Space direction="vertical" size={0}>
            <Typography.Text strong>{plan.displayName}</Typography.Text>
            <Typography.Text type="secondary" copyable={{ text: plan.planId }}>
              {plan.planId}@{plan.planVersion}
            </Typography.Text>
          </Space>
        ),
      },
      {
        title: '类别',
        dataIndex: 'category',
        width: 90,
        render: categoryTag,
      },
      {
        title: '来源',
        dataIndex: 'source',
        width: 110,
        render: sourceTag,
      },
      {
        title: '状态',
        dataIndex: 'status',
        width: 100,
        render: statusTag,
      },
      {
        title: '内容摘要',
        dataIndex: 'sha256',
        width: 155,
        render: (sha256?: string | null) =>
          sha256 ? (
            <Tooltip title={sha256}>
              <Typography.Text code copyable={{ text: sha256 }}>
                {sha256.slice(0, 12)}…
              </Typography.Text>
            </Tooltip>
          ) : (
            '-'
          ),
      },
      {
        title: '最近发布时间',
        dataIndex: 'publishedAt',
        width: 180,
        render: formatDateTime,
      },
      {
        title: '操作',
        key: 'actions',
        width: 240,
        render: (_, plan) => {
          const key = `${plan.planId}@${plan.planVersion}`;
          return (
            <Space>
              <Button
                type="link"
                icon={<EyeOutlined />}
                onClick={() => void openDetail(plan)}
              >
                详情
              </Button>
              <Button
                type="link"
                icon={<EditOutlined />}
                loading={editingKey === key}
                onClick={() => void editPlanYaml(plan)}
              >
                编辑新版本
              </Button>
              {plan.source === 'ONLINE' && plan.status === 'ACTIVE' ? (
                <Button
                  type="link"
                  danger
                  icon={<StopOutlined />}
                  loading={disablingKey === key}
                  onClick={() => confirmDisable(plan)}
                >
                  停用
                </Button>
              ) : null}
            </Space>
          );
        },
      },
    ],
    [confirmDisable, disablingKey, editPlanYaml, editingKey, openDetail],
  );

  if (!access.canAccessTrainingPlans) return null;

  return (
    <PageContainer
      title="训练方案管理"
      subTitle="在线编写或上传训练方案 YAML（仅超级管理员）"
      extra={[
        <Button key="manual" icon={<FileTextOutlined />}>
          <Link to="/user-manual#training-plan-yaml">查看使用手册</Link>
        </Button>,
        <Button
          key="template"
          icon={<DownloadOutlined />}
          loading={downloading}
          onClick={() => void downloadTemplate()}
        >
          下载 CV/CPU 模板
        </Button>,
        <Button
          key="refresh"
          icon={<ReloadOutlined />}
          loading={loading}
          onClick={() => void loadPlans()}
        >
          刷新
        </Button>,
      ]}
    >
      <Alert
        showIcon
        type="info"
        message="发布前请先预览"
        description="编辑或上传 YAML 只会生成预览，确认发布后才会保存。发起训练时，平台会按方案筛选可用模型和数据集。"
        style={{ marginBottom: 16 }}
      />

      <Card
        id="training-plan-yaml-editor"
        title="编写或上传 YAML"
        style={{ marginBottom: 16 }}
      >
        <Tabs
          activeKey={yamlInputMode}
          onChange={(key) => {
            setYamlInputMode(key as 'editor' | 'upload');
            setPreview(undefined);
          }}
          items={[
            {
              key: 'editor',
              label: '在线编辑',
              children: (
                <Space direction="vertical" style={{ width: '100%' }}>
                  <Alert
                    type="info"
                    showIcon
                    message="已发布版本不可覆盖"
                    description="可以直接修改 YAML；基于已有方案编辑时，必须提高 version，再校验并发布为新版本。"
                  />
                  <Input.TextArea
                    value={yamlText}
                    rows={18}
                    spellCheck={false}
                    placeholder="粘贴训练方案 YAML，或在下方列表点击“编辑新版本”载入已有方案"
                    style={{ fontFamily: 'Consolas, Monaco, monospace' }}
                    disabled={previewing || publishing}
                    onChange={(event) => {
                      setYamlText(event.target.value);
                      setPreview(undefined);
                    }}
                  />
                  <Typography.Text type="secondary">
                    最大 256 KiB；内容变化后必须重新校验。
                  </Typography.Text>
                </Space>
              ),
            },
            {
              key: 'upload',
              label: '上传文件',
              children: (
                <Dragger
                  accept=".yaml,.yml,application/yaml,text/yaml"
                  maxCount={1}
                  multiple={false}
                  beforeUpload={(file) => {
                    const error = validateTrainingPlanYamlFile(file);
                    if (error) {
                      message.error(error);
                      return Upload.LIST_IGNORE;
                    }
                    setSelectedFile(file);
                    setPreview(undefined);
                    return false;
                  }}
                  onRemove={() => {
                    setSelectedFile(undefined);
                    setPreview(undefined);
                    return true;
                  }}
                  fileList={
                    selectedFile
                      ? [
                          {
                            uid: 'selected-training-plan-yaml',
                            name: selectedFile.name,
                            size: selectedFile.size,
                            status: 'done' as const,
                          },
                        ]
                      : []
                  }
                  disabled={previewing || publishing}
                >
                  <p className="ant-upload-drag-icon">
                    <UploadOutlined />
                  </p>
                  <p className="ant-upload-text">
                    点击或拖入一个 .yaml / .yml 文件
                  </p>
                  <p className="ant-upload-hint">最大 256 KiB</p>
                </Dragger>
              ),
            },
          ]}
        />
        <Space style={{ marginTop: 16 }} wrap>
          <Button
            type="primary"
            icon={<EyeOutlined />}
            disabled={!activeFile || publishing}
            loading={previewing}
            onClick={() => void handlePreview()}
          >
            预览并校验
          </Button>
          <Button
            type="primary"
            icon={<CheckCircleOutlined />}
            disabled={
              !canPublishTrainingPlan(activeFile, preview) || previewing
            }
            loading={publishing}
            onClick={confirmPublish}
          >
            确认发布
          </Button>
          {activeFile ? (
            <Typography.Text type="secondary">
              当前内容：{activeFile.name}（{activeFile.size} 字节）
            </Typography.Text>
          ) : null}
        </Space>
      </Card>

      {preview ? (
        <Card
          title="校验结果"
          style={{ marginBottom: 16 }}
          extra={
            preview.publishable ? (
              <Tag color="success">可以发布</Tag>
            ) : (
              <Tag color="error">不可发布</Tag>
            )
          }
        >
          <Space direction="vertical" size={16} style={{ width: '100%' }}>
            <Typography.Text code copyable={{ text: preview.sha256 }}>
              SHA-256：{preview.sha256 || '-'}
            </Typography.Text>
            {preview.currentActive ? (
              <Alert
                type="info"
                showIcon
                message={`当前活动版本：${preview.currentActive.planId}@${preview.currentActive.planVersion}`}
              />
            ) : null}
            {preview.definition ? (
              <DefinitionSummary definition={preview.definition} />
            ) : (
              <Empty description="YAML 尚未解析成有效方案" />
            )}
            <IssueList
              title="阻塞错误"
              issues={preview.issues || []}
              type="error"
            />
            <IssueList
              title="非阻塞警告"
              issues={preview.warnings || []}
              type="warning"
            />
            {preview.changes?.length ? (
              <Card
                size="small"
                title={`与当前版本的差异（${preview.changes.length}）`}
              >
                <Table
                  size="small"
                  pagination={false}
                  rowKey={(change) => `${change.section}:${change.changeType}`}
                  dataSource={preview.changes}
                  columns={[
                    { title: '区段', dataIndex: 'section' },
                    { title: '变化', dataIndex: 'changeType' },
                    {
                      title: '风险',
                      dataIndex: 'riskLevel',
                      render: (risk: string) => (
                        <Tag
                          color={
                            risk === 'HIGH'
                              ? 'error'
                              : risk === 'MEDIUM'
                                ? 'warning'
                                : 'default'
                          }
                        >
                          {risk}
                        </Tag>
                      ),
                    },
                  ]}
                />
              </Card>
            ) : null}
          </Space>
        </Card>
      ) : null}

      <Card title="全部方案版本">
        <Table<TrainingPlanSummary>
          rowKey={(plan) =>
            `${plan.source}:${plan.recordId ?? 'built-in'}:${plan.planId}:${plan.planVersion}`
          }
          loading={loading}
          columns={columns}
          dataSource={plans}
          pagination={{ pageSize: 10, showSizeChanger: false }}
          scroll={{ x: 1100 }}
        />
      </Card>

      <Drawer
        title={detail ? `${detail.summary.displayName} 详情` : '训练方案详情'}
        width={760}
        open={detailOpen}
        loading={detailLoading}
        destroyOnClose
        onClose={() => setDetailOpen(false)}
      >
        {detail ? (
          <>
            <Space wrap style={{ marginBottom: 16 }}>
              {sourceTag(detail.summary.source)}
              {statusTag(detail.summary.status)}
              {categoryTag(detail.summary.category)}
              <Typography.Text type="secondary">
                发布时间：{formatDateTime(detail.summary.publishedAt)}
              </Typography.Text>
            </Space>
            <DefinitionSummary definition={detail.definition} />
            <Divider>只读 YAML</Divider>
            <pre
              style={{
                margin: 0,
                padding: 16,
                overflow: 'auto',
                whiteSpace: 'pre-wrap',
                wordBreak: 'break-word',
                background: '#f6f8fa',
                border: '1px solid #e8e8e8',
                borderRadius: 8,
              }}
            >
              {detail.yamlContent}
            </pre>
          </>
        ) : null}
      </Drawer>
    </PageContainer>
  );
};

export default TrainingPlansPage;
