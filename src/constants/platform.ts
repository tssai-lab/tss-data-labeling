/**
 * 平台配置常量（源自 TSSAIPlatform-frontend-prototype）
 * 避免硬编码，便于二次开发和维护
 */

/** API 配置 */
export const API_CONFIG = {
  TIMEOUT: 30000,
  ENDPOINTS: {
    MODEL_LIST: '/model/list',
    MODEL_UPLOAD: '/model/upload',
    /** 分片上传（与 TSSAIPlatform-xyx 后端 ModelUploadController 对齐） */
    MODEL_UPLOAD_INIT: '/model/upload/init',
    MODEL_UPLOAD_CHUNK: '/model/upload/chunk',
    MODEL_UPLOAD_PROGRESS: '/model/upload/progress',
    MODEL_UPLOAD_COMPLETE: '/model/upload/complete',
    MODEL_DETAIL: '/model/detail',
    MODEL_DELETE: '/model/delete',
    MODEL_CODE_FILES: '/model/code-files',
    MODEL_PREVIEW_CODE: '/model/previewCode',
    CODE_FILES: '/code/code-files',
    CODE_PREVIEW_CODE: '/code/previewCode',

    DATASET_LIST: '/dataset/list',
    /** 数据集断点续传 */
    DATASET_UPLOAD_INIT: '/dataset/upload/init',
    DATASET_UPLOAD_CHUNK: '/dataset/upload/chunk',
    DATASET_UPLOAD_PROGRESS: '/dataset/upload/progress',
    DATASET_UPLOAD_COMPLETE: '/dataset/upload/complete',
    DATASET_UPLOAD_FOLDER: '/dataset/upload/folder',

    TASK_LIST: '/task/list',
    TASK_CREATE: '/task/create',
    TASK_DETAIL: '/task/detail',
    TASK_STOP: '/task/stop',
    TASK_DELETE: '/task/delete',
    /** 将训练产物登记为结果模型版本 */
    TASK_PUBLISH_MODEL: (id: string) =>
      `/task/${encodeURIComponent(id)}/publish-model`,

    /** 算力资源监控 */
    RESOURCE_MONITOR_SUMMARY: '/resource-monitor/summary',
    RESOURCE_MONITOR_SERVERS: '/resource-monitor/servers',
    RESOURCE_MONITOR_SERVER: (serverIp: string) =>
      `/resource-monitor/servers/${encodeURIComponent(serverIp)}`,
    RESOURCE_MONITOR_SERVER_ENABLED: (serverIp: string) =>
      `/resource-monitor/servers/${encodeURIComponent(serverIp)}/enabled`,
    RESOURCE_MONITOR_METRICS: (serverIp: string) =>
      `/resource-monitor/servers/${encodeURIComponent(serverIp)}/metrics`,
    RESOURCE_MONITOR_QUEUE_REORDER: (serverIp: string) =>
      `/resource-monitor/servers/${encodeURIComponent(serverIp)}/queue/reorder`,
    RESOURCE_MONITOR_QUEUE_PRIORITY: (serverIp: string) =>
      `/resource-monitor/servers/${encodeURIComponent(serverIp)}/queue/priority`,
    RESOURCE_MONITOR_QUEUE_TASK: (serverIp: string, taskId: string) =>
      `/resource-monitor/servers/${encodeURIComponent(serverIp)}/queue/${encodeURIComponent(taskId)}`,
    RESOURCE_MONITOR_GLOBAL_QUEUE: '/resource-monitor/queue',
    RESOURCE_MONITOR_GLOBAL_QUEUE_REORDER: '/resource-monitor/queue/reorder',
    RESOURCE_MONITOR_GLOBAL_QUEUE_TASK: (taskId: string) =>
      `/resource-monitor/queue/${encodeURIComponent(taskId)}`,
    RESOURCE_MONITOR_KUBERNETES_DIAGNOSTICS:
      '/resource-monitor/kubernetes/diagnostics',

    /** 实验版本管理 */
    EXPERIMENT_VERSIONS: (experimentId: string) =>
      `/experiments/${encodeURIComponent(experimentId)}/versions`,
    EXPERIMENT_VERSION_DETAIL: (experimentId: string, versionNo: number) =>
      `/experiments/${encodeURIComponent(experimentId)}/versions/${encodeURIComponent(String(versionNo))}`,
    EXPERIMENT_VERSION_CREATE: (experimentId: string) =>
      `/experiments/${encodeURIComponent(experimentId)}/versions`,
    EXPERIMENT_HYPER_PARAMS_UPDATE: (experimentId: string, versionNo: number) =>
      `/experiments/${encodeURIComponent(experimentId)}/versions/${encodeURIComponent(String(versionNo))}/hyper-parameters`,

    /** 独立 MLflow 指标接口（经 proxy 转发） */
    MLFLOW_METRICS_HISTORY: '/mlflow-api/2.0/mlflow/metrics/get-history-bulk',

    /** GPU 资源概况（待后端实现） */
    GPU_OVERVIEW: '/resource/gpu/overview',
  },
} as const;

