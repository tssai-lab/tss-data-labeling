declare module '*.css';

declare namespace API {
  // 模型相关
  type ModelItem = {
    id: string;
    name: string;
    version: string;
    type: 'CV' | 'NLP' | 'POINT_CLOUD' | 'ROBOT' | 'OTHER';
    uploadTime?: string;
    size?: string;
    sizeBytes?: number;
    remark?: string;
    storagePath?: string;
    fileName?: string;
    createdAt?: string;
    updatedAt?: string;
    assetId?: string;
    /** 制品 SHA-256；历史版本可能为空 */
    artifactSha256?: string;
    /** 服务端根据文件内容签发的训练规格；为空表示只能存储，不能自动进入新训练候选 */
    artifactSpecId?: string;
    commitInfo?: string;
    hyperParams?: Record<string, unknown>;
    isCurrent?: boolean;
    status?: string;
  };

  type ModelVersionDetail = {
    id: string;
    assetId: string;
    version: string;
    name?: string;
    type?: ModelItem['type'];
    fileName?: string;
    storagePath?: string;
    sizeBytes?: number;
    size?: string;
    createdAt?: string;
    updatedAt?: string;
    remark?: string;
    artifactSha256?: string;
    artifactSpecId?: string;
    commitInfo?: string;
    hyperParams?: Record<string, unknown>;
    isCurrent?: boolean;
    status?: string;
    codeContent?: string;
    codeFileName?: string;
    codeFilePath?: string;
    codeFiles?: API.ModelCodeFile[];
  };

  type ModelAssetDetail = {
    id: string;
    name: string;
    type: ModelItem['type'];
    remark?: string;
    createdAt?: string;
    updatedAt?: string;
    uploadTime?: string;
    /** 资产当前推荐 READY 版本 */
    currentVersionId?: string;
    latestVersion?: ModelVersionDetail;
    versions: ModelVersionDetail[];
    defaultVersionId?: string;
  };

  /** 分片上传初始化请求 */
  type ModelUploadInitParams = {
    fileName: string;
    fileSize: number;
    fileFingerprint?: string;
    /** 提交说明；须与 init/complete 一致且非空（1～1024） */
    commitInfo: string;
    hyperParams?: Record<string, unknown>;
    /** V2 init：新建资产必填；已有资产新增版本时传 targetAssetId */
    modelName?: string;
    modelVersion?: string;
    taskType?: string;
    remark?: string;
    targetAssetId?: string;
  };

  /** 分片上传进度 / 初始化响应 */
  type ModelUploadInitResult = {
    uploadId: string;
    chunkSize?: number;
    status?: string;
    fileName?: string;
    fileSize?: number;
    totalChunks?: number;
    uploadedChunks?: number;
    uploadedBytes?: number;
    uploadedPartIndexes?: number[];
    artifactSpecId?: string;
  };

  type ModelUploadCompleteParams = {
    uploadId: string;
    /** 已有资产时传入：仅新增版本，不创建新资产 */
    assetId?: string;
    modelName: string;
    version: string;
    type: string;
    remark: string;
    /** 必须与上传初始化阶段保持一致 */
    commitInfo: string;
    hyperParams?: Record<string, unknown>;
  };

  /** GET /api/model/code-files 返回项（与 backend-api.md 对齐，兼容旧字段） */
  type ModelCodeFile = {
    path: string;
    fileName?: string;
    name?: string;
    extension?: string;
    sizeBytes?: number;
    size?: number;
  };

  /** GET /api/model/previewCode */
  type ModelCodePreview = {
    path?: string;
    fileName?: string;
    content: string;
    sizeBytes?: number;
    language?: string;
  };

