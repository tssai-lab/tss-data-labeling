import {
  CloudUploadOutlined,
  DownloadOutlined,
  FileSearchOutlined,
  PlayCircleOutlined,
  ReloadOutlined,
  StopOutlined,
  UploadOutlined,
} from '@ant-design/icons';
import type { ActionType, ProColumns } from '@ant-design/pro-components';
import { PageContainer, ProCard, ProTable } from '@ant-design/pro-components';
import { useSearchParams } from '@umijs/max';
import {
  Alert,
  Button,
  Collapse,
  Descriptions,
  Drawer,
  Form,
  Input,
  InputNumber,
  Modal,
  message,
  Popconfirm,
  Progress,
  Radio,
  Select,
  Space,
  Steps,
  Tag,
  Tour,
  Tooltip,
  Typography,
  Upload,
} from 'antd';
import type { UploadFile } from 'antd/es/upload/interface';
import React, { useEffect, useMemo, useRef, useState } from 'react';
import { usePageTour } from '@/components/Guide/usePageTour';
import InferenceLogPanel from '@/components/inference/InferenceLogPanel';
import InferenceOriginalInput from '@/components/inference/InferenceOriginalInput';
import InferenceResultVisual from '@/components/inference/ResultVisual';
import {
  createInferenceTask,
  deleteInferenceScript,
  deleteInferenceTask,
  downloadObjectWithBrowser,
  fetchDatasetList,
  fetchModelList,
  formatInferenceBytes,
  getInferenceTask,
  getInferenceTaskResult,
  type InferenceInputMode,
  type InferenceHardwareOption,
  type InferenceResourceProfile,
  type InferenceScriptVersion,
  type InferenceTask,
  listInferenceResourceProfiles,
  listInferenceHardwareOptions,
  listInferenceScripts,
  listInferenceTasks,
  objectNameFromMinioPath,
  retryInferenceTask,
  stopInferenceTask,
  uploadInferenceScript,
  uploadObject,
} from '@/services/platform';
import {
  defaultInferenceResourceProfileId,
  listUsableInferenceProfiles,
} from './resourceProfilePresentation.mjs';
import { requireSavedInferenceScriptVersion } from './inferenceScriptUploadResult.mjs';

/**
 * 推理工作台
 *
 * - 上方：选择模型 / 脚本 / 输入并创建任务
 * - 中部：任务列表（轮询状态）、脚本列表
 * - 右侧抽屉「推理结果」：摘要、下载、运行日志（xterm）、结构化结果与可视化
 *
 * 日志正文经任务 `logPath` 走文件下载；结果摘要用 `getInferenceTaskResult`。
 */

const { TextArea } = Input;

const STATUS_MAP: Record<string, { color: string; text: string }> = {
  pending: { color: 'default', text: '待提交' },
  queued: { color: 'warning', text: '调度中' },
  running: { color: 'processing', text: '推理中' },
  success: { color: 'success', text: '已完成' },
  failed: { color: 'error', text: '失败' },
  stopped: { color: 'default', text: '已停止' },
};

function optionLabel(
  options: { label: string; value: string }[],
  value?: string | null,
) {
  if (!value) return '-';
  const found = options.find((item) => item.value === value);
  return found ? found.label : value;
}

function statusTag(status?: string, detail?: string | null) {
  const item = STATUS_MAP[status || ''] || {
    color: 'default',
    text: status || '-',
  };
  const tag = <Tag color={item.color}>{item.text}</Tag>;
  return detail?.trim() ? <Tooltip title={detail}>{tag}</Tooltip> : tag;
}

function parseJson(text?: string) {
  const raw = text?.trim();
  if (!raw) return {};
  const value = JSON.parse(raw);
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error('JSON 参数必须是对象');
  }
  return value as Record<string, unknown>;
}

/** 由 outputPath 拼出 result.json 的 objectName，供「下载结果」 */
function minioPathToResultObject(outputPath?: string | null) {
  const objectName = objectNameFromMinioPath(outputPath);
  if (!objectName) return '';
  return `${objectName.replace(/\/?$/, '/')}result.json`;
}

function shortId(value?: string) {
  if (!value) return '';
  return value.length > 14 ? `...${value.slice(-8)}` : value;
}

