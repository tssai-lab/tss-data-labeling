import { request } from '@umijs/max';

export type TrainingPlan = {
  schemaVersion?: string;
  id: string;
  version: string;
  displayName: string;
  description?: string;
  category?: 'CV' | 'NLP' | 'OTHER';
  enabled: boolean;
  trainingModes: string[];
  execution: {
    interpreter: 'python' | 'python3';
    entrypoint: string;
    arguments: string[];
  };
  inputs: {
    model: {
      taskTypes?: string[];
      acceptedSpecIds?: string[];
      requiredEntries?: string[];
      formats?: string[];
      formatGuide?: string;
    };
    dataset: {
      taskTypes?: string[];
      acceptedSpecIds?: string[];
      cvTaskTypes?: string[];
      annotationFormats?: string[];
      requiredEntries?: string[];
      formatGuide?: string;
    };
    code?: {
      required: boolean;
      approvalRequired?: boolean;
      runtime?: string;
    };
  };
  parameters?: Array<{
    name: string;
    displayName?: string;
    description?: string;
    type: string;
    required?: boolean;
    defaultValue?: unknown;
    minimum?: number;
    maximum?: number;
    allowedValues?: unknown[];
  }>;
  runtimes: Array<{
    id: string;
    deviceType: 'CPU' | 'NVIDIA_GPU';
    resourceProfiles: Array<{
      id: string;
      cpuRequest: string;
      cpuLimit: string;
      memoryRequest: string;
      memoryLimit: string;
      ephemeralStorageLimit: string;
      gpuCount: number;
      nodeSelector?: Record<string, string>;
    }>;
  }>;
};

export type TrainingResourceCapability = {
  planId: string;
  planVersion: string;
  resourceProfileId: string;
  deviceType: 'CPU' | 'NVIDIA_GPU';
  cpu: { requestCores: number; limitCores: number };
  memory: { requestMiB: number; limitMiB: number };
  gpuCount: number;
  eligibleNodeCount: number;
  capacityAvailable: boolean;
  gpu?: {
    models: string[];
    observedGpuCount: number;
    safeTotalMemoryMiB?: number;
    maxFreeMemoryMiB?: number;
    metricsComplete: boolean;
  };
  dataStatus: 'AVAILABLE' | 'PARTIAL' | 'UNAVAILABLE';
  observedAt?: string;
  message: string;
};

export type TrainingHardwareOption = {
  hardwareTargetId: string;
  displayName: string;
  resourceProfileId: string;
  deviceType: 'CPU' | 'NVIDIA_GPU';
  cpu: { requestCores: number; limitCores: number };
  memory: { requestMiB: number; limitMiB: number };
  gpuCount: number;
  eligibleNodeCount: number;
  gpu?: {
    model: string;
    observedGpuCount: number;
    safeTotalMemoryMiB?: number;
    maxFreeMemoryMiB?: number;
    metricsComplete: boolean;
    hostGpuIndex?: string;
    uuid?: string;
    nodeName?: string;
    utilizationRate?: number;
    temperatureCelsius?: number;
  };
  dataStatus: 'AVAILABLE' | 'PARTIAL' | 'UNAVAILABLE';
  observedAt?: string;
  message: string;
};

/** 后端是可选训练方案的唯一可信来源。 */
export async function fetchTrainingPlans(options?: { [key: string]: unknown }) {
  return request<{ success: boolean; data: TrainingPlan[]; errorMessage?: string }>(
    '/training-plans',
    { method: 'GET', ...(options || {}) },
  );
}

export async function fetchTrainingResourceCapability(
  planId: string,
  params: { version?: string; resourceProfileId: string },
  options?: { [key: string]: unknown },
) {
  return request<{
    success: boolean;
    data: TrainingResourceCapability;
    errorMessage?: string;
  }>(`/training-plans/${encodeURIComponent(planId)}/resource-capabilities`, {
    method: 'GET',
    params,
    ...(options || {}),
  });
}

export async function fetchTrainingHardwareOptions(
  planId: string,
  params: { version?: string },
  options?: { [key: string]: unknown },
) {
  return request<{
    success: boolean;
    data: TrainingHardwareOption[];
    errorMessage?: string;
  }>(`/training-plans/${encodeURIComponent(planId)}/hardware-options`, {
    method: 'GET',
    params,
    ...(options || {}),
  });
}