  // 数据集相关（列表聚合行字段与 services/dataset 对齐）
  type DatasetItem = {
    id: string;
    assetId?: string;
    name: string;
    type:
      | 'CV'
      | 'NLP'
      | 'POINT_CLOUD'
      | 'MULTIMODAL'
      | 'ROBOT'
      | 'LEROBOT'
      | 'OTHER';
    uploadTime?: string;
    size?: string;
    fileCount?: number | null;
    version?: string;
    versionId?: string;
    versionStatus?: string;
    artifactSpecId?: string;
    remark?: string;
    storagePath?: string;
    sizeBytes?: number;
    createdAt?: string;
    updatedAt?: string;
    fileName?: string;
    versionRemark?: string;
    latestDraftVersionId?: string | null;
    importJobId?: string | null;
    importStatus?: string | null;
    importProgress?: number | null;
    importErrorMessage?: string | null;
    displayStatus?: string;
    editSessionId?: string | null;
  };

  // 任务相关
  type TaskItem = {
    id: string;
    name: string;
    experimentId?: string;
    versionNo?: number;
    modelName?: string;
    datasetName?: string;
    modelId?: string;
    datasetId?: string;
    createTime: string;
    status: 'pending' | 'queued' | 'scheduled' | 'running' | 'success' | 'failed' | 'stopped';
    progress: number;
    modelVersionId?: string;
    baseModelVersionId?: string;
    /** 训练成功后发布的结果模型版本 ID */
    producedModelVersionId?: string;
    modelPublishStatus?: string;
    modelPublishError?: string;
    modelPublishedAt?: string;
    modelArtifactPath?: string;
    modelArtifactSha256?: string;
    modelArtifactSizeBytes?: number;
    codeVersionId?: string;
    trainingProfile?: string;
    trainingPlanId?: string;
    trainingPlanVersion?: string;
    trainingMode?: string;
    resourceProfileId?: string;
    runSpec?: {
      resources?: {
        profileId?: string;
        gpuNodeName?: string;
        gpuHostIndex?: string;
        gpuUuid?: string;
        gpuModel?: string;
        gpuTotalMemoryMiB?: number;
        gpuMemoryLimitMiB?: number;
      };
    };
    datasetVersionId?: string;
    hyperParams?: Record<string, any>;
    metrics?: Record<string, any>;
    runId?: string;
    logPath?: string;
    outputPath?: string;
    producedModelVersionId?: string;
    modelPublishStatus?: 'PENDING' | 'PUBLISHING' | 'PUBLISHED' | 'FAILED';
    modelPublishError?: string;
    modelPublishedAt?: string;
    modelArtifactPath?: string;
    modelArtifactSha256?: string;
    modelArtifactSizeBytes?: number;
    errorMessage?: string;
    startedAt?: string;
    finishedAt?: string;
    remark?: string;
  };

  /** 训练实验版本记录（后端 TrainingExperimentVersion） */
  type TrainingExperimentVersion = {
    id: string;
    experimentId: string;
    versionNo: number;
    name?: string;
    modelVersionId?: string;
    baseModelVersionId?: string;
    /** 训练成功后发布的结果模型版本 ID */
    producedModelVersionId?: string;
    modelPublishStatus?: string;
    modelPublishError?: string;
    modelPublishedAt?: string;
    modelArtifactPath?: string;
    modelArtifactSha256?: string;
    modelArtifactSizeBytes?: number;
    codeVersionId: string;
    trainingProfile?: string;
    trainingPlanId?: string;
    trainingPlanVersion?: string;
    trainingMode?: string;
    resourceProfileId?: string;
    runSpec?: {
      resources?: {
        profileId?: string;
        gpuNodeName?: string;
        gpuHostIndex?: string;
        gpuUuid?: string;
        gpuModel?: string;
        gpuTotalMemoryMiB?: number;
        gpuMemoryLimitMiB?: number;
      };
    };
    datasetVersionId: string;
    hyperParams?: Record<string, any>;
    status: 'pending' | 'queued' | 'scheduled' | 'running' | 'success' | 'failed' | 'stopped';
    progress?: number;
    metrics?: Record<string, any>;
    runId?: string;
    logPath?: string;
    outputPath?: string;
    producedModelVersionId?: string;
    modelPublishStatus?: 'PENDING' | 'PUBLISHING' | 'PUBLISHED' | 'FAILED';
    modelPublishError?: string;
    modelPublishedAt?: string;
    modelArtifactPath?: string;
    modelArtifactSha256?: string;
    modelArtifactSizeBytes?: number;
    errorMessage?: string;
    startedAt?: string;
    finishedAt?: string;
    remark?: string;
    createdAt?: string;
    updatedAt?: string;
    createTime?: string;
  };

