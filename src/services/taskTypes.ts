/** 训练请求契约：字段对应后端 DTO，页面负责选择和校验，服务层不补业务默认值。 */
export type TrainingResourceRequest = {
  hardwareTargetId?: string;
  cpuCores?: number;
  /** 内存及显存预算均使用 MiB；显存预算不等于集群硬隔离。 */
  memoryMiB?: number;
  gpuCount?: number;
  gpuMemoryLimitMiB?: number;
};

/** 新建和续训共用的字段；续训省略字段时沿用后端现有继承规则。 */
type TrainingVersionFields = {
  /** 同次提交与超时重试共用；主动新建训练时更换。 */
  submissionKey?: string;
  name?: string;
  /** API 别名，后端落到 modelVersionId。 */
  baseModelVersionId?: string;
  modelVersionId?: string;
  codeVersionId?: string;
  datasetVersionId?: string;
  planId?: string;
  planVersion?: string;
  trainingMode?: string;
  resourceProfileId?: string;
  resourceRequest?: TrainingResourceRequest;
  hyperParams?: Record<string, unknown> | string;
  remark?: string;
};

export type CreateTaskRequest = TrainingVersionFields & {
  datasetVersionId: string;
  trainingProfile?: string;
};

export type CreateExperimentVersionRequest = TrainingVersionFields;
