import { request } from '@umijs/max';
import { downloadAuthFile } from '@/utils/authFileDownload';

export type TrainingPlanCategory = 'CV' | 'NLP' | 'OTHER';
export type TrainingPlanSource = 'BUILT_IN' | 'ONLINE';
export type TrainingPlanStatus = 'ACTIVE' | 'DISABLED';

export interface TrainingPlanIssue {
  code: string;
  path?: string | null;
  message: string;
}

export interface TrainingPlanChange {
  section: string;
  changeType: string;
  riskLevel: string;
}

export interface TrainingPlanReference {
  planId: string;
  planVersion: string;
  source: TrainingPlanSource;
  status: TrainingPlanStatus;
  sha256?: string | null;
}

export interface TrainingPlanResourceProfile {
  id: string;
  cpuRequest?: string | null;
  cpuLimit?: string | null;
  memoryRequest?: string | null;
  memoryLimit?: string | null;
  ephemeralStorageLimit?: string | null;
  gpuCount?: number | null;
  nodeSelector?: Record<string, string> | null;
}

export interface TrainingPlanDefinition {
  schemaVersion: string;
  id: string;
  version: string;
  displayName: string;
  description?: string | null;
  category?: TrainingPlanCategory | null;
  enabled?: boolean | null;
  unavailableReason?: string | null;
  trainingModes?: string[] | null;
  execution?: {
    interpreter?: string | null;
    entrypoint?: string | null;
    arguments?: string[] | null;
  } | null;
  inputs?: {
    model?: {
      required?: boolean | null;
      consumed?: boolean | null;
      acceptedSpecIds?: string[] | null;
    } | null;
    dataset?: {
      required?: boolean | null;
      acceptedSpecIds?: string[] | null;
    } | null;
    code?: {
      required?: boolean | null;
      approvalRequired?: boolean | null;
      runtime?: string | null;
    } | null;
  } | null;
  parameters?: Array<{
    name: string;
    displayName?: string | null;
    description?: string | null;
    type: string;
    required?: boolean | null;
    defaultValue?: unknown;
    minimum?: number | null;
    maximum?: number | null;
    allowedValues?: unknown[] | null;
  }> | null;
  runtimes?: Array<{
    id: string;
    deviceType: string;
    image?: string | null;
    imagePullPolicy?: string | null;
    productionDigestRequired?: boolean | null;
    resourceProfiles?: TrainingPlanResourceProfile[] | null;
  }> | null;
  outputs?: {
    progressProtocol?: string | null;
    metricsPath?: string | null;
    logPath?: string | null;
    artifacts?: Array<{
      path: string;
      role: string;
      required?: boolean | null;
      format?: string | null;
      publishAsModel?: boolean | null;
      publishedModelSpecId?: string | null;
    }> | null;
  } | null;
  security?: {
    networkPolicy?: string | null;
    runAsNonRoot?: boolean | null;
    allowPrivilegeEscalation?: boolean | null;
    automountServiceAccountToken?: boolean | null;
    maxRuntimeSeconds?: number | null;
  } | null;
}

export interface TrainingPlanPreview {
  sha256: string;
  publishable: boolean;
  definition?: TrainingPlanDefinition | null;
  currentActive?: TrainingPlanReference | null;
  issues: TrainingPlanIssue[];
  warnings: TrainingPlanIssue[];
  changes: TrainingPlanChange[];
}

export interface TrainingPlanSummary {
  recordId?: number | null;
  source: TrainingPlanSource;
  status: TrainingPlanStatus;
  planId: string;
  planVersion: string;
  schemaVersion: string;
  category?: TrainingPlanCategory | null;
  displayName: string;
  sha256?: string | null;
  importedByUserId?: number | null;
  importedAt?: string | null;
  publishedByUserId?: number | null;
  publishedAt?: string | null;
  disabledByUserId?: number | null;
  disabledAt?: string | null;
}

export interface TrainingPlanDetail {
  summary: TrainingPlanSummary;
  definition: TrainingPlanDefinition;
  yamlContent: string;
}

export interface TrainingPlanRequestOptions {
  skipErrorHandler?: boolean;
}

function yamlForm(file: File): FormData {
  const formData = new FormData();
  formData.append('file', file, file.name);
  return formData;
}

const multipartHeaders = {
  // 浏览器必须自行生成包含 boundary 的 Content-Type。
  'Content-Type': undefined as unknown as string,
};

export async function fetchAdminTrainingPlans(
  options?: TrainingPlanRequestOptions,
) {
  return request<TrainingPlanSummary[]>('/admin/training-plans', {
    method: 'GET',
    ...(options || {}),
  });
}

export async function fetchAdminTrainingPlan(
  planId: string,
  version: string,
  options?: TrainingPlanRequestOptions,
) {
  return request<TrainingPlanDetail>(
    `/admin/training-plans/${encodeURIComponent(planId)}/${encodeURIComponent(version)}`,
    { method: 'GET', ...(options || {}) },
  );
}

export async function previewTrainingPlanYaml(
  file: File,
  options?: TrainingPlanRequestOptions,
) {
  return request<TrainingPlanPreview>('/admin/training-plans/preview', {
    method: 'POST',
    data: yamlForm(file),
    headers: multipartHeaders,
    ...(options || {}),
  });
}

export async function publishTrainingPlanYaml(
  file: File,
  expectedSha256: string,
  options?: TrainingPlanRequestOptions,
) {
  return request<TrainingPlanDetail>('/admin/training-plans/publish', {
    method: 'POST',
    params: { expectedSha256 },
    data: yamlForm(file),
    headers: multipartHeaders,
    ...(options || {}),
  });
}

export async function disableTrainingPlan(
  planId: string,
  version: string,
  options?: TrainingPlanRequestOptions,
) {
  return request<TrainingPlanDetail>(
    `/admin/training-plans/${encodeURIComponent(planId)}/${encodeURIComponent(version)}/disable`,
    { method: 'POST', ...(options || {}) },
  );
}

export async function downloadCvCpuTrainingPlanTemplate(
  _options?: TrainingPlanRequestOptions,
) {
  return downloadAuthFile({
    url: '/admin/training-plans/templates/cv-cpu-v2',
    fileName: 'training-plan-cv-cpu-v2.yaml',
  });
}