  // 模型详情（扩展字段，后端可能部分返回）
  type ModelDetail = API.ModelItem & {
    updateTime?: string;
    timestamp?: string;
    params?: {
      framework?: string;
      inputSize?: string;
      numClasses?: string;
      paramsCount?: string;
      trainDataset?: string;
      trainParams?: string;
    };
    codeContent?: string;
    codeFileName?: string;
    codeFilePath?: string;
    codeFiles?: API.ModelCodeFile[];
    versionHistory?: {
      version: string;
      updateTime: string;
      timestamp: string;
    }[];
  };

  type DatasetVersionDetail = {
    id: string;
    assetId: string;
    version: string;
    fileName?: string;
    storagePath?: string;
    sizeBytes?: number;
    size?: string;
    remark?: string;
    status?: 'DRAFT' | 'READY' | 'DEPRECATED' | 'ARCHIVED' | string;
    parentVersionId?: string | null;
    artifactSha256?: string;
    artifactSpecId?: string;
    createdAt?: string;
  };

  type DatasetDetail = {
    id: string;
    name: string;
    type:
      | 'CV'
      | 'NLP'
      | 'POINT_CLOUD'
      | 'MULTIMODAL'
      | 'ROBOT'
      | 'LEROBOT'
      | 'OTHER';
    remark?: string;
    createdAt?: string;
    updatedAt?: string;
    uploadTime?: string;
    latestVersion?: API.DatasetVersionDetail;
    versions: API.DatasetVersionDetail[];
    latestDraftVersionId?: string | null;
    importJobId?: string | null;
    importStatus?: string | null;
    importProgress?: number | null;
    importErrorMessage?: string | null;
  };

  // MLflow 指标点
  type MlflowMetricPoint = {
    key: string;
    value: number;
    timestamp?: number;
    step: number;
    run_id?: string;
  };

  // 用户相关
  type UserItem = {
    id: string;
    username: string;
    phone: string;
    role: 'admin' | 'user';
    createTime: string;
    status: 'enabled' | 'disabled';
  };

  // 日志相关
  type LogItem = {
    id: string;
    username: string;
    operateType: string;
    operateTime: string;
    ip: string;
    content: string;
    result: 'success' | 'failed';
  };

  // 算力资源监控
  type ResourceMonitorServerSpecs = {
    cpu: string;
    memory: string;
    gpu: string;
    os: string;
  };

  type ResourceMonitorRunningTask = {
    id: string;
    name: string;
    model: string;
    dataset: string;
    startTime: string;
    progress: number;
    cpuUsage: number;
    memUsage: number;
    gpuUsage: number;
  };

  type ResourceMonitorQueuedTask = {
    id: string;
    name: string;
    model: string;
    dataset: string;
    submitTime: string;
    /** 业务优先级，默认「中」；上下移动不会改变；预留后续多优先级需求 */
    priority: '高' | '中' | '低' | string;
    /** 人工排序标记：0=无干预，非 0=超管手动指定槽位（越小越靠前） */
    queueSortIndex: number;
  };

