/** 数据集内部契约：资产、版本、上传会话各用自己的编号和状态。 */

/**
 * 数据集服务接口。
 *
 * 对接模块二后端 `/api/dataset/**` 与 `/api/dataset-*`。数据集支持 CV/NLP 强类型校验、
 * 资产/版本管理、分片断点续传，以及 CV 图片文件夹直传打包。
 */

/** 训练创建使用的任务类型（不含 MULTIMODAL） */
export type TaskType = 'CV' | 'NLP' | 'POINT_CLOUD';

/** 数据集模块类型（含多模态、机器人预留） */
export type DatasetType =
  | TaskType
  | 'MULTIMODAL'
  | 'ROBOT'
  | 'LEROBOT'
  | 'OTHER';

/** CV 子任务（module2-api-doc 1.3） */
export type CvTaskType =
  | 'IMAGE_CLASSIFICATION'
  | 'OBJECT_DETECTION'
  | 'SEMANTIC_SEGMENTATION'
  | 'INSTANCE_SEGMENTATION'
  | 'UNLABELED'
  | 'OTHER';

/** CV 标注格式（module2-api-doc 1.3） */
export type AnnotationFormat =
  | 'NONE'
  | 'FOLDER_CLASSIFICATION'
  | 'CSV'
  | 'YOLO'
  | 'COCO'
  | 'VOC'
  | 'MASK'
  | 'LABELME'
  | 'OTHER';

/** 数据集资产：表示一个数据集主体，不等同于某个具体文件版本。 */
export type DatasetAsset = {
  id: string;
  name: string;
  type?: DatasetType;
  remark?: string;
  createdAt?: string;
  updatedAt?: string;
};

/** 数据集版本：表示某个数据集资产下的一个具体文件版本。 */
export type DatasetVersion = {
  id: string;
  assetId: string;
  version: string;
  fileName?: string;
  storagePath?: string;
  sizeBytes?: number;
  remark?: string;
  status?: 'DRAFT' | 'READY' | 'DEPRECATED' | 'ARCHIVED' | string;
  parentVersionId?: string | null;
  artifactSha256?: string;
  artifactSpecId?: string;
  createdAt?: string;
};

/** 数据集列表页使用的聚合视图，包含资产信息和当前最新版本信息。 */
export type DatasetListItem = {
  id: string;
  assetId: string;
  name: string;
  type: DatasetType;
  remark?: string;
  versionId?: string;
  version?: string;
  versionStatus?: string;
  artifactSpecId?: string;
  fileName?: string;
  storagePath?: string;
  size?: string;
  sizeBytes?: number;
  versionRemark?: string;
  fileCount?: number | null;
  uploadTime?: string;
  createdAt?: string;
  updatedAt?: string;
  latestDraftVersionId?: string | null;
  importJobId?: string | null;
  importStatus?: 'PENDING' | 'RUNNING' | 'FAILED' | string | null;
  importProgress?: number | null;
  importErrorMessage?: string | null;
  /** V2 聚合展示状态 */
  displayStatus?: string;
  /** @deprecated 新契约用 workspaceId */
  editSessionId?: string | null;
  workspaceId?: string | null;
  workspaceRevision?: number | null;
  hasDraft?: boolean;
};

/** GET /api/dataset/list 查询参数（module2-api-doc 7.1） */
export type DatasetListQuery = {
  type?: DatasetType;
  keyword?: string;
  current?: number;
  pageSize?: number;
  page?: number;
  artifactSpecIds?: string;
};

/** 多模态 zip 样本分组方式（module2-api-doc §6.1） */
export type MultimodalSampleGrouping = 'MANIFEST' | 'AUTO_DIRECTORY';

/** 初始化数据集分片上传时需要提交的元信息。 */
export type DatasetUploadInitParams = {
  fileName: string;
  fileSize: number;
  fileFingerprint?: string;
  assetId?: string;
  datasetName: string;
  version?: string;
  versionLabel?: string;
  type: DatasetType;
  cvTaskType?: CvTaskType;
  annotationFormat?: AnnotationFormat;
  remark?: string;
  description?: string;
  sampleGrouping?: MultimodalSampleGrouping;
  manifestPath?: string;
  /** MANIFEST 严格模式：未声明 entry 导致 ImportJob 失败 */
  strictManifest?: boolean;
};

/**
 * 数据集上传进度。
 *
 * uploadedPartIndexes 是断点续传依据；前端应按该数组跳过已完成分片。
 */
export type DatasetUploadProgress = {
  uploadId: string;
  status: 'UPLOADING' | 'COMPLETED' | string;
  fileName: string;
  fileSize: number;
  chunkSize: number;
  totalChunks: number;
  uploadedChunks: number;
  uploadedBytes: number;
  uploadedPartIndexes: number[];
  storagePath?: string;
  assetId?: string;
  versionId?: string;
  artifactSpecId?: string;
  createdAt?: string;
  updatedAt?: string;
};

/** 上传兼容回执；上传完成、导入完成和版本可用是不同状态，不能混用。 */
export type DatasetUploadCompleteResult = {
  uploadId: string;
  id: string;
  datasetVersionId?: string;
  assetId: string;
  name: string;
  version: string;
  type?: DatasetType;
  remark?: string;
  fileName: string;
  storagePath?: string;
  sizeBytes?: number;
  artifactSpecId?: string;
  status: string;
  uploadStatus?: string;
  versionStatus?: 'DRAFT' | 'READY' | string;
  importJobId?: string | null;
  importStatus?: string | null;
  createdAt?: string;
  updatedAt?: string;
};

/** CV 图片文件夹上传参数；后端会校验 paths 并打包为 zip 后入库。 */
export type DatasetFolderUploadParams = {
  datasetName: string;
  version?: string;
  type: 'CV';
  cvTaskType?: CvTaskType;
  annotationFormat?: AnnotationFormat;
  remark?: string;
  files: File[];
  paths: string[];
};

/** 删除数据集资产时，后端会同时返回删除的版本数和对象数。 */
export type DatasetDeleteResult = {
  id: string;
  deletedVersions: number;
  deletedObjects: number;
};

export type DatasetVersionLifecycleStatus = 'READY' | 'DEPRECATED' | 'ARCHIVED';

export type UploadDatasetCompatParams = {
  name: string;
  files: File[];
  type?: DatasetType;
  version?: string;
  assetId?: string;
  cvTaskType?: CvTaskType;
  annotationFormat?: AnnotationFormat;
  remark?: string;
  sampleGrouping?: MultimodalSampleGrouping;
  manifestPath?: string;
  /** MANIFEST 严格模式：未声明 entry 导致 ImportJob 失败 */
  strictManifest?: boolean;
  /** 与 backend-api 一致；不传则按「文件名|大小|数据集名|版本|类型」自动生成稳定指纹 */
  fileFingerprint?: string;
  /** 单文件分片上传时进度 0–100 */
  onProgress?: (percent: number) => void;
  /** 服务端合并分片（COMPLETING）阶段回调 */
  onMergeStatus?: (status: string) => void;
  /** init 成功后回调，便于页面写入 localStorage 做刷新续传提示 */
  onUploadSession?: (payload: {
    uploadId: string;
    fileFingerprint: string;
  }) => void;
};