/** 文件上传配置 */
export const UPLOAD_CONFIG = {
  MODEL: {
    MAX_SIZE: 2 * 1024 * 1024 * 1024, // 2GB
    /** 单文件模型：.pt/.pth/.onnx 等；千问等大模型由多文件组成，请上传 .zip 包 */
    ACCEPT_TYPES: ['.zip', '.safetensors', '.pt', '.pth', '.ckpt', '.onnx'],
    REQUIRED_FIELDS: ['file', 'name', 'version', 'type', 'remark'],
  },
  DATASET: {
    MAX_SIZE: 50 * 1024 * 1024 * 1024, // 50GB
    ACCEPT_TYPES: [] as string[],
    REQUIRED_FIELDS: ['file', 'name'],
    REQUIRE_ANNOTATION: false,
  },
  TRAINING_CODE: {
    MAX_SIZE: 50 * 1024 * 1024, // 50MB
    ACCEPT_TYPES: ['.py', '.pyx', '.ipynb', '.txt'],
  },
} as const;

/** 模型类型 */
export const MODEL_TYPES = {
  CV: { label: 'CV（计算机视觉）', value: 'CV', color: '#1890ff' },
  NLP: { label: 'NLP（自然语言处理）', value: 'NLP', color: '#52c41a' },
} as const;

/** 数据集类型 */
export const DATASET_TYPES = {
  CV: { label: 'CV（计算机视觉）', value: 'CV', color: '#1890ff' },
  NLP: { label: 'NLP（自然语言处理）', value: 'NLP', color: '#52c41a' },
  POINT_CLOUD: { label: '点云', value: 'POINT_CLOUD', color: '#722ed1' },
  MULTIMODAL: { label: '多模态', value: 'MULTIMODAL', color: '#eb2f96' },
  LEROBOT: { label: 'LeRobot', value: 'LEROBOT', color: '#1677ff' },
} as const;

/** 任务状态 */
export const TASK_STATUS = {
  PENDING: { label: '待执行', value: 'pending', color: '#595959' },
  QUEUED: { label: '调度中', value: 'queued', color: '#faad14' },
  RUNNING: { label: '运行中', value: 'running', color: '#1890ff' },
  SUCCESS: { label: '成功', value: 'success', color: '#52c41a' },
  FAILED: { label: '失败', value: 'failed', color: '#ff4d4f' },
  STOPPED: { label: '已停止', value: 'stopped', color: '#8c8c8c' },
} as const;

/** 训练可视化配置 */
export const VISUALIZATION_CONFIG = {
  REALTIME: true,
  METRICS_POLL_INTERVAL_MS: 4000,
  TASK_STATUS_POLL_INTERVAL_MS: 3000,
  GENERATE_AFTER_TRAINING: true,
  STORAGE_TYPE: 'cloud',
  SUPPORT_DISTRIBUTED: true,
  CHART_TYPES: ['loss', 'accuracy', 'precision', 'recall', 'f1'],
} as const;

/** 分页配置 */
export const PAGINATION_CONFIG = {
  DEFAULT_PAGE_SIZE: 10,
  PAGE_SIZE_OPTIONS: [10, 20, 50, 100],
} as const;

/** 日期格式 */
export const DATE_FORMAT = {
  DISPLAY: 'YYYY-MM-DD HH:mm:ss',
  TIMESTAMP: 'X',
} as const;