  type ResourceMonitorServerItem = {
    serverIp: string;
    hostname: string;
    enabled?: boolean;
    status: 'online' | 'warning';
    cpuRate: number | null;
    memRate: number | null;
    gpuRate: number | null;
    diskRate?: number | null;
    networkIn?: number | null;
    networkOut?: number | null;
    gpuMemRate?: number | null;
    gpuTemp?: number | null;
    gpuDevices?: Array<{
      hostGpuIndex?: string | null;
      uuid?: string | null;
      model?: string | null;
      totalMemoryMiB?: number | null;
      freeMemoryMiB?: number | null;
      utilizationRate?: number | null;
      temperatureCelsius?: number | null;
    }>;
    metricsStatus:
      | 'fresh'
      | 'temporarily_unavailable'
      | 'stale'
      | 'unavailable';
    metricsLastSuccessAt?: string | null;
    metricsLastAttemptAt?: string | null;
    metricsMessage?: string | null;
    nodeReady?: boolean | null;
    nodeUnschedulable?: boolean | null;
    nodeMemoryPressure?: boolean | null;
    nodeDiskPressure?: boolean | null;
    nodePidPressure?: boolean | null;
    nodeHealthStatus?: 'healthy' | 'warning' | 'unavailable';
    runTask: number;
    waitTask: number;
    runningTasks: API.ResourceMonitorRunningTask[];
    queuedTasks: API.ResourceMonitorQueuedTask[];
    specs?: API.ResourceMonitorServerSpecs;
  };

  type ResourceMonitorSummary = {
    total: number;
    online: number;
    runningTasks: number;
    queuedTasks: number;
    avgGpu: string | number | null;
  };

  type ResourceMonitorMetricPoint = {
    tickIndex: number;
    fullTime: string;
    time: string;
    type: string;
    value: number | null;
  };

  type ResourceMonitorMetrics = {
    interval: string;
    spanLabel: string;
    points: API.ResourceMonitorMetricPoint[];
    metricsStatus:
      | 'fresh'
      | 'temporarily_unavailable'
      | 'stale'
      | 'unavailable';
    metricsLastSuccessAt?: string | null;
    metricsLastAttemptAt?: string | null;
    metricsMessage?: string | null;
  };

  type KubernetesNodeHealth = {
    name: string;
    ready?: boolean | null;
    unschedulable?: boolean | null;
    memoryPressure?: boolean | null;
    diskPressure?: boolean | null;
    pidPressure?: boolean | null;
    healthStatus: 'healthy' | 'warning' | 'unavailable';
    message?: string | null;
  };

  type KubernetesPodIssue = {
    namespace: string;
    podName: string;
    nodeName?: string | null;
    phase: string;
    containerType: 'pod' | 'init' | 'main';
    containerName?: string | null;
    reason: string;
    message?: string | null;
    exitCode?: number | null;
  };

  type KubernetesWorkloadImage = {
    namespace: string;
    podName: string;
    nodeName?: string | null;
    workloadType: 'training' | 'inference';
    containerType: 'init' | 'main';
    containerName: string;
    declaredImage: string;
    imageId?: string | null;
    configuredInferenceImageMatch?: boolean | null;
  };

  type KubernetesDiagnostics = {
    collectionStatus: 'healthy' | 'degraded' | 'unavailable';
    message?: string | null;
    collectedAt: string;
    configuredInferenceImage: string;
    nodes: API.KubernetesNodeHealth[];
    podIssues: API.KubernetesPodIssue[];
    workloadImages: API.KubernetesWorkloadImage[];
  };

  /** 全局排队任务（跨服务器，按资源池分组） */
  type ResourceMonitorGlobalQueuedTask = {
    id: string;
    name: string;
    model: string;
    dataset: string;
    submitTime: string;
    priority: '高' | '中' | '低' | string;
    queueSortIndex: number;
    /** queued / pending */
    status: string;
    /** 分组键：tss.ai/node-pool 值（cpu/gpu/…），新增池自动出现新分组 */
    nodePool: string;
    /** 组内序号（1-based），仅同池内有意义 */
    positionInPool: number;
  };
}
