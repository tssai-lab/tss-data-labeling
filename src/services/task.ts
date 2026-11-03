/**
 * 任务模块 - Services 层
 * 封装训练任务相关接口，供 Page 层调用
 */
import { request } from '@umijs/max';
import { API_CONFIG } from '@/constants/platform';
import type { CreateTaskRequest, CreateExperimentVersionRequest } from './taskTypes';
export type { CreateTaskRequest, CreateExperimentVersionRequest, TrainingResourceRequest } from './taskTypes';

/** 获取任务列表 */
export async function fetchTaskList(options?: {
  current?: number;
  pageSize?: number;
  status?: string;
  name?: string;
  experimentId?: string;
  skipErrorHandler?: boolean;
  [key: string]: unknown;
}) {
  const { current, pageSize, status, name, experimentId, ...rest } = options || {};
  return request<{ success: boolean; data: { data: API.TaskItem[]; total: number }; errorMessage?: string }>(
    API_CONFIG.ENDPOINTS.TASK_LIST,
    {
      method: 'GET',
      params: { current, pageSize, status, name, experimentId },
      ...rest,
    },
  );
}

/** 获取任务详情 */
export async function fetchTaskDetail(id: string, options?: { [key: string]: unknown }) {
  return request<{ success: boolean; data: API.TrainingExperimentVersion; errorMessage?: string }>(
    API_CONFIG.ENDPOINTS.TASK_DETAIL,
    {
      method: 'GET',
      params: { id },
      ...(options || {}),
    },
  );
}

/** 历史图文一致性方案标识；真实代码和数据集版本仍由用户选择，不绑定种子资产。 */
export const CONSISTENCY_TRAINING_PROFILE = 'image_text_consistency_fusion_logreg';

/** 发起训练任务（会自动生成 experimentId，并创建 versionNo=1） */
export async function createTask(
  params: CreateTaskRequest,
  options?: { [key: string]: unknown },
) {
  return request<{ success: boolean; data: API.TrainingExperimentVersion; errorMessage?: string }>(
    API_CONFIG.ENDPOINTS.TASK_CREATE,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      data: params,
      ...(options || {}),
    },
  );
}

/** 使用上传得到的 code/dataset 版本发起 profile 训练 */
export async function createProfileTrainingTask(
  params: {
    name?: string;
    codeVersionId: string;
    datasetVersionId: string;
    trainingProfile?: string;
    hyperParams?: Record<string, unknown>;
    remark?: string;
  },
  options?: { [key: string]: unknown },
) {
  return createTask(
    {
      name: params.name,
      trainingProfile: params.trainingProfile || CONSISTENCY_TRAINING_PROFILE,
      codeVersionId: params.codeVersionId,
      datasetVersionId: params.datasetVersionId,
      hyperParams: params.hyperParams ?? {},
      remark: params.remark,
    },
    options,
  );
}

/** 按 experimentId 查看历史版本 */
export async function listExperimentVersions(experimentId: string, options?: { [key: string]: unknown }) {
  return request<{ success: boolean; data: API.TrainingExperimentVersion[]; errorMessage?: string }>(
    API_CONFIG.ENDPOINTS.EXPERIMENT_VERSIONS(experimentId),
    { method: 'GET', ...(options || {}) },
  );
}

/** 查询指定实验版本详情 */
export async function getExperimentVersion(
  experimentId: string,
  versionNo: number,
  options?: { [key: string]: unknown },
) {
  return request<{ success: boolean; data: API.TrainingExperimentVersion; errorMessage?: string }>(
    API_CONFIG.ENDPOINTS.EXPERIMENT_VERSION_DETAIL(experimentId, versionNo),
    { method: 'GET', ...(options || {}) },
  );
}

/** 修改指定版本的超参数 */
export async function updateExperimentHyperParams(
  experimentId: string,
  versionNo: number,
  body: { hyperParams: Record<string, unknown> | string; remark?: string },
  options?: { [key: string]: unknown },
) {
  return request<{ success: boolean; data: API.TrainingExperimentVersion; errorMessage?: string }>(
    API_CONFIG.ENDPOINTS.EXPERIMENT_HYPER_PARAMS_UPDATE(experimentId, versionNo),
    {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      data: body,
      ...(options || {}),
    },
  );
}

/** 创建实验新版本（versionNo 自动递增，不传字段继承最新版本） */
export async function createExperimentVersion(
  experimentId: string,
  body: CreateExperimentVersionRequest,
  options?: { [key: string]: unknown },
) {
  return request<{ success: boolean; data: API.TrainingExperimentVersion; errorMessage?: string }>(
    API_CONFIG.ENDPOINTS.EXPERIMENT_VERSION_CREATE(experimentId),
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      data: body,
      ...(options || {}),
    },
  );
}

/** 终止任务 */
export async function stopTask(id: string, options?: { [key: string]: unknown }) {
  return request<{ success: boolean; data: API.TrainingExperimentVersion; errorMessage?: string }>(
    API_CONFIG.ENDPOINTS.TASK_STOP,
    {
      method: 'POST',
      params: { id },
      ...(options || {}),
    },
  );
}

/** 将成功训练的核心产物发布为标准模型版本；重复调用保持幂等 */
export async function publishTrainingModel(
  id: string,
  options?: { [key: string]: unknown },
) {
  return request<{
    success: boolean;
    data: API.TrainingExperimentVersion;
    errorMessage?: string;
  }>(`/task/${encodeURIComponent(id)}/publish-model`, {
    method: 'POST',
    ...(options || {}),
  });
}

/** 删除任务 */
export async function deleteTask(id: string, options?: { [key: string]: unknown }) {
  return request<{ success: boolean; data: unknown; errorMessage?: string }>(
    API_CONFIG.ENDPOINTS.TASK_DELETE,
    {
      method: 'DELETE',
      params: { id },
      ...(options || {}),
    },
  );
}

/** 将训练产物发布为结果模型版本，返回更新后的实验版本（含 producedModelVersionId） */
export async function publishTaskModel(
  id: string,
  options?: { [key: string]: unknown },
) {
  return request<{
    success: boolean;
    data: API.TrainingExperimentVersion;
    errorMessage?: string;
  }>(API_CONFIG.ENDPOINTS.TASK_PUBLISH_MODEL(id), {
    method: 'POST',
    ...(options || {}),
  });
}
