import { history, useSearchParams } from '@umijs/max';
import { Alert, Form, Modal, message } from 'antd';
import type { UploadFile } from 'antd/es/upload/interface';
import React, { useEffect, useMemo, useRef, useState } from 'react';
import { usePageTour } from '@/components/Guide/usePageTour';
import { isTrainingCodeAutoApproveEnabled } from '@/constants/trainingCode';
import type { DatasetType } from '@/services/dataset';
import {
  autoApproveCodeVersionIfEnabled,
  CONSISTENCY_TRAINING_PROFILE,
  checkCodeVersionForTraining,
  createExperimentVersion,
  createTask,
  fetchAllDatasetList,
  fetchAllModelList,
  fetchApprovedCodeVersions,
  fetchTaskDetail,
  fetchTrainingDatasetCandidates,
  fetchTrainingHardwareOptions,
  fetchTrainingModelCandidates,
  fetchTrainingPlans,
  getCodeVersionDetail,
  getModelVersion,
  publishTaskModel,
  uploadCodeZip,
} from '@/services/platform';
import type {
  TrainingHardwareOption,
  TrainingPlan,
} from '@/services/trainingPlans';
import { getApiErrorMessage } from '@/utils/apiError';
import { markPendingCodeStatus } from '@/utils/pendingCodeVersions';
import { type CheckState, isCodeApproved } from './createPresentation';
import {
  firstTrainingHardwareOption,
  isTrainingHardwareTargetAllowed,
} from './hardwareOptionPresentation.mjs';
import {
  firstTrainingResourceProfileId,
  isTrainingResourceProfileIdAllowed,
  listTrainingResourceProfiles,
} from './resourceProfilePresentation.mjs';
import {
  filterDatasetCandidates,
  filterModelCandidates,
  isSpecDrivenInput,
} from './trainingAssetCompatibility.mjs';
import { buildTrainingPlanHyperParams } from './trainingPlanDefaults.mjs';
import { createTrainingSubmission } from './trainingSubmission';
import {
  buildTrainingResourceRequest,
  resourceStatusPresentation,
} from './trainingResourcePresentation.mjs';
/** 向导状态与提交编排：保留原调用顺序，展示步骤不直接发起写请求。 */
export function useTrainingCreate() {
  // 引导段 S3：发起训练讲解（讲解 + 用户自点向导）
  const tourProps = usePageTour(3);
  const [searchParams] = useSearchParams();
  const experimentId = searchParams.get('experimentId')?.trim() || '';
  const fromVersionId = searchParams.get('fromVersionId')?.trim() || '';
  const presetCodeVersionId = searchParams.get('codeVersionId')?.trim() || '';
  const presetBaseModelVersionId =
    searchParams.get('baseModelVersionId')?.trim() ||
    searchParams.get('modelVersionId')?.trim() ||
    '';
  const fromSource = searchParams.get('from')?.trim() || '';
  const fromModelAssetId = searchParams.get('assetId')?.trim() || '';
  const isExperimentContinue = !!experimentId;
  const backFromModelDetail = fromSource === 'model' && !!fromModelAssetId;

  const [form] = Form.useForm();
  const selectedHardwareTargetId = Form.useWatch('hardwareTargetId', form);
  const selectedResourceProfileId = Form.useWatch('resourceProfileId', form);
  const resourceMode = Form.useWatch('resourceMode', form) || 'recommended';
  const [currentStep, setCurrentStep] = useState(0);
  const submission = useRef(createTrainingSubmission());
  const [submitting, setSubmitting] = useState(false);

  const [modelOptions, setModelOptions] = useState<API.ModelItem[]>([]);
  const [datasetOptions, setDatasetOptions] = useState<API.DatasetItem[]>([]);
  const [codeOptions, setCodeOptions] = useState<any[]>([]);
  const [trainingPlans, setTrainingPlans] = useState<TrainingPlan[]>([]);
  const [hardwareOptions, setHardwareOptions] = useState<
    TrainingHardwareOption[]
  >([]);
  const [hardwareOptionsLoading, setHardwareOptionsLoading] = useState(false);
  const [hardwareOptionsError, setHardwareOptionsError] = useState<string>();
  const [selectedTrainingPlanId, setSelectedTrainingPlanId] =
    useState<string>();

  const [modelLoading, setModelLoading] = useState(false);
  const [datasetLoading, setDatasetLoading] = useState(false);
  const modelLoadSequence = useRef(0);
  const datasetLoadSequence = useRef(0);
  const [codeLoading, setCodeLoading] = useState(false);

  const [codeInputMode, setCodeInputMode] = useState<'select' | 'upload'>(
    'select',
  );

  const [selectedBaseModelVersionId, setSelectedBaseModelVersionId] =
    useState<string>();
  const [selectedDatasetVersionId, setSelectedDatasetVersionId] =
    useState<string>();
  const [selectedCodeVersionId, setSelectedCodeVersionId] = useState<string>();
  const [selectedCodeApprovalStatus, setSelectedCodeApprovalStatus] =
    useState<string>();

  const [codeUploading, setCodeUploading] = useState(false);

  const [codeCheck, setCodeCheck] = useState<CheckState>({ loading: false });

  const selectedModel = useMemo(
    () => modelOptions.find((item) => item.id === selectedBaseModelVersionId),
    [modelOptions, selectedBaseModelVersionId],
  );

  /** 确保已选结果模型始终出现在下拉选项中（避免列表刷新后选项被冲掉导致显示空白） */
  const modelSelectOptions = useMemo(() => {
    if (
      !selectedBaseModelVersionId ||
      modelOptions.some((item) => item.id === selectedBaseModelVersionId)
    ) {
      return modelOptions;
    }
    return [
      {
        id: selectedBaseModelVersionId,
        name: `结果模型 ${selectedBaseModelVersionId.slice(0, 8)}…`,
        version: '-',
        type: (selectedModel?.type || 'NLP') as API.ModelItem['type'],
      } as API.ModelItem,
      ...modelOptions,
    ];
  }, [modelOptions, selectedBaseModelVersionId, selectedModel?.type]);

  const selectedTrainingPlan = useMemo(
    () => trainingPlans.find((plan) => plan.id === selectedTrainingPlanId),
    [selectedTrainingPlanId, trainingPlans],
  );
  const specDrivenModel = isSpecDrivenInput(
    selectedTrainingPlan?.inputs?.model,
  );
  const acceptedModelSpecIds =
    selectedTrainingPlan?.inputs?.model?.acceptedSpecIds ?? [];

  const filteredModelSelectOptions = useMemo(() => {
    return filterModelCandidates(
      modelSelectOptions,
      selectedTrainingPlan?.inputs?.model,
    );
  }, [modelSelectOptions, selectedTrainingPlan]);

  /** v2 按规格筛选；旧方案继续沿用单一任务类型或模型类型约束。 */
  const planDatasetTypes =
    selectedTrainingPlan?.inputs?.dataset?.taskTypes ?? [];
  const specDrivenDataset = isSpecDrivenInput(
    selectedTrainingPlan?.inputs?.dataset,
  );
  const acceptedDatasetSpecIds =
    selectedTrainingPlan?.inputs?.dataset?.acceptedSpecIds ?? [];
  const requiredDatasetType = (
    specDrivenDataset
      ? undefined
      : planDatasetTypes.length === 1
        ? planDatasetTypes[0]
        : (
            selectedModel ||
            modelSelectOptions.find(
              (item) => item.id === selectedBaseModelVersionId,
            )
          )?.type
  ) as DatasetType | undefined;
  const datasetSelectionReady = Boolean(
    selectedTrainingPlan && selectedBaseModelVersionId,
  );

  const filteredDatasetOptions = useMemo(() => {
    const compatible = filterDatasetCandidates(
      datasetOptions,
      selectedTrainingPlan?.inputs?.dataset,
    );
    if (specDrivenDataset) return compatible;
    if (!requiredDatasetType) {
      return compatible.filter((dataset) => dataset.type !== 'MULTIMODAL');
    }
    return compatible.filter((dataset) => dataset.type === requiredDatasetType);
  }, [
    datasetOptions,
    requiredDatasetType,
    selectedTrainingPlan,
    specDrivenDataset,
  ]);

  const filteredCodeOptions = useMemo(
    () =>
      codeOptions.filter(
        (code) =>
          !selectedTrainingPlanId ||
          code.trainingProfile === selectedTrainingPlanId,
      ),
    [codeOptions, selectedTrainingPlanId],
  );

  const resourceProfiles = useMemo(
    () => listTrainingResourceProfiles(selectedTrainingPlan),
    [selectedTrainingPlan],
  );
  const selectedResourceProfile = resourceProfiles.find(
    (profile) =>
      profile.id ===
      (selectedResourceProfileId || form.getFieldValue('resourceProfileId')),
  );
  const selectedHardwareOption = hardwareOptions.find(
    (option) =>
      option.hardwareTargetId ===
      (selectedHardwareTargetId || form.getFieldValue('hardwareTargetId')),
  );
  const resourceStatus = resourceStatusPresentation(
    selectedHardwareOption,
    hardwareOptionsError,
  );

  useEffect(() => {
    if (!selectedTrainingPlan) {
      setHardwareOptions([]);
      setHardwareOptionsError(undefined);
      return;
    }
    let cancelled = false;
    setHardwareOptionsLoading(true);
    setHardwareOptions([]);
    setHardwareOptionsError(undefined);
    fetchTrainingHardwareOptions(
      selectedTrainingPlan.id,
      {
        version: selectedTrainingPlan.version,
      },
      { skipErrorHandler: true },
    )
      .then((response) => {
        if (cancelled) return;
        if (!response?.success || !response.data) {
          throw new Error(response?.errorMessage || '硬件型号接口返回失败');
        }
        const options = response.data;
        setHardwareOptions(options);
        if (!options.length) {
          setHardwareOptionsError(
            '当前没有与训练方案兼容且通过检测的可训练硬件',
          );
          form.setFieldsValue({
            hardwareTargetId: undefined,
            resourceProfileId: undefined,
          });
          return;
        }
        const currentTargetId = form.getFieldValue('hardwareTargetId');
        const preferredProfileId = form.getFieldValue('resourceProfileId');
        const selected =
          options.find(
            (option) => option.hardwareTargetId === currentTargetId,
          ) || firstTrainingHardwareOption(options, preferredProfileId);
        form.setFieldsValue({
          hardwareTargetId: selected?.hardwareTargetId,
          resourceProfileId: selected?.resourceProfileId,
          resourceMode: 'recommended',
          cpuCores: undefined,
          memoryMiB: undefined,
          gpuMemoryLimitMiB: undefined,
        });
      })
      .catch((error) => {
        if (!cancelled) {
          setHardwareOptions([]);
          setHardwareOptionsError(getApiErrorMessage(error));
        }
      })
      .finally(() => {
        if (!cancelled) setHardwareOptionsLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [form, selectedTrainingPlan]);

  useEffect(() => {
    if (!selectedTrainingPlan || !selectedDatasetVersionId) return;
    const dataset = datasetOptions.find(
      (item) => item.versionId === selectedDatasetVersionId,
    );
    if (
      dataset &&
      !filteredDatasetOptions.some(
        (item) => item.versionId === selectedDatasetVersionId,
      )
    ) {
      setSelectedDatasetVersionId(undefined);
      form.setFieldValue('datasetVersionId', undefined);
    }
  }, [
    datasetOptions,
    filteredDatasetOptions,
    form,
    selectedDatasetVersionId,
    selectedTrainingPlan,
  ]);

  const reloadModelOptions = (artifactSpecIds?: string[]) => {
    const sequence = ++modelLoadSequence.current;
    setModelLoading(true);
    const request = artifactSpecIds
      ? fetchTrainingModelCandidates(artifactSpecIds)
      : fetchAllModelList();
    return request
      .then((res: any) => {
        if (sequence !== modelLoadSequence.current) return;
        const list = (res?.data ?? []).filter((item: API.ModelItem) => item.id);
        setModelOptions(list);
      })
      .catch((error: any) => {
        if (sequence !== modelLoadSequence.current) return;
        setModelOptions([]);
        message.error(error?.message || '基础模型权重列表加载失败');
      })
      .finally(() => {
        if (sequence === modelLoadSequence.current) setModelLoading(false);
      });
  };

  const reloadCodeOptions = () => {
    setCodeLoading(true);
    return fetchApprovedCodeVersions({ skipErrorHandler: true })
      .then((res: any) => {
        if (!res?.success) {
          message.error(res?.errorMessage || '训练代码版本列表加载失败');
          setCodeOptions([]);
          return;
        }
        setCodeOptions(res.data ?? []);
      })
      .catch((error: any) => {
        setCodeOptions([]);
        message.error(error?.message || '训练代码版本列表加载失败');
      })
      .finally(() => setCodeLoading(false));
  };

  const reloadDatasetOptions = (artifactSpecIds?: string[]) => {
    const sequence = ++datasetLoadSequence.current;
    setDatasetLoading(true);
    const request = artifactSpecIds
      ? fetchTrainingDatasetCandidates(artifactSpecIds)
      : fetchAllDatasetList();
    return request
      .then((res) => {
        if (sequence !== datasetLoadSequence.current) return;
        const list = (res?.data ?? []).filter(
          (item: API.DatasetItem) => item.versionId,
        );
        setDatasetOptions(list ?? []);
      })
      .catch((error: any) => {
        if (sequence !== datasetLoadSequence.current) return;
        setDatasetOptions([]);
        message.error(error?.message || '数据集版本列表加载失败');
      })
      .finally(() => {
        if (sequence === datasetLoadSequence.current) setDatasetLoading(false);
      });
  };

  useEffect(() => {
    reloadModelOptions();
    reloadCodeOptions();
    reloadDatasetOptions();
    fetchTrainingPlans({ skipErrorHandler: true })
      .then((res) => {
        const plans = (res?.data ?? []).filter((plan) => plan.enabled);
        setTrainingPlans(plans);
        if (!plans.length) {
          message.error('没有可用训练方案，请检查后端 training-plans 配置');
        }
      })
      .catch((error: any) =>
        message.error(error?.message || '训练方案加载失败'),
      );
  }, []);

  useEffect(() => {
    if (!selectedTrainingPlan) return;
    const modelSpecs = selectedTrainingPlan.inputs?.model?.acceptedSpecIds;
    const datasetSpecs = selectedTrainingPlan.inputs?.dataset?.acceptedSpecIds;
    void reloadModelOptions(Array.isArray(modelSpecs) ? modelSpecs : undefined);
    void reloadDatasetOptions(
      Array.isArray(datasetSpecs) ? datasetSpecs : undefined,
    );
  }, [selectedTrainingPlan]);

  useEffect(() => {
    if (!selectedTrainingPlan || isExperimentContinue) return;
    form.setFieldsValue({
      planId: selectedTrainingPlan.id,
      planVersion: selectedTrainingPlan.version,
      trainingMode: selectedTrainingPlan.trainingModes?.[0],
      hardwareTargetId: undefined,
      resourceProfileId: firstTrainingResourceProfileId(selectedTrainingPlan),
      resourceMode: 'recommended',
      cpuCores: undefined,
      memoryMiB: undefined,
      gpuMemoryLimitMiB: undefined,
      hyperParams: buildTrainingPlanHyperParams(selectedTrainingPlan),
    });
  }, [form, isExperimentContinue, selectedTrainingPlan]);

  useEffect(() => {
    if (!isExperimentContinue) return;
    let cancelled = false;

    const ensureOption = (modelId: string, meta?: Partial<API.ModelItem>) => {
      setModelOptions((prev) => {
        if (prev.some((item) => item.id === modelId)) {
          if (!meta) return prev;
          return prev.map((item) =>
            item.id === modelId
              ? {
                  ...item,
                  ...meta,
                  id: item.id,
                  name: meta.name || item.name,
                  version: meta.version || item.version,
                  type: (meta.type as API.ModelItem['type']) || item.type,
                }
              : item,
          );
        }
        return [
          {
            name: meta?.name || `结果模型 ${modelId.slice(0, 8)}…`,
            version: meta?.version || '-',
            type: (meta?.type || 'NLP') as API.ModelItem['type'],
            ...meta,
            id: modelId,
          } as API.ModelItem,
          ...prev,
        ];
      });
    };

    const applyModelSelection = async (modelId: string) => {
      if (cancelled || !modelId) return;
      setSelectedBaseModelVersionId(modelId);
      form.setFieldsValue({ baseModelVersionId: modelId });
      ensureOption(modelId);
      try {
        const detailRes: any = await getModelVersion(modelId, {
          skipErrorHandler: true,
        });
        if (cancelled) return;
        const d = detailRes?.data;
        if (!d) return;
        ensureOption(modelId, {
          name: d.name || d.fileName || '结果模型',
          version: d.version || '-',
          type: d.type || 'NLP',
          status: d.status,
          artifactSpecId: d.artifactSpecId,
        });
      } catch {
        // keep placeholder option
      }
    };

    const resolveProducedModelId = async (
      data: API.TrainingExperimentVersion,
    ): Promise<string | undefined> => {
      if (data.producedModelVersionId) return data.producedModelVersionId;
      try {
        const raw = localStorage.getItem('taskCreatePrefill');
        const prefill = raw ? JSON.parse(raw) : null;
        if (
          prefill?.producedModelVersionId &&
          (!prefill.fromVersionId || prefill.fromVersionId === data.id)
        ) {
          return String(prefill.producedModelVersionId);
        }
      } catch {
        // ignore
      }
      if (data.status !== 'success') return undefined;
      try {
        const pub = await publishTaskModel(data.id, {
          skipErrorHandler: true,
        });
        const produced =
          pub?.data?.producedModelVersionId ||
          (pub as any)?.data?.produced_model_version_id;
        if (produced) return String(produced);
        if (pub && pub.success === false) {
          message.warning(
            pub.errorMessage || '该版本结果模型尚未发布，请手动选择权重',
          );
        }
      } catch (error: any) {
        message.warning(
          error?.message || '自动发布结果模型失败，请手动选择权重',
        );
      }
      return undefined;
    };

    const load = async () => {
      try {
        // 先同步读 prefill，尽快占住选择（避免列表刷新把 UI 冲空）
        try {
          const raw = localStorage.getItem('taskCreatePrefill');
          const prefill = raw ? JSON.parse(raw) : null;
          const earlyId = prefill?.producedModelVersionId as string | undefined;
          if (
            earlyId &&
            (!fromVersionId ||
              !prefill?.fromVersionId ||
              prefill.fromVersionId === fromVersionId)
          ) {
            await applyModelSelection(earlyId);
          }
        } catch {
          // ignore
        }

        const detailId = fromVersionId || experimentId;
        const res: any = await fetchTaskDetail(detailId, {
          skipErrorHandler: true,
        });
        if (cancelled) return;
        let data = res?.data as API.TrainingExperimentVersion | undefined;
        if (!data) {
          message.warning('无法加载继续训练的源版本详情');
          return;
        }

        // 若只传了 experimentId，尽量对齐到指定版本
        if (!fromVersionId && data.experimentId) {
          try {
            const raw = localStorage.getItem('taskCreatePrefill');
            const prefill = raw ? JSON.parse(raw) : null;
            const wantId = prefill?.fromVersionId as string | undefined;
            if (wantId && wantId !== data.id) {
              const alt = await fetchTaskDetail(wantId, {
                skipErrorHandler: true,
              });
              if (alt?.data) data = alt.data as API.TrainingExperimentVersion;
            }
          } catch {
            // ignore prefill parse
          }
        }
        if (cancelled) return;

        const producedId = await resolveProducedModelId(data);
        if (cancelled) return;
        if (producedId) {
          await applyModelSelection(producedId);
        } else {
          // 上面 early prefill 可能已选中；此处用 DOM/状态外的标记更稳，避免闭包陈旧
          const stillEmpty = !form.getFieldValue('baseModelVersionId');
          if (stillEmpty) {
            message.warning(
              '该版本暂无结果模型（producedModelVersionId），请先在详情页发布结果模型，或手动选择权重',
            );
          }
        }

        if (data.codeVersionId) {
          setSelectedCodeVersionId(data.codeVersionId);
          setSelectedCodeApprovalStatus('APPROVED');
          form.setFieldValue('codeVersionId', data.codeVersionId);
        }
        if (data.trainingPlanId) {
          setSelectedTrainingPlanId(data.trainingPlanId);
          form.setFieldsValue({
            trainingProfile: data.trainingPlanId,
            planId: data.trainingPlanId,
            planVersion: data.trainingPlanVersion,
            trainingMode: data.trainingMode,
            hardwareTargetId: undefined,
            resourceProfileId: data.resourceProfileId,
          });
        }
        if (data.datasetVersionId) {
          setSelectedDatasetVersionId(data.datasetVersionId);
          form.setFieldValue('datasetVersionId', data.datasetVersionId);
        }
        if (data.name) {
          form.setFieldValue('name', `${data.name}-continue`);
        }
        if (data.hyperParams && typeof data.hyperParams === 'object') {
          form.setFieldValue(
            'hyperParams',
            JSON.stringify(data.hyperParams, null, 2),
          );
        }
        if (data.versionNo != null) {
          form.setFieldValue(
            'remark',
            `基于 v${data.versionNo} 结果模型继续训练`,
          );
        }
      } catch {
        // ignore prefill failure
      }
    };

    void load();
    return () => {
      cancelled = true;
    };
    // selectedBaseModelVersionId 仅用于 else 分支提示，不纳入依赖以免重复拉取
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [experimentId, form, fromVersionId, isExperimentContinue]);

  useEffect(() => {
    if (!presetCodeVersionId) return;
    setCodeInputMode('select');
    setSelectedCodeVersionId(presetCodeVersionId);
    setSelectedCodeApprovalStatus('APPROVED');
    form.setFieldValue('codeVersionId', presetCodeVersionId);

    let cancelled = false;
    void getCodeVersionDetail(presetCodeVersionId, {
      skipErrorHandler: true,
    })
      .then((res) => {
        if (cancelled || !res?.data) return;
        const detail = res.data;
        const profile = detail.trainingProfile?.trim();
        if (profile) {
          setSelectedTrainingPlanId(profile);
          form.setFieldValue('trainingProfile', profile);
        }
        setCodeOptions((prev) => {
          if (prev.some((item) => item.codeVersionId === presetCodeVersionId)) {
            return prev.map((item) =>
              item.codeVersionId === presetCodeVersionId
                ? { ...item, ...detail }
                : item,
            );
          }
          return [
            {
              ...detail,
              codeVersionId: presetCodeVersionId,
              codeAssetName:
                detail.codeAssetName || detail.codeName || presetCodeVersionId,
              approvalStatus: detail.approvalStatus || 'APPROVED',
            },
            ...prev,
          ];
        });
        if (String(detail.approvalStatus || '').toUpperCase() === 'APPROVED') {
          setSelectedCodeApprovalStatus('APPROVED');
        }
      })
      .catch(() => {
        // 预填失败时仍保留 URL 中的 codeVersionId 选中态
      });

    return () => {
      cancelled = true;
    };
  }, [form, presetCodeVersionId]);

  useEffect(() => {
    if (!presetCodeVersionId || !codeOptions.length) return;
    const found = codeOptions.find(
      (item) => item.codeVersionId === presetCodeVersionId,
    );
    const profile = found?.trainingProfile?.trim();
    if (!profile) return;
    setSelectedTrainingPlanId((prev) => prev || profile);
    if (!form.getFieldValue('trainingProfile')) {
      form.setFieldValue('trainingProfile', profile);
    }
  }, [codeOptions, form, presetCodeVersionId]);

  useEffect(() => {
    if (!presetBaseModelVersionId || isExperimentContinue) return;
    let cancelled = false;

    const ensureOption = (modelId: string, meta?: Partial<API.ModelItem>) => {
      setModelOptions((prev) => {
        if (prev.some((item) => item.id === modelId)) {
          if (!meta) return prev;
          return prev.map((item) =>
            item.id === modelId
              ? {
                  ...item,
                  ...meta,
                  id: item.id,
                  name: meta.name || item.name,
                  version: meta.version || item.version,
                  type: (meta.type as API.ModelItem['type']) || item.type,
                }
              : item,
          );
        }
        return [
          {
            name: meta?.name || `模型 ${modelId.slice(0, 8)}…`,
            version: meta?.version || '-',
            type: (meta?.type || 'NLP') as API.ModelItem['type'],
            ...meta,
            id: modelId,
          } as API.ModelItem,
          ...prev,
        ];
      });
    };

    setSelectedBaseModelVersionId(presetBaseModelVersionId);
    form.setFieldsValue({ baseModelVersionId: presetBaseModelVersionId });
    ensureOption(presetBaseModelVersionId);

    void getModelVersion(presetBaseModelVersionId, { skipErrorHandler: true })
      .then((detailRes: any) => {
        if (cancelled) return;
        const d = detailRes?.data;
        if (!d) return;
        ensureOption(presetBaseModelVersionId, {
          name: d.name || d.fileName || '基础模型权重',
          version: d.version || '-',
          type: d.type || 'NLP',
          status: d.status,
          artifactSpecId: d.artifactSpecId,
        });
      })
      .catch(() => {
        // keep placeholder option
      });

    return () => {
      cancelled = true;
    };
  }, [form, isExperimentContinue, presetBaseModelVersionId]);

  useEffect(() => {
    if (!selectedCodeVersionId) {
      setCodeCheck({ loading: false });
      return;
    }
    if (selectedCodeApprovalStatus === 'APPROVED') {
      setCodeCheck({
        loading: false,
        passed: true,
        approvalStatus: 'APPROVED',
      });
      return;
    }
    let cancelled = false;
    setCodeCheck({ loading: true });
    checkCodeVersionForTraining(
      selectedCodeVersionId,
      selectedTrainingPlanId || CONSISTENCY_TRAINING_PROFILE,
      { skipErrorHandler: true },
    )
      .then((res) => {
        if (cancelled) return;
        if (!res?.success) {
          setCodeCheck({
            loading: false,
            passed: false,
            reasons: [res?.errorMessage || '准入校验失败'],
          });
          return;
        }
        const d = res.data;
        setCodeCheck({
          loading: false,
          passed: d.passed,
          reasons: d.reasons || [],
          approvalStatus: d.approvalStatus,
          validationStatus: d.validationStatus,
        });
        if (d.approvalStatus) {
          setSelectedCodeApprovalStatus(d.approvalStatus);
        }
      })
      .catch((error: any) => {
        if (cancelled) return;
        setCodeCheck({
          loading: false,
          passed: false,
          reasons: [error?.message || '准入校验请求失败'],
        });
      });
    // 换代码、换方案或卸载后，不让上一轮的准入结论覆盖当前选择。
    return () => { cancelled = true; };
  }, [
    selectedCodeApprovalStatus,
    selectedCodeVersionId,
    selectedTrainingPlanId,
  ]);

  const selectedCode = useMemo(
    () =>
      codeOptions.find((item) => item.codeVersionId === selectedCodeVersionId),
    [codeOptions, selectedCodeVersionId],
  );

  const uploadTrainingCodeZip = async (values: {
    codeName: string;
    remark?: string;
    file: UploadFile[];
  }) => {
    const file = values.file?.[0]?.originFileObj as File | undefined;
    if (!file) {
      throw new Error('请选择训练代码 zip 文件');
    }
    setCodeUploading(true);
    try {
      const res = await uploadCodeZip(
        {
          file,
          codeName: values.codeName,
          trainingProfile:
            selectedTrainingPlanId || CONSISTENCY_TRAINING_PROFILE,
          remark: values.remark?.trim() || 'task/create 页面上传',
        },
        { skipErrorHandler: true },
      );
      if (res?.success === false) {
        throw new Error(res?.errorMessage || '训练代码上传失败');
      }
      const codeVersionId = res?.data?.codeVersionId;
      if (!codeVersionId) {
        throw new Error('训练代码上传成功，但未返回版本编号');
      }
      let approvalStatus = res?.data?.approvalStatus;
      if (approvalStatus === 'APPROVED') {
        message.success('训练代码已上传、保存到列表并审核通过');
      } else if (isTrainingCodeAutoApproveEnabled()) {
        try {
          const approved = await autoApproveCodeVersionIfEnabled(
            codeVersionId,
            {
              trainingProfile:
                selectedTrainingPlanId || CONSISTENCY_TRAINING_PROFILE,
              skipErrorHandler: true,
            },
          );
          approvalStatus = approved?.approvalStatus || 'APPROVED';
          message.success('训练代码已上传、保存到列表并自动审核通过');
        } catch (approveError: any) {
          message.warning(
            getApiErrorMessage(
              approveError,
              '上传成功，但审核状态未确认。请刷新训练代码列表查看；若仍不可用，请改选已审核版本或联系管理员。',
            ),
          );
        }
      } else {
        message.success('训练代码已上传并保存到列表，正在执行准入校验');
      }
      markPendingCodeStatus(codeVersionId, approvalStatus || 'PENDING');
      setSelectedCodeVersionId(codeVersionId);
      setSelectedCodeApprovalStatus(approvalStatus);
      form.setFieldValue('codeVersionId', codeVersionId);
      await reloadCodeOptions();
      setCodeInputMode('select');
    } finally {
      setCodeUploading(false);
    }
  };

  const renderCodeCheckAlert = () => {
    if (!selectedCodeVersionId) return null;
    if (codeCheck.loading) {
      return (
        <Alert
          type="info"
          showIcon
          style={{ marginTop: 12 }}
          message="正在执行训练代码准入校验…"
        />
      );
    }
    if (codeCheck.passed && isCodeApproved(codeCheck.approvalStatus)) {
      return (
        <Alert
          type="success"
          showIcon
          style={{ marginTop: 12 }}
          message="训练代码校验通过且已审核"
          description="训练代码已通过结构与固定入口校验，并已审核通过。"
        />
      );
    }
    if (codeCheck.passed && !isCodeApproved(codeCheck.approvalStatus)) {
      return (
        <Alert
          type="warning"
          showIcon
          style={{ marginTop: 12 }}
          message={
            isTrainingCodeAutoApproveEnabled()
              ? '训练代码校验通过，但尚未审核通过'
              : '训练代码校验通过，等待管理员审批'
          }
          description={
            isTrainingCodeAutoApproveEnabled()
              ? '训练代码尚未通过审核。可刷新后重试，或到训练代码页处理。'
              : '训练代码结构校验和人工审核均通过后，才可以提交训练。'
          }
        />
      );
    }
    return (
      <Alert
        type="error"
        showIcon
        style={{ marginTop: 12 }}
        message="训练代码校验未通过"
        description={
          <ul style={{ marginBottom: 0, paddingLeft: 20 }}>
            {(codeCheck.reasons || []).map((r) => (
              <li key={r}>{r}</li>
            ))}
          </ul>
        }
      />
    );
  };

  const validateConfigSection = async () => {
    await form.validateFields([
      'trainingProfile',
      'trainingMode',
      'hyperParams',
    ]);
  };

  const validateResourceSection = async () => {
    await form.validateFields(['hardwareTargetId', 'resourceMode']);
    // 提交确认页会卸载资源步骤，useWatch 此时可能返回 undefined；
    // 直接读取 Form 保留的字段值，确保“已显示并确认”的默认档位能够提交。
    const formHardwareTargetId = form.getFieldValue('hardwareTargetId');
    const formResourceProfileId = form.getFieldValue('resourceProfileId');
    if (!hardwareOptions.length) {
      message.error('当前没有通过检测的可训练硬件');
      throw new Error('missing hardware option');
    }
    if (
      !isTrainingHardwareTargetAllowed(hardwareOptions, formHardwareTargetId)
    ) {
      message.error('请选择当前可用的硬件型号');
      throw new Error('invalid hardware target');
    }
    if (
      !isTrainingResourceProfileIdAllowed(
        resourceProfiles,
        formResourceProfileId,
      )
    ) {
      message.error('请选择当前训练方案允许的资源规格');
      throw new Error('invalid resource profile');
    }
    if (form.getFieldValue('resourceMode') === 'custom') {
      await form.validateFields(['cpuCores', 'memoryMiB', 'gpuMemoryLimitMiB']);
      try {
        buildTrainingResourceRequest(
          'custom',
          form.getFieldsValue(true),
          resourceProfiles.find(
            (profile) => profile.id === formResourceProfileId,
          ),
          hardwareOptions.find(
            (option) => option.hardwareTargetId === formHardwareTargetId,
          ),
          formHardwareTargetId,
        );
      } catch (error: any) {
        message.error(error?.message || '自定义资源配置无效');
        throw error;
      }
    }
  };

  const validateCodeSection = async () => {
    if (!selectedCodeVersionId) {
      message.error('请选择或上传训练代码');
      throw new Error('missing code');
    }
    if (codeCheck.loading) {
      message.warning('正在执行准入校验，请稍候');
      throw new Error('check loading');
    }
    if (!codeCheck.passed) {
      Modal.error({
        title: '训练代码校验未通过',
        content: (
          <div>
            <p>不能进入下一步，原因：</p>
            <ul style={{ paddingLeft: 20 }}>
              {(codeCheck.reasons || []).map((r) => (
                <li key={r}>{r}</li>
              ))}
            </ul>
          </div>
        ),
      });
      throw new Error('check failed');
    }
    if (
      !isCodeApproved(selectedCodeApprovalStatus || codeCheck.approvalStatus)
    ) {
      Modal.warning({
        title: '训练代码尚未审核通过',
        content: isTrainingCodeAutoApproveEnabled()
          ? '当前版本尚未审核通过。请刷新后重试，或到训练代码页处理。'
          : '当前版本尚未审核通过。请等待管理员审核，或改选已审核版本。',
      });
      throw new Error('approval pending');
    }
  };

  const validateCodeStep = async () => {
    await form.validateFields(['trainingProfile']);
    await validateConfigSection();
    await validateCodeSection();
  };

  const validateStep = async (step: number) => {
    if (step === 0) {
      await form.validateFields(['trainingProfile']);
      if (!selectedTrainingPlan) {
        message.error('请先选择训练方案');
        throw new Error('missing training plan');
      }
      return;
    }
    if (step === 1) {
      if (!selectedTrainingPlan) {
        message.error('请先选择训练方案');
        throw new Error('missing training plan');
      }
      if (!selectedBaseModelVersionId) {
        message.error('请选择或上传基础模型权重');
        throw new Error('missing model');
      }
      const model = modelSelectOptions.find(
        (item) => item.id === selectedBaseModelVersionId,
      );
      if (
        !model ||
        !filteredModelSelectOptions.some(
          (item) => item.id === selectedBaseModelVersionId,
        )
      ) {
        message.error('所选模型与当前训练方案不兼容，或尚未准备完成');
        throw new Error('model is incompatible with training plan');
      }
      return;
    }
    if (step === 2) {
      if (!datasetSelectionReady) {
        message.error('请先在第二步选择基础模型权重');
        throw new Error('missing model');
      }
      if (!selectedDatasetVersionId) {
        message.error('请选择或上传训练数据集');
        throw new Error('missing dataset');
      }
      const dataset = datasetOptions.find(
        (item) => item.versionId === selectedDatasetVersionId,
      );
      if (
        !dataset ||
        !filteredDatasetOptions.some(
          (item) => item.versionId === selectedDatasetVersionId,
        )
      ) {
        message.error('所选数据集与当前训练方案不兼容，或尚未准备完成');
        throw new Error('dataset is incompatible with training plan');
      }
      return;
    }
    if (step === 3) {
      await validateCodeStep();
      return;
    }
    if (step === 4) {
      await validateResourceSection();
    }
  };

  const handleNext = async () => {
    try {
      await validateStep(currentStep);
      setCurrentStep((s) => s + 1);
    } catch {
      // validated inside
    }
  };

  const handlePrev = () => {
    setCurrentStep((s) => Math.max(0, s - 1));
  };

  const handleSubmit = async () => {
    // 必须在第一个 await 前挡住连点，按钮状态更新本身不是同步锁。
    if (!submission.current.begin()) return;
    setSubmitting(true);
    try {
      try {
        await validateResourceSection();
      } catch {
        setCurrentStep(4);
        return;
      }
      if (!codeCheck.passed) {
        Modal.error({
          title: '训练代码校验未通过',
          content: (codeCheck.reasons || ['未知原因']).join('；'),
        });
        setCurrentStep(3);
        return;
      }
      if (
        !isCodeApproved(selectedCodeApprovalStatus || codeCheck.approvalStatus)
      ) {
        Modal.warning({
          title: '训练代码尚未审核通过',
          content: isTrainingCodeAutoApproveEnabled()
            ? '请回到训练配置步骤刷新后重试，或改选已审核通过的版本。'
            : '请等待管理员审核通过后提交，或改选已审核通过的训练代码版本。',
        });
        setCurrentStep(3);
        return;
      }
      if (!selectedBaseModelVersionId || !selectedDatasetVersionId) {
        message.error('请完成基础模型权重与数据集选择');
        return;
      }
      if (!selectedCodeVersionId) {
        message.error('请选择或上传训练代码');
        setCurrentStep(3);
        return;
      }
      const values = form.getFieldsValue(true);
      let hyperParams: Record<string, unknown> = {};
      try {
        hyperParams = JSON.parse(values.hyperParams || '{}');
      } catch {
        message.error('hyperParams JSON 格式不正确');
        setCurrentStep(3);
        return;
      }

      try {
        let data: API.TrainingExperimentVersion | undefined;
        const submittedHardwareOption = hardwareOptions.find(
          (option) => option.hardwareTargetId === values.hardwareTargetId,
        );
        const submittedResourceProfile = resourceProfiles.find(
          (profile) => profile.id === values.resourceProfileId,
        );
        if (!submittedHardwareOption || !submittedResourceProfile) {
          throw new Error('所选硬件型号已不可用，请返回资源配置重新选择');
        }
        const resourceRequest = buildTrainingResourceRequest(
          values.resourceMode,
          values,
          submittedResourceProfile,
          submittedHardwareOption,
          values.hardwareTargetId,
        );
        const payload = {
          name: values.name,
          baseModelVersionId: selectedBaseModelVersionId,
          datasetVersionId: selectedDatasetVersionId,
          remark: values.remark,
          hyperParams,
          codeVersionId: selectedCodeVersionId,
          planId: selectedTrainingPlanId || values.trainingProfile,
          planVersion: selectedTrainingPlan?.version || values.planVersion,
          trainingMode: values.trainingMode,
          resourceProfileId: values.resourceProfileId,
          resourceRequest,
        };
        if (isExperimentContinue) {
          const res: any = await createExperimentVersion(experimentId, {
            ...payload,
            submissionKey: submission.current.keyFor(experimentId, payload),
          }, {
            skipErrorHandler: true,
          });
          if (res?.success === false) {
            throw new Error(res?.errorMessage || '创建实验新版本失败');
          }
          data = res?.data;
        } else {
          const taskPayload = {
            ...payload,
            trainingProfile: selectedTrainingPlanId || values.trainingProfile,
          };
          const res: any = await createTask({
            ...taskPayload,
            submissionKey: submission.current.keyFor('new', taskPayload),
          }, {
            skipErrorHandler: true,
          });
          if (res?.success === false) {
            throw new Error(res?.errorMessage || '创建训练任务失败');
          }
          data = res?.data;
        }
        if (!data?.id) throw new Error('未收到训练编号，请重试查询本次提交结果');
        message.success(isExperimentContinue ? `已创建第 ${data.versionNo ?? '?'} 版训练` : 'K8s 训练任务已创建');
        history.push(`/task/detail/${data.id}`);
      } catch (error: any) {
        message.error(
          error?.errorMessage || error?.message || '创建失败，请重试',
        );
      }
    } finally {
      submission.current.finish();
      setSubmitting(false);
    }
  };

  const stepItems = [
    { title: '训练方案' },
    { title: '基础模型' },
    { title: '训练数据集' },
    { title: '训练配置与代码' },
    { title: '资源配置' },
    { title: '确认并提交' },
  ];

  return {
    isExperimentContinue,
    experimentId,
    backFromModelDetail,
    presetBaseModelVersionId,
    fromModelAssetId,
    form,
    resourceMode,
    currentStep,
    stepItems,
    trainingPlans,
    setSelectedTrainingPlanId,
    setSelectedBaseModelVersionId,
    setSelectedDatasetVersionId,
    setSelectedCodeVersionId,
    setSelectedCodeApprovalStatus,
    setCodeCheck,
    selectedTrainingPlan,
    modelLoading,
    selectedTrainingPlanId,
    selectedBaseModelVersionId,
    filteredModelSelectOptions,
    reloadModelOptions,
    specDrivenModel,
    acceptedModelSpecIds,
    selectedModel,
    datasetSelectionReady,
    specDrivenDataset,
    requiredDatasetType,
    datasetLoading,
    selectedDatasetVersionId,
    filteredDatasetOptions,
    reloadDatasetOptions,
    acceptedDatasetSpecIds,
    codeInputMode,
    setCodeInputMode,
    codeLoading,
    selectedCodeVersionId,
    filteredCodeOptions,
    codeUploading,
    uploadTrainingCodeZip,
    selectedCode,
    selectedCodeApprovalStatus,
    renderCodeCheckAlert,
    hardwareOptionsLoading,
    hardwareOptions,
    hardwareOptionsError,
    selectedResourceProfile,
    selectedHardwareOption,
    resourceStatus,
    codeCheck,
    handlePrev,
    handleNext,
    handleSubmit,
    submitting,
    tourProps,
  };
}
export type TrainingCreateState = ReturnType<typeof useTrainingCreate>;