const InferenceWorkbench: React.FC = () => {
  const [searchParams] = useSearchParams();
  const [taskForm] = Form.useForm();
  const selectedResourceProfileId = Form.useWatch(
    'resourceProfileId',
    taskForm,
  );
  const selectedHardwareTargetId = Form.useWatch(
    'hardwareTargetId',
    taskForm,
  );
  const [scriptForm] = Form.useForm();
  const actionRef = useRef<ActionType | undefined>(undefined);
  const prefillAppliedRef = useRef(false);

  const sourceModelVersionId = searchParams.get('modelVersionId') || '';
  const sourceTrainingId = searchParams.get('trainingId') || '';
  const sourceDatasetVersionId = searchParams.get('datasetVersionId') || '';
  const sourceTrainingProfile = searchParams.get('trainingProfile') || '';

  // 模型 / 数据集 / 脚本下拉选项（创建任务用）
  const [modelOptions, setModelOptions] = useState<any[]>([]);
  const [datasetOptions, setDatasetOptions] = useState<any[]>([]);
  const [scriptOptions, setScriptOptions] = useState<InferenceScriptVersion[]>(
    [],
  );
  const [resourceProfileOptions, setResourceProfileOptions] = useState<
    InferenceResourceProfile[]
  >([]);
  const [hardwareOptions, setHardwareOptions] = useState<
    InferenceHardwareOption[]
  >([]);
  const [createStep, setCreateStep] = useState(0);
  const [loadingAssets, setLoadingAssets] = useState(false);
  // 引导段 S4：推理工作台讲解
  const tourProps = usePageTour(4, { ready: !loadingAssets });
  const [creating, setCreating] = useState(false);
  const [retryingTaskId, setRetryingTaskId] = useState<string>();
  const [scriptUploading, setScriptUploading] = useState(false);
  const [scriptModalOpen, setScriptModalOpen] = useState(false);
  const [scriptFileList, setScriptFileList] = useState<UploadFile[]>([]);
  const [inputFileList, setInputFileList] = useState<UploadFile[]>([]);
  const [inputMode, setInputMode] =
    useState<InferenceInputMode>('SINGLE_OBJECT');
  const [selectedTask, setSelectedTask] = useState<InferenceTask>();
  const [drawerOpen, setDrawerOpen] = useState(false);

  const usableResourceProfiles = useMemo(
    () => listUsableInferenceProfiles(resourceProfileOptions),
    [resourceProfileOptions],
  );
  const selectedResourceProfile = usableResourceProfiles.find(
    (profile) => profile.id === selectedResourceProfileId,
  );
  const selectedHardwareOption = hardwareOptions.find(
    (option) => option.hardwareTargetId === selectedHardwareTargetId,
  );

  /** 打开结果抽屉，并拉取最新 result / logPath / outputPath */
  const openTaskResult = async (task: InferenceTask) => {
    setSelectedTask(task);
    setDrawerOpen(true);
    try {
      const res = await getInferenceTaskResult(task.id, {
        skipErrorHandler: true,
      });
      const latest = res?.data;
      if (!latest) return;
      setSelectedTask((prev) =>
        prev && prev.id === task.id ? { ...prev, ...latest } : prev,
      );
    } catch {
      // 列表中的 result 仍可展示
    }
  };

  // 抽屉打开且任务未终态时，定时刷新结果摘要（供进度与运行日志更新）
  useEffect(() => {
    if (!drawerOpen || !selectedTask?.id) return;
    const running = ['pending', 'queued', 'scheduled', 'running'].includes(
      selectedTask.status,
    );
    if (!running) return;

    const taskId = selectedTask.id;
    const timer = window.setInterval(() => {
      void getInferenceTaskResult(taskId, { skipErrorHandler: true })
        .then((res) => {
          const latest = res?.data;
          if (!latest) return;
          setSelectedTask((prev) =>
            prev && prev.id === taskId ? { ...prev, ...latest } : prev,
          );
        })
        .catch(() => {
          // 保持抽屉内已有数据
        });
    }, 3000);

    return () => window.clearInterval(timer);
  }, [drawerOpen, selectedTask?.id, selectedTask?.status]);

  const modelSelectOptions = useMemo(
    () =>
      modelOptions
        .filter((item) => item.id)
        .map((item) => ({
          label: [
            item.name || '未命名模型',
            item.version || item.id,
            item.fileName,
            shortId(item.id),
          ]
            .filter(Boolean)
            .join(' / '),
          value: item.id,
        })),
    [modelOptions],
  );

  const datasetSelectOptions = useMemo(
    () =>
      datasetOptions
        .filter(
          (item) =>
            item.versionId &&
            (!item.versionStatus || item.versionStatus === 'READY'),
        )
        .map((item) => ({
          label: `${item.name || '未命名数据集'} / ${item.version || item.versionId}`,
          value: item.versionId,
        })),
    [datasetOptions],
  );

  const scriptSelectOptions = useMemo(
    () =>
      scriptOptions
        .filter((item) => item.status !== 'DELETED')
        .map((item) => ({
          label: `${item.scriptName} / ${item.version} / ${item.entryFile}`,
          value: item.id,
        })),
    [scriptOptions],
  );

  const reloadAssets = async () => {
    setLoadingAssets(true);
    try {
      const [modelRes, datasetRes, scriptRes] = await Promise.all([
        fetchModelList({ pageSize: 200 }),
        fetchDatasetList({ pageSize: 200 }),
        listInferenceScripts({ skipErrorHandler: true }),
      ]);
      setModelOptions((modelRes as any)?.data ?? []);
      setDatasetOptions((datasetRes as any)?.data ?? []);
      setScriptOptions(scriptRes?.data ?? []);
    } catch (error: any) {
      message.error(error?.message || '资源加载失败');
    } finally {
      setLoadingAssets(false);
    }
    try {
      const [resourceProfileRes, hardwareOptionRes] = await Promise.all([
        listInferenceResourceProfiles({ skipErrorHandler: true }),
        listInferenceHardwareOptions({ skipErrorHandler: true }),
      ]);
      const nextProfiles = listUsableInferenceProfiles(
        resourceProfileRes?.data ?? [],
      );
      setResourceProfileOptions(nextProfiles);
      setHardwareOptions(hardwareOptionRes?.data ?? []);
      const currentProfileId = taskForm.getFieldValue('resourceProfileId');
      if (!nextProfiles.some((profile) => profile.id === currentProfileId)) {
        taskForm.setFieldValue(
          'resourceProfileId',
          defaultInferenceResourceProfileId(nextProfiles),
        );
      }
    } catch (error: any) {
      setResourceProfileOptions([]);
      setHardwareOptions([]);
      taskForm.setFieldValue('resourceProfileId', undefined);
      message.error(error?.message || '推理资源规格加载失败');
    }
  };

  useEffect(() => {
    reloadAssets();
  }, []);

  useEffect(() => {
    if (prefillAppliedRef.current || !sourceModelVersionId) return;
    if (loadingAssets || modelOptions.length === 0) return;
    const values: Record<string, unknown> = {
      modelVersionId: sourceModelVersionId,
      name: sourceTrainingId ? `推理-${sourceTrainingId}` : '训练模型推理',
    };
    if (sourceDatasetVersionId) {
      values.inputMode = 'DATASET_VERSION';
      values.datasetVersionId = sourceDatasetVersionId;
      values.paramsJson = JSON.stringify(
        { inputKind: 'dataset', split: 'test' },
        null,
        2,
      );
      setInputMode('DATASET_VERSION');
    }
    taskForm.setFieldsValue(values);
    prefillAppliedRef.current = true;
  }, [
    loadingAssets,
    modelOptions.length,
    sourceDatasetVersionId,
    sourceModelVersionId,
    sourceTrainingId,
    taskForm,
  ]);

  const handleUploadScript = async () => {
    const values = await scriptForm.validateFields();
    const file = scriptFileList[0]?.originFileObj as File | undefined;
    if (!file) {
      message.warning('请选择推理脚本 ZIP');
      return;
    }
    if (!file.name.toLowerCase().endsWith('.zip')) {
      message.warning('脚本文件必须是 ZIP');
      return;
    }
    if (values.paramsSchemaJson?.trim()) {
      parseJson(values.paramsSchemaJson);
    }

    setScriptUploading(true);
    try {
      const res = await uploadInferenceScript(
        {
          file,
          scriptName: values.scriptName.trim(),
          version: values.version?.trim() || 'v1',
          runtime: values.runtime || 'PYTHON3',
          entryFile: values.entryFile.trim(),
          paramsSchemaJson: values.paramsSchemaJson,
          remark: values.remark,
        },
        { skipErrorHandler: true },
      );
      const scriptVersionId = requireSavedInferenceScriptVersion(res);
      message.success('推理脚本已上传并保存到脚本列表');
      setScriptModalOpen(false);
      scriptForm.resetFields();
      setScriptFileList([]);
      await reloadAssets();
      taskForm.setFieldValue('scriptVersionId', scriptVersionId);
    } catch (error: any) {
      message.error(error?.message || '脚本上传失败');
    } finally {
      setScriptUploading(false);
    }
  };

  /** 创建推理任务；单文件模式会先 uploadObject 再提交 */
  const handleCreateTask = async () => {
    await taskForm.validateFields([
      'resourceProfileId',
      ...(selectedResourceProfile?.deviceType === 'NVIDIA_GPU'
        ? ['hardwareTargetId', 'gpuMemoryLimitMiB']
        : []),
    ]);
    const values = taskForm.getFieldsValue(true);
    if (
        !usableResourceProfiles.some(
        (profile) => profile.id === values.resourceProfileId,
      )
    ) {
      message.error('请选择后端当前启用的推理资源规格');
      return;
    }
    if (
      selectedResourceProfile?.deviceType === 'NVIDIA_GPU' &&
      !hardwareOptions.some(
        (option) => option.hardwareTargetId === values.hardwareTargetId,
      )
    ) {
      message.error('请选择后端当前检测到的一张物理 GPU');
      return;
    }
    setCreating(true);
    try {
      const params = parseJson(values.paramsJson);
      let inputObjectName: string | undefined;
      if (values.inputMode === 'SINGLE_OBJECT') {
        const file = inputFileList[0]?.originFileObj as File | undefined;
        if (!file) {
          throw new Error('请选择单文件输入');
        }
        const objectName = `inference-inputs/${Date.now()}-${file.name}`;
        const uploadRes = await uploadObject(file, objectName, {
          skipErrorHandler: true,
        });
        inputObjectName = uploadRes?.data?.objectName;
      }

      const res = await createInferenceTask(
        {
          name: values.name.trim(),
          modelVersionId: values.modelVersionId,
          scriptVersionId: values.scriptVersionId,
          inputMode: values.inputMode,
          datasetVersionId:
            values.inputMode === 'DATASET_VERSION'
              ? values.datasetVersionId
              : undefined,
          inputObjectName:
            values.inputMode === 'SINGLE_OBJECT' ? inputObjectName : undefined,
          resourceProfileId: values.resourceProfileId,
          hardwareTargetId:
            selectedResourceProfile?.deviceType === 'NVIDIA_GPU'
              ? values.hardwareTargetId
              : undefined,
          gpuMemoryLimitMiB:
            selectedResourceProfile?.deviceType === 'NVIDIA_GPU'
              ? values.gpuMemoryLimitMiB
              : undefined,
          params,
          remark: values.remark,
        },
        { skipErrorHandler: true },
      );
      message.success('推理任务已创建');
      setInputFileList([]);
      setCreateStep(0);
      taskForm.setFieldsValue({
        name: '',
        remark: '',
      });
      actionRef.current?.reload();
      if (res?.data) {
        void openTaskResult(res.data);
      }
    } catch (error: any) {
      message.error(error?.message || '创建推理任务失败');
    } finally {
      setCreating(false);
    }
  };

  const handleOpenResourceStep = async () => {
    try {
      await taskForm.validateFields([
        'name',
        'modelVersionId',
        'scriptVersionId',
        'inputMode',
        ...(inputMode === 'DATASET_VERSION' ? ['datasetVersionId'] : []),
        'paramsJson',
      ]);
      parseJson(taskForm.getFieldValue('paramsJson'));
      if (inputMode === 'SINGLE_OBJECT' && !inputFileList[0]?.originFileObj) {
        message.error('请选择单文件输入');
        return;
      }
      if (!usableResourceProfiles.length) {
        message.error('后端当前没有可用的推理资源规格');
        return;
      }
      setCreateStep(1);
    } catch {
      // 表单已经显示具体错误。
    }
  };

  const handleStopTask = async (task: InferenceTask) => {
    try {
      await stopInferenceTask(task.id, { skipErrorHandler: true });
      message.success('任务已停止');
      actionRef.current?.reload();
    } catch (error: any) {
      message.error(error?.message || '停止任务失败');
    }
  };

  const handleRetryTask = async (task: InferenceTask) => {
    setRetryingTaskId(task.id);
    try {
      const res = await retryInferenceTask(task.id, { skipErrorHandler: true });
      message.success('推理任务已重新提交');
      if (selectedTask?.id === task.id && res?.data) {
        setSelectedTask(res.data);
      }
      actionRef.current?.reload();
    } catch (error: any) {
      message.error(error?.message || '重试推理任务失败');
      actionRef.current?.reload();
      if (selectedTask?.id === task.id) {
        try {
          const latest = await getInferenceTask(task.id, {
            skipErrorHandler: true,
          });
          if (latest?.data) {
            setSelectedTask(latest.data);
          }
        } catch {
          // 保留当前详情，列表刷新仍会恢复服务端状态
        }
      }
    } finally {
      setRetryingTaskId(undefined);
    }
  };

  const handleDeleteTask = async (task: InferenceTask) => {
    try {
      await deleteInferenceTask(task.id, { skipErrorHandler: true });
      message.success('推理任务已删除');
      if (selectedTask?.id === task.id) {
        setSelectedTask(undefined);
        setDrawerOpen(false);
      }
      actionRef.current?.reload();
    } catch (error: any) {
      message.error(error?.message || '删除推理任务失败');
    }
  };

  const handleDeleteScript = async (script: InferenceScriptVersion) => {
    try {
      await deleteInferenceScript(script.id, { skipErrorHandler: true });
      message.success('推理脚本版本已删除');
      if (taskForm.getFieldValue('scriptVersionId') === script.id) {
        taskForm.setFieldValue('scriptVersionId', undefined);
      }
      await reloadAssets();
    } catch (error: any) {
      message.error(error?.message || '删除推理脚本失败');
    }
  };

  /** MinIO objectName → 浏览器原生下载（结果、日志、配图共用） */
  const downloadByObjectName = async (objectName: string, filename: string) => {
    if (!objectName) {
      message.warning('暂无可下载文件');
      return;
    }
    try {
      await downloadObjectWithBrowser(objectName, filename);
    } catch (error: any) {
      message.error(error?.message || '下载失败');
    }
  };

  const columns: ProColumns<InferenceTask>[] = [
    {
      title: '任务名称',
      dataIndex: 'name',
      ellipsis: true,
      width: 180,
    },
    {
      title: '输入',
      dataIndex: 'inputMode',
      width: 120,
      valueEnum: {
        SINGLE_OBJECT: { text: '单文件' },
        DATASET_VERSION: { text: '数据集' },
      },
      render: (_, record) =>
        record.inputMode === 'DATASET_VERSION' ? (
          <Tag color="blue">数据集</Tag>
        ) : (
          <Tag color="purple">单文件</Tag>
        ),
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 110,
      valueEnum: Object.fromEntries(
        Object.entries(STATUS_MAP).map(([key, item]) => [
          key,
          { text: item.text },
        ]),
      ),
      render: (_, record) => statusTag(record.status, record.errorMessage),
    },
    {
      title: '重试',
      dataIndex: 'retryCount',
      hideInSearch: true,
      width: 90,
      render: (_, record) => {
        if (
          record.retryCount === undefined ||
          record.maxRetries === undefined
        ) {
          return '-';
        }
        return `${record.retryCount}/${record.maxRetries}`;
      },
    },
    {
      title: '进度',
      dataIndex: 'progress',
      hideInSearch: true,
      width: 150,
      render: (_, record) => (
        <Progress
          percent={record.progress ?? 0}
          size="small"
          status={record.status === 'failed' ? 'exception' : undefined}
        />
      ),
    },
    {
      title: '创建时间',
      dataIndex: 'createdAt',
      valueType: 'dateTime',
      hideInSearch: true,
      width: 170,
    },
    {
      title: '完成时间',
      dataIndex: 'finishedAt',
      valueType: 'dateTime',
      hideInSearch: true,
      width: 170,
    },
    {
      title: '操作',
      key: 'action',
      hideInSearch: true,
      fixed: 'right',
      width: 320,
      render: (_, record) => {
        const isActive = ['pending', 'queued', 'scheduled', 'running'].includes(
          record.status,
        );
        const retryCount = record.retryCount ?? 0;
        const maxRetries = record.maxRetries ?? 3;
        const canRetry =
          record.status === 'failed' && record.retryable === true;
        return (
          <Space size={4}>
            <Button
              type="link"
              icon={<FileSearchOutlined />}
              onClick={() => {
                void openTaskResult(record);
              }}
            >
              结果
            </Button>
            {isActive && (
              <Popconfirm
                title="停止推理任务"
                description="确认停止当前任务吗？"
                onConfirm={() => handleStopTask(record)}
              >
                <Button danger type="link" icon={<StopOutlined />}>
                  停止
                </Button>
              </Popconfirm>
            )}
            {canRetry && (
              <Popconfirm
                title="重试推理任务"
                description={`确认重新提交该任务吗？当前已重试 ${retryCount}/${maxRetries} 次。`}
                okText="重试"
                cancelText="取消"
                onConfirm={() => handleRetryTask(record)}
              >
                <Button
                  type="link"
                  icon={<ReloadOutlined />}
                  loading={retryingTaskId === record.id}
                >
                  重试
                </Button>
              </Popconfirm>
            )}
            <Popconfirm
              title="删除推理任务"
              description={
                isActive
                  ? '删除会停止正在执行的推理任务，确认删除吗？'
                  : '确认删除该推理任务吗？'
              }
              okText="删除"
              cancelText="取消"
              okButtonProps={{ danger: true }}
              onConfirm={() => handleDeleteTask(record)}
            >
              <Button danger type="link">
                删除
              </Button>
            </Popconfirm>
          </Space>
        );
      },
    },
  ];

  return (
    <PageContainer
      title="模型推理"
      subTitle="上传推理脚本，选择模型与输入数据，异步执行自定义推理任务"
      extra={[
        <Button key="reload" icon={<ReloadOutlined />} onClick={reloadAssets}>
          刷新资源
        </Button>,
        <Button
          key="script"
          icon={<CloudUploadOutlined />}
          onClick={() => setScriptModalOpen(true)}
        >
          上传脚本
        </Button>,
      ]}
    >
      <Space direction="vertical" size={16} style={{ width: '100%' }}>
        <ProCard split="vertical" bordered bodyStyle={{ padding: 0 }}>
          <ProCard
            title="创建推理任务"
            colSpan={{ xs: 24, md: 9 }}
            style={{ minHeight: 620 }}
            data-tour="inf-create"
          >
            {sourceTrainingId && (
              <Alert
                type="info"
                showIcon
                style={{ marginBottom: 16 }}
                message="已从训练任务带入推理模型"
                description={
                  sourceTrainingProfile ===
                  'image_text_consistency_fusion_logreg'
                    ? '模型和测试数据集已自动选择。请选择兼容的融合模型推理脚本后即可执行。'
                    : '模型已自动选择，请确认推理脚本和输入数据与该模型兼容。'
                }
              />
            )}
            <Form
              form={taskForm}
              layout="vertical"
              initialValues={{
                inputMode: 'SINGLE_OBJECT',
                paramsJson: '{}',
              }}
              onValuesChange={(changed) => {
                if (changed.inputMode) setInputMode(changed.inputMode);
              }}
            >
              <Steps
                size="small"
                current={createStep}
                items={[{ title: '任务输入' }, { title: '资源配置' }]}
                style={{ marginBottom: 20 }}
              />
              {createStep === 0 && (
                <>
                  <Form.Item
                    name="name"
                    label="任务名称"
                    rules={[{ required: true, message: '请输入任务名称' }]}
                  >
                    <Input placeholder="例如 smoke-single-inference" />
                  </Form.Item>
                  <Form.Item
                    name="modelVersionId"
                    label="模型"
                    rules={[{ required: true, message: '请选择模型' }]}
                  >
                    <Select
                      data-tour="inf-model"
                      showSearch
                      loading={loadingAssets}
                      options={modelSelectOptions}
                      optionFilterProp="label"
                      placeholder="选择训练产出的模型"
                    />
                  </Form.Item>
                  <Form.Item
                    name="scriptVersionId"
                    label="推理脚本"
                    rules={[{ required: true, message: '请选择推理脚本' }]}
                  >
                    <Select
                      data-tour="inf-script"
                      showSearch
                      loading={loadingAssets}
                      options={scriptSelectOptions}
                      optionFilterProp="label"
                      placeholder="选择已上传脚本版本"
                      dropdownRender={(menu) => (
                        <>
                          {menu}
                          <Button
                            block
                            type="link"
                            icon={<UploadOutlined />}
                            onClick={() => setScriptModalOpen(true)}
                          >
                            上传新的推理脚本
                          </Button>
                        </>
                      )}
                    />
                  </Form.Item>
                  <Form.Item name="inputMode" label="输入方式">
                    <Radio.Group
                      optionType="button"
                      buttonStyle="solid"
                      data-tour="inf-mode"
                    >
                      <Radio.Button value="SINGLE_OBJECT">单文件</Radio.Button>
                      <Radio.Button value="DATASET_VERSION">
                        数据集
                      </Radio.Button>
                    </Radio.Group>
                  </Form.Item>
                  {inputMode === 'DATASET_VERSION' ? (
                    <Form.Item
                      name="datasetVersionId"
                      label="数据集"
                      rules={[{ required: true, message: '请选择数据集' }]}
                    >
                      <Select
                        showSearch
                        loading={loadingAssets}
                        options={datasetSelectOptions}
                        optionFilterProp="label"
                        placeholder="选择准备完成的数据集"
                      />
                    </Form.Item>
                  ) : (
                    <Form.Item label="上传单文件" required>
                      <Upload
                        maxCount={1}
                        fileList={inputFileList}
                        beforeUpload={() => false}
                        onChange={({ fileList }) =>
                          setInputFileList(fileList.slice(-1))
                        }
                      >
                        <Button icon={<UploadOutlined />}>选择文件</Button>
                      </Upload>
                    </Form.Item>
                  )}
                  <Form.Item
                    name="paramsJson"
                    label="脚本参数"
                    rules={[
                      {
                        validator: async (_, value) => {
                          parseJson(value);
                        },
                      },
                    ]}
                  >
                    <TextArea rows={6} placeholder='{"threshold":0.5}' />
                  </Form.Item>
                  <Form.Item name="remark" label="备注">
                    <Input.TextArea rows={2} />
                  </Form.Item>
                  <Button block type="primary" onClick={handleOpenResourceStep}>
                    下一步：资源配置
                  </Button>
                </>
              )}
              {createStep === 1 && (
                <>
                  <Alert
                    type="info"
                    showIcon
                    style={{ marginBottom: 16 }}
                    message="选择本次推理使用的资源规格"
                    description="CPU、内存和临时磁盘来自后端白名单；GPU 任务还会按 UUID 固定到所选物理卡。"
                  />
                  {!usableResourceProfiles.length && (
                    <Alert
                      type="error"
                      showIcon
                      style={{ marginBottom: 16 }}
                      message="没有可用的推理资源规格"
                      description="请刷新资源；若仍为空，需要先检查后端资源配置接口。"
                    />
                  )}
                  <Form.Item
                    name="resourceProfileId"
                    label="计算资源规格"
                    rules={[{ required: true, message: '请选择计算资源规格' }]}
                  >
                    <Select
                      disabled={!usableResourceProfiles.length}
                      onChange={() =>
                        taskForm.setFieldsValue({
                          hardwareTargetId: undefined,
                          gpuMemoryLimitMiB: undefined,
                        })
                      }
                      options={usableResourceProfiles.map((profile) => ({
                        value: profile.id,
                        label: `${profile.displayName} (${profile.id})`,
                      }))}
                    />
                  </Form.Item>
                  {selectedResourceProfile?.deviceType === 'NVIDIA_GPU' && (
                    <>
                      {!hardwareOptions.length && (
                        <Alert
                          type="error"
                          showIcon
                          style={{ marginBottom: 16 }}
                          message="当前没有可精确选择的 GPU"
                          description="请检查 GPU 指标是否新鲜，以及集群是否已启用 NVIDIA DRA。"
                        />
                      )}
                      <Form.Item
                        name="hardwareTargetId"
                        label="指定物理 GPU"
                        rules={[{ required: true, message: '请选择一张物理 GPU' }]}
                        extra="GPU 0/1 是宿主机编号；真正绑定使用下方 UUID。若该卡忙，任务会等待，不会换卡。"
                      >
                        <Select
                          disabled={!hardwareOptions.length}
                          options={hardwareOptions.map((option) => ({
                            value: option.hardwareTargetId,
                            label: option.displayName,
                          }))}
                        />
                      </Form.Item>
                      {selectedHardwareOption && (
                        <Descriptions size="small" column={1} bordered>
                          <Descriptions.Item label="宿主机编号">
                            GPU {selectedHardwareOption.hostGpuIndex}
                          </Descriptions.Item>
                          <Descriptions.Item label="GPU UUID">
                            {selectedHardwareOption.uuid}
                          </Descriptions.Item>
                          <Descriptions.Item label="总显存">
                            {selectedHardwareOption.totalMemoryMiB} MiB
                          </Descriptions.Item>
                          <Descriptions.Item label="当前空闲显存（参考）">
                            {selectedHardwareOption.freeMemoryMiB ?? '-'} MiB
                          </Descriptions.Item>
                        </Descriptions>
                      )}
                      <Form.Item
                        name="gpuMemoryLimitMiB"
                        label="GPU 显存软预算（可选）"
                        rules={[
                          {
                            validator: async (_, value) => {
                              if (value === undefined || value === null) return;
                              if (
                                !Number.isInteger(value) ||
                                value <= 0 ||
                                !selectedHardwareOption ||
                                value > selectedHardwareOption.totalMemoryMiB
                              ) {
                                throw new Error('显存预算必须是所选 GPU 总显存以内的正整数');
                              }
                            },
                          },
                        ]}
                        extra="该值会在用户推理脚本启动前传给 PyTorch CUDA 分配器；这是软限制。"
                      >
                        <InputNumber
                          min={1}
                          max={selectedHardwareOption?.totalMemoryMiB}
                          step={256}
                          precision={0}
                          disabled={!selectedHardwareOption}
                          style={{ width: '100%' }}
                          addonAfter="MiB"
                        />
                      </Form.Item>
                    </>
                  )}
                  {selectedResourceProfile && (
                    <Descriptions size="small" column={1} bordered>
                      <Descriptions.Item label="设备">
                        {selectedResourceProfile.deviceType}
                      </Descriptions.Item>
                      <Descriptions.Item label="CPU（申请 / 上限）">
                        {selectedResourceProfile.cpuRequest} /{' '}
                        {selectedResourceProfile.cpuLimit}
                      </Descriptions.Item>
                      <Descriptions.Item label="内存（申请 / 上限）">
                        {selectedResourceProfile.memoryRequest} /{' '}
                        {selectedResourceProfile.memoryLimit}
                      </Descriptions.Item>
                      <Descriptions.Item label="临时磁盘（申请 / 上限）">
                        {selectedResourceProfile.ephemeralStorageRequest} /{' '}
                        {selectedResourceProfile.ephemeralStorageLimit}
                      </Descriptions.Item>
                      <Descriptions.Item label="GPU 数量">
                        {selectedResourceProfile.gpuCount}
                      </Descriptions.Item>
                    </Descriptions>
                  )}
                  <Space style={{ marginTop: 16, width: '100%' }}>
                    <Button
                      style={{ flex: 1 }}
                      onClick={() => setCreateStep(0)}
                    >
                      上一步
                    </Button>
                    <Button
                      style={{ flex: 1 }}
                      type="primary"
                      icon={<PlayCircleOutlined />}
                      loading={creating}
                      onClick={handleCreateTask}
                      data-tour="inf-submit"
                    >
                      创建并执行
                    </Button>
                  </Space>
                </>
              )}
            </Form>
          </ProCard>
          <ProCard title="推理任务" colSpan={{ xs: 24, md: 15 }}>
            <ProTable<InferenceTask>
              actionRef={actionRef}
              columns={columns}
              rowKey="id"
              polling={3000}
              scroll={{ x: 1300 }}
              search={{ labelWidth: 'auto' }}
              request={async (params) => {
                const res = await listInferenceTasks(
                  {
                    page: params.current,
                    pageSize: params.pageSize,
                    status: params.status as string | undefined,
                  },
                  { skipErrorHandler: true },
                );
                return {
                  data: res?.data?.data ?? [],
                  total: res?.data?.total ?? 0,
                  success: true,
                };
              }}
              pagination={{ pageSize: 10 }}
              toolBarRender={() => [
                <Button
                  key="reload"
                  icon={<ReloadOutlined />}
                  onClick={() => actionRef.current?.reload()}
                >
                  刷新
                </Button>,
              ]}
            />
          </ProCard>
        </ProCard>

        <ProCard
          title="推理脚本版本"
          bordered
          extra={
            <Button
              icon={<UploadOutlined />}
              onClick={() => setScriptModalOpen(true)}
            >
              上传脚本
            </Button>
          }
        >
          <ProTable<InferenceScriptVersion>
            rowKey="id"
            search={false}
            options={false}
            pagination={{ pageSize: 5 }}
            dataSource={scriptOptions}
            columns={[
              {
                title: '脚本名称',
                dataIndex: 'scriptName',
                ellipsis: true,
              },
              {
                title: '版本',
                dataIndex: 'version',
                width: 120,
              },
              {
                title: '入口文件',
                dataIndex: 'entryFile',
                ellipsis: true,
              },
              {
                title: '运行时',
                dataIndex: 'runtime',
                width: 120,
              },
              {
                title: '大小',
                dataIndex: 'sizeBytes',
                width: 120,
                render: (_, record) => formatInferenceBytes(record.sizeBytes),
              },
              {
                title: '创建时间',
                dataIndex: 'createdAt',
                valueType: 'dateTime',
                width: 170,
              },
              {
                title: '操作',
                key: 'action',
                width: 100,
                render: (_, record) => (
                  <Popconfirm
                    title="删除推理脚本版本"
                    description="确认删除该脚本版本吗？若已被推理任务引用将无法删除。"
                    okText="删除"
                    cancelText="取消"
                    okButtonProps={{ danger: true }}
                    onConfirm={() => handleDeleteScript(record)}
                  >
                    <Button danger type="link">
                      删除
                    </Button>
                  </Popconfirm>
                ),
              },
            ]}
          />
        </ProCard>
      </Space>

      <Modal
        title="上传推理脚本"
        open={scriptModalOpen}
        onCancel={() => setScriptModalOpen(false)}
        onOk={handleUploadScript}
        confirmLoading={scriptUploading}
        destroyOnClose
      >
        <Form
          form={scriptForm}
          layout="vertical"
          initialValues={{
            version: 'v1',
            runtime: 'PYTHON3',
            entryFile: 'infer.py',
          }}
        >
          <Form.Item
            name="scriptName"
            label="脚本名称"
            rules={[{ required: true, message: '请输入脚本名称' }]}
          >
            <Input placeholder="例如 cv-classifier-infer" />
          </Form.Item>
          <Form.Item
            name="version"
            label="版本号"
            rules={[{ required: true, message: '请输入版本号' }]}
          >
            <Input placeholder="v1" />
          </Form.Item>
          <Form.Item name="runtime" label="运行时">
            <Select options={[{ label: 'Python 3', value: 'PYTHON3' }]} />
          </Form.Item>
          <Form.Item
            name="entryFile"
            label="入口文件"
            rules={[{ required: true, message: '请输入入口文件' }]}
          >
            <Input placeholder="infer.py" />
          </Form.Item>
          <Form.Item label="脚本 ZIP">
            <Upload
              maxCount={1}
              fileList={scriptFileList}
              beforeUpload={() => false}
              onChange={({ fileList }) => setScriptFileList(fileList.slice(-1))}
            >
              <Button icon={<UploadOutlined />}>选择 ZIP</Button>
            </Upload>
          </Form.Item>
          <Form.Item name="paramsSchemaJson" label="参数表单 Schema JSON">
            <TextArea rows={4} placeholder='{"threshold":{"type":"number"}}' />
          </Form.Item>
          <Form.Item name="remark" label="备注">
            <TextArea rows={2} />
          </Form.Item>
        </Form>
      </Modal>

      {/* 结果抽屉：任务摘要 + 下载 + 运行日志 + 结构化/可视化结果 */}
      <Drawer
        title="推理结果"
        open={drawerOpen}
        width={860}
        onClose={() => setDrawerOpen(false)}
        extra={selectedTask ? statusTag(selectedTask.status) : null}
      >
        {selectedTask && (
          <Space direction="vertical" size={16} style={{ width: '100%' }}>
            <Descriptions column={1} size="small" bordered>
              <Descriptions.Item label="任务 ID">
                {selectedTask.id}
              </Descriptions.Item>
              <Descriptions.Item label="任务名称">
                {selectedTask.name}
              </Descriptions.Item>
              <Descriptions.Item label="推理模型">
                {optionLabel(modelSelectOptions, selectedTask.modelVersionId)}
              </Descriptions.Item>
              <Descriptions.Item label="推理脚本">
                {optionLabel(scriptSelectOptions, selectedTask.scriptVersionId)}
              </Descriptions.Item>
              <Descriptions.Item label="资源规格">
                {selectedTask.resourceProfileId || '历史任务（旧全局配置）'}
              </Descriptions.Item>
              {selectedTask.gpuUuid && (
                <Descriptions.Item label="物理 GPU">
                  {selectedTask.gpuModel} · {selectedTask.gpuNodeName || '-'} · GPU{' '}
                  {selectedTask.gpuHostIndex} ·{' '}
                  {selectedTask.gpuUuid}
                </Descriptions.Item>
              )}
              {selectedTask.gpuMemoryLimitMiB && (
                <Descriptions.Item label="显存软预算">
                  {selectedTask.gpuMemoryLimitMiB} MiB
                </Descriptions.Item>
              )}
              <Descriptions.Item label="输入">
                {selectedTask.inputMode === 'DATASET_VERSION'
                  ? optionLabel(
                      datasetSelectOptions,
                      selectedTask.datasetVersionId,
                    )
                  : selectedTask.inputObjectName || '-'}
              </Descriptions.Item>
              <Descriptions.Item label="执行轮次">
                {selectedTask.currentAttempt === undefined ||
                selectedTask.retryCount === undefined ||
                selectedTask.maxRetries === undefined
                  ? '-'
                  : `第 ${selectedTask.currentAttempt} 次执行，已重试 ${selectedTask.retryCount}/${selectedTask.maxRetries} 次`}
              </Descriptions.Item>
              <Descriptions.Item label="进度">
                <Progress
                  percent={selectedTask.progress ?? 0}
                  size="small"
                  status={
                    selectedTask.status === 'failed' ? 'exception' : undefined
                  }
                />
              </Descriptions.Item>
              {selectedTask.errorMessage && (
                <Descriptions.Item label="错误信息">
                  <Typography.Text type="danger">
                    {selectedTask.errorMessage}
                  </Typography.Text>
                </Descriptions.Item>
              )}
              <Descriptions.Item label="输出目录">
                <Typography.Text copyable ellipsis style={{ maxWidth: 560 }}>
                  {selectedTask.outputPath || '-'}
                </Typography.Text>
              </Descriptions.Item>
              <Descriptions.Item label="日志文件">
                <Typography.Text copyable ellipsis style={{ maxWidth: 560 }}>
                  {selectedTask.logPath || '-'}
                </Typography.Text>
              </Descriptions.Item>
            </Descriptions>
            <InferenceOriginalInput
              inputMode={selectedTask.inputMode}
              inputObjectName={selectedTask.inputObjectName}
              datasetVersionId={selectedTask.datasetVersionId}
              datasetDisplayName={optionLabel(
                datasetSelectOptions,
                selectedTask.datasetVersionId,
              )}
              onDownloadObject={downloadByObjectName}
            />
            <Space wrap>
              {selectedTask.status === 'failed' &&
                selectedTask.retryable === true && (
                  <Popconfirm
                    title="重试推理任务"
                    description={`确认重新提交该任务吗？当前已重试 ${
                      selectedTask.retryCount ?? 0
                    }/${selectedTask.maxRetries ?? 3} 次。`}
                    okText="重试"
                    cancelText="取消"
                    onConfirm={() => handleRetryTask(selectedTask)}
                  >
                    <Button
                      type="primary"
                      icon={<ReloadOutlined />}
                      loading={retryingTaskId === selectedTask.id}
                    >
                      重试
                    </Button>
                  </Popconfirm>
                )}
              <Button
                icon={<DownloadOutlined />}
                disabled={!selectedTask.outputPath}
                onClick={() =>
                  downloadByObjectName(
                    minioPathToResultObject(selectedTask.outputPath),
                    `${selectedTask.id}-result.json`,
                  )
                }
              >
                下载结果
              </Button>
              <Button
                icon={<DownloadOutlined />}
                disabled={!selectedTask.logPath}
                onClick={() =>
                  downloadByObjectName(
                    objectNameFromMinioPath(selectedTask.logPath),
                    `${selectedTask.id}.log`,
                  )
                }
              >
                下载日志
              </Button>
            </Space>

            <InferenceLogPanel
              logPath={selectedTask.logPath}
              status={selectedTask.status}
            />

            <Collapse
              size="small"
              items={[
                {
                  key: 'structured',
                  label: '结构化结果',
                  children: (
                    <pre
                      style={{
                        margin: 0,
                        padding: 12,
                        minHeight: 120,
                        maxHeight: 280,
                        overflow: 'auto',
                        background: '#111827',
                        color: '#e5e7eb',
                        borderRadius: 6,
                      }}
                    >
                      {JSON.stringify(selectedTask.result || {}, null, 2)}
                    </pre>
                  ),
                },
              ]}
            />

            <div>
              <Typography.Title level={5} style={{ marginTop: 0 }}>
                可视化结果
              </Typography.Title>
              <InferenceResultVisual
                result={selectedTask.result}
                outputPath={selectedTask.outputPath}
                onDownloadObject={downloadByObjectName}
              />
            </div>
          </Space>
        )}
      </Drawer>
      <Tour {...tourProps} />
    </PageContainer>
  );
};

export default InferenceWorkbench;
