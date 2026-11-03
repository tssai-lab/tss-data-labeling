import { UploadOutlined } from '@ant-design/icons';
import { history } from '@umijs/max';
import {
  Alert,
  Button,
  Descriptions,
  Form,
  Input,
  InputNumber,
  message,
  Radio,
  Select,
  Space,
  Tag,
  Typography,
  Upload,
} from 'antd';
import type { UploadFile } from 'antd/es/upload/interface';
import React from 'react';
import { getApiErrorMessage } from '@/utils/apiError';
import { isCodeApproved, PlanFormatHint } from './createPresentation';
import { formatTrainingHardwareOptionLabel } from './hardwareOptionPresentation.mjs';
import { firstTrainingResourceProfileId } from './resourceProfilePresentation.mjs';
import {
  formatTrainingMode,
  isSingleTrainingMode,
} from './trainingModePresentation.mjs';
import { buildTrainingPlanHyperParams } from './trainingPlanDefaults.mjs';
import {
  formatMiB,
  formatTrainingResourceSummary,
} from './trainingResourcePresentation.mjs';
import type { TrainingCreateState } from './useTrainingCreate';
/** 第 1 步：只展示状态，通过原回调更新向导。 */
export function TrainingStep0({
  state,
}: {
  state: Pick<
    TrainingCreateState,
    | 'trainingPlans'
    | 'setSelectedTrainingPlanId'
    | 'setSelectedBaseModelVersionId'
    | 'setSelectedDatasetVersionId'
    | 'setSelectedCodeVersionId'
    | 'setSelectedCodeApprovalStatus'
    | 'setCodeCheck'
    | 'form'
    | 'resourceMode'
    | 'selectedTrainingPlan'
  >;
}) {
  const {
    trainingPlans,
    setSelectedTrainingPlanId,
    setSelectedBaseModelVersionId,
    setSelectedDatasetVersionId,
    setSelectedCodeVersionId,
    setSelectedCodeApprovalStatus,
    setCodeCheck,
    form,
    selectedTrainingPlan,
  } = state;
  return (
    <>
      <Form.Item
        name="trainingProfile"
        label="训练方案"
        rules={[{ required: true, message: '请选择训练方案' }]}
      >
        <Select
          loading={!trainingPlans.length}
          placeholder="请先选择训练方案"
          onChange={(value: string) => {
            const plan = trainingPlans.find((item) => item.id === value);
            const modelTypes = plan?.inputs?.model?.taskTypes ?? [];
            setSelectedTrainingPlanId(value);
            setSelectedBaseModelVersionId(undefined);
            setSelectedDatasetVersionId(undefined);
            setSelectedCodeVersionId(undefined);
            setSelectedCodeApprovalStatus(undefined);
            setCodeCheck({ loading: false });
            form.setFieldsValue({
              baseModelVersionId: undefined,
              datasetVersionId: undefined,
              codeVersionId: undefined,
              planId: value,
              planVersion: plan?.version,
              trainingMode: plan?.trainingModes?.[0],
              hardwareTargetId: undefined,
              resourceProfileId: firstTrainingResourceProfileId(plan),
              resourceMode: 'recommended',
              cpuCores: undefined,
              memoryMiB: undefined,
              gpuMemoryLimitMiB: undefined,
              modelType: modelTypes.length === 1 ? modelTypes[0] : undefined,
              hyperParams: buildTrainingPlanHyperParams(plan),
            });
          }}
          options={Object.values(
            trainingPlans.reduce(
              (acc, plan) => {
                const cat =
                  plan.category ||
                  plan.inputs?.model?.taskTypes?.[0] ||
                  plan.inputs?.dataset?.taskTypes?.[0] ||
                  'OTHER';
                const groupLabel =
                  cat === 'CV' ? 'CV' : cat === 'NLP' ? 'NLP' : '其他';
                if (!acc[groupLabel]) {
                  acc[groupLabel] = { label: groupLabel, options: [] };
                }
                acc[groupLabel].options.push({
                  value: plan.id,
                  label: plan.displayName,
                  disabled: !firstTrainingResourceProfileId(plan),
                });
                return acc;
              },
              {} as Record<
                string,
                {
                  label: string;
                  options: {
                    value: string;
                    label: string;
                    disabled: boolean;
                  }[];
                }
              >,
            ),
          )}
        />
      </Form.Item>
      {selectedTrainingPlan && <PlanFormatHint plan={selectedTrainingPlan} />}
    </>
  );
}

/** 第 2 步：只展示状态，通过原回调更新向导。 */
export function TrainingStep1({
  state,
}: {
  state: Pick<
    TrainingCreateState,
    | 'isExperimentContinue'
    | 'modelLoading'
    | 'selectedTrainingPlanId'
    | 'selectedBaseModelVersionId'
    | 'setSelectedBaseModelVersionId'
    | 'form'
    | 'filteredModelSelectOptions'
    | 'reloadModelOptions'
    | 'specDrivenModel'
    | 'acceptedModelSpecIds'
    | 'selectedModel'
  >;
}) {
  const {
    isExperimentContinue,
    modelLoading,
    selectedTrainingPlanId,
    selectedBaseModelVersionId,
    setSelectedBaseModelVersionId,
    form,
    filteredModelSelectOptions,
    reloadModelOptions,
    specDrivenModel,
    acceptedModelSpecIds,
    selectedModel,
  } = state;
  return (
    <>
      <Form.Item
        label="基础模型权重版本"
        required={isExperimentContinue}
        extra={
          isExperimentContinue
            ? '继续训练默认选中该版本发布的结果模型'
            : '只显示与当前训练方案兼容且准备完成的模型'
        }
      >
        <Select
          placeholder="请选择基础模型权重版本"
          showSearch
          allowClear
          loading={modelLoading}
          disabled={!selectedTrainingPlanId}
          notFoundContent={
            selectedTrainingPlanId
              ? '没有与该方案兼容且准备完成的模型'
              : '请先选择训练方案'
          }
          optionFilterProp="label"
          value={selectedBaseModelVersionId}
          onChange={(value?: string) => {
            setSelectedBaseModelVersionId(value);
            form.setFieldsValue({ baseModelVersionId: value });
          }}
          options={filteredModelSelectOptions.map((item) => ({
            value: item.id,
            label: `${item.name} / ${item.version || 'v?'}`,
          }))}
        />
      </Form.Item>
      <Space style={{ marginBottom: 16 }}>
        <Button
          onClick={() =>
            void reloadModelOptions(
              specDrivenModel ? acceptedModelSpecIds : undefined,
            )
          }
          loading={modelLoading}
        >
          刷新模型列表
        </Button>
        <Button
          href={history.createHref({ pathname: '/model/upload' })}
          target="_blank"
          rel="noopener noreferrer"
        >
          去模型管理上传
        </Button>
      </Space>
      {selectedModel && (
        <Descriptions size="small" column={1} bordered>
          <Descriptions.Item label="名称">
            {selectedModel.name}
          </Descriptions.Item>
          <Descriptions.Item label="版本">
            {selectedModel.version}
          </Descriptions.Item>
        </Descriptions>
      )}
    </>
  );
}

/** 第 3 步：只展示状态，通过原回调更新向导。 */
export function TrainingStep2({
  state,
}: {
  state: Pick<
    TrainingCreateState,
    | 'datasetSelectionReady'
    | 'specDrivenDataset'
    | 'requiredDatasetType'
    | 'selectedModel'
    | 'datasetLoading'
    | 'selectedDatasetVersionId'
    | 'setSelectedDatasetVersionId'
    | 'form'
    | 'filteredDatasetOptions'
    | 'reloadDatasetOptions'
    | 'acceptedDatasetSpecIds'
  >;
}) {
  const {
    datasetSelectionReady,
    specDrivenDataset,
    requiredDatasetType,
    selectedModel,
    datasetLoading,
    selectedDatasetVersionId,
    setSelectedDatasetVersionId,
    form,
    filteredDatasetOptions,
    reloadDatasetOptions,
    acceptedDatasetSpecIds,
  } = state;
  return (
    <>
      {!datasetSelectionReady ? (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 16 }}
          message="请先选择基础模型权重"
          description="请返回上一步完成选择，平台随后会筛选可用数据集。"
        />
      ) : specDrivenDataset ? (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          message="仅显示与当前方案兼容且可用的数据集"
        />
      ) : (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          message={`仅显示与已选模型匹配的 ${requiredDatasetType} 数据集`}
          description={`已选模型：${selectedModel?.name ?? '-'}`}
        />
      )}
      <Form.Item
        name="datasetVersionId"
        label="数据集版本"
        extra={
          specDrivenDataset
            ? '平台已按当前方案筛选'
            : requiredDatasetType
              ? `平台已按 ${requiredDatasetType} 类型筛选`
              : '请先在第一步选择基础模型权重'
        }
      >
        <Select
          placeholder={
            specDrivenDataset
              ? '请选择兼容的数据集版本'
              : requiredDatasetType
                ? `请选择 ${requiredDatasetType} 数据集版本`
                : '请先选择基础模型权重'
          }
          showSearch
          loading={datasetLoading}
          optionFilterProp="label"
          disabled={!datasetSelectionReady}
          notFoundContent={
            datasetSelectionReady
              ? '没有兼容且可用的数据集'
              : '请先选择基础模型权重'
          }
          value={selectedDatasetVersionId}
          onChange={(value: string) => {
            setSelectedDatasetVersionId(value);
            form.setFieldValue('datasetVersionId', value);
          }}
          options={filteredDatasetOptions.flatMap((d: API.DatasetItem) => {
            const versionId = d.versionId;
            if (!versionId) return [];
            return [
              {
                value: versionId,
                label: `${d.name} / ${d.version || 'v?'} / ${d.type || '未分类'}`,
              },
            ];
          })}
        />
      </Form.Item>
      <Space style={{ marginBottom: 16 }}>
        <Button
          onClick={() =>
            void reloadDatasetOptions(
              specDrivenDataset ? acceptedDatasetSpecIds : undefined,
            )
          }
          loading={datasetLoading}
        >
          刷新数据集列表
        </Button>
        <Button
          href={history.createHref({ pathname: '/dataset/upload' })}
          target="_blank"
          rel="noopener noreferrer"
        >
          去数据集管理上传
        </Button>
      </Space>
    </>
  );
}

/** 第 4 步：只展示状态，通过原回调更新向导。 */
export function TrainingStep3({
  state,
}: {
  state: Pick<
    TrainingCreateState,
    | 'selectedTrainingPlan'
    | 'codeInputMode'
    | 'setCodeInputMode'
    | 'codeLoading'
    | 'selectedCodeVersionId'
    | 'setSelectedCodeVersionId'
    | 'setSelectedCodeApprovalStatus'
    | 'form'
    | 'filteredCodeOptions'
    | 'codeUploading'
    | 'uploadTrainingCodeZip'
    | 'selectedCode'
    | 'selectedCodeApprovalStatus'
    | 'renderCodeCheckAlert'
  >;
}) {
  const {
    selectedTrainingPlan,
    codeInputMode,
    setCodeInputMode,
    codeLoading,
    selectedCodeVersionId,
    setSelectedCodeVersionId,
    setSelectedCodeApprovalStatus,
    form,
    filteredCodeOptions,
    codeUploading,
    uploadTrainingCodeZip,
    selectedCode,
    selectedCodeApprovalStatus,
    renderCodeCheckAlert,
  } = state;
  return (
    <>
      <Form.Item name="name" label="任务名称（可选）">
        <Input placeholder="例如：fusion-k8s-train" />
      </Form.Item>
      {isSingleTrainingMode(selectedTrainingPlan?.trainingModes) ? (
        <>
          <Form.Item name="trainingMode" hidden>
            <Input />
          </Form.Item>
          <Form.Item label="训练类型">
            <Typography.Text>
              {formatTrainingMode(selectedTrainingPlan?.trainingModes?.[0])}
            </Typography.Text>
          </Form.Item>
        </>
      ) : (
        <Form.Item
          name="trainingMode"
          label="训练类型"
          rules={[{ required: true, message: '请选择训练类型' }]}
        >
          <Select
            options={(selectedTrainingPlan?.trainingModes ?? []).map(
              (mode) => ({
                value: mode,
                label: formatTrainingMode(mode),
              }),
            )}
          />
        </Form.Item>
      )}
      <Form.Item name="remark" label="备注（可选）">
        <Input placeholder="例如：create-page k8s test" />
      </Form.Item>

      <Form.Item
        name="hyperParams"
        label="训练参数（JSON）"
        extra="参数由训练方案校验后写入运行规格，并传入训练程序。"
        rules={[
          { required: true, message: '请输入训练参数 JSON' },
          {
            validator: async (_: any, value: string) => {
              try {
                JSON.parse(value || '{}');
                return Promise.resolve();
              } catch {
                return Promise.reject(new Error('JSON 格式不正确'));
              }
            },
          },
        ]}
      >
        <Input.TextArea rows={6} />
      </Form.Item>
      <Radio.Group
        value={codeInputMode}
        onChange={(e) => setCodeInputMode(e.target.value)}
        style={{ marginBottom: 16 }}
      >
        <Radio.Button value="select">选择已有</Radio.Button>
        <Radio.Button value="upload">上传新包</Radio.Button>
      </Radio.Group>
      {codeInputMode === 'select' ? (
        <Form.Item
          name="codeVersionId"
          label="训练代码版本"
          extra="仅展示已审核通过的训练代码版本"
        >
          <Select
            placeholder="请选择训练代码版本"
            showSearch
            loading={codeLoading}
            optionFilterProp="label"
            value={selectedCodeVersionId}
            onChange={(value: string) => {
              setSelectedCodeVersionId(value);
              setSelectedCodeApprovalStatus('APPROVED');
              form.setFieldValue('codeVersionId', value);
            }}
            options={filteredCodeOptions.map((item: any) => ({
              value: item.codeVersionId,
              label: `${item.codeAssetName} / ${item.version || item.codeVersionId}`,
            }))}
          />
        </Form.Item>
      ) : (
        <>
          <Form.Item
            name="codeName"
            label="代码资产名称"
            rules={[{ required: true, message: '请输入代码名称' }]}
          >
            <Input placeholder="例如：consistency-train-code" />
          </Form.Item>
          <Form.Item name="codeRemark" label="备注（可选）">
            <Input.TextArea
              rows={2}
              placeholder="例如：fusion 基线训练代码"
              maxLength={200}
              showCount
            />
          </Form.Item>
          <Form.Item
            name="codeFile"
            label="训练代码 ZIP"
            valuePropName="fileList"
            getValueFromEvent={(e) => e?.fileList ?? []}
            rules={[
              {
                required: true,
                validator: (_, value) => {
                  const list = Array.isArray(value) ? value : [];
                  if (
                    !list.length ||
                    !list.some((item: UploadFile) => item.originFileObj)
                  ) {
                    return Promise.reject(new Error('请选择训练代码 zip 文件'));
                  }
                  return Promise.resolve();
                },
              },
            ]}
            extra="仅支持 .zip，须包含固定训练入口脚本"
          >
            <Upload
              beforeUpload={() => false}
              maxCount={1}
              accept=".zip"
              disabled={codeUploading}
            >
              <Button icon={<UploadOutlined />} disabled={codeUploading}>
                选择训练代码 zip
              </Button>
            </Upload>
          </Form.Item>
          <Button
            type="primary"
            loading={codeUploading}
            onClick={async () => {
              try {
                const values = await form.validateFields([
                  'codeName',
                  'codeFile',
                ]);
                await uploadTrainingCodeZip({
                  codeName: values.codeName,
                  remark: values.codeRemark,
                  file: values.codeFile,
                });
              } catch (error: any) {
                message.error(getApiErrorMessage(error));
              }
            }}
          >
            上传并选用
          </Button>
        </>
      )}
      {selectedCode && (
        <Descriptions
          size="small"
          column={1}
          bordered
          style={{ marginTop: 16 }}
        >
          <Descriptions.Item label="代码版本编号">
            <Typography.Text copyable code>
              {selectedCode.codeVersionId}
            </Typography.Text>
          </Descriptions.Item>
          <Descriptions.Item label="状态">
            <Space>
              <Tag
                color={selectedCode.status === 'READY' ? 'success' : 'default'}
              >
                {selectedCode.status}
              </Tag>
              <Tag
                color={
                  selectedCodeApprovalStatus === 'APPROVED'
                    ? 'success'
                    : 'warning'
                }
              >
                {selectedCodeApprovalStatus || '-'}
              </Tag>
            </Space>
          </Descriptions.Item>
        </Descriptions>
      )}
      {renderCodeCheckAlert()}
    </>
  );
}

/** 第 5 步：只展示状态，通过原回调更新向导。 */
export function TrainingStep4({
  state,
}: {
  state: Pick<
    TrainingCreateState,
    | 'hardwareOptionsLoading'
    | 'hardwareOptions'
    | 'hardwareOptionsError'
    | 'form'
    | 'resourceMode'
    | 'selectedResourceProfile'
    | 'selectedHardwareOption'
    | 'resourceStatus'
  >;
}) {
  const {
    hardwareOptionsLoading,
    hardwareOptions,
    hardwareOptionsError,
    form,
    resourceMode,
    selectedResourceProfile,
    selectedHardwareOption,
    resourceStatus,
  } = state;
  return (
    <>
      {hardwareOptionsLoading && !hardwareOptions.length && (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          message="正在从 Kubernetes 读取可训练硬件型号"
        />
      )}
      {!hardwareOptionsLoading && hardwareOptionsError && (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 16 }}
          message="可训练硬件读取失败"
          description={hardwareOptionsError}
        />
      )}
      {!hardwareOptionsLoading &&
        !hardwareOptionsError &&
        !hardwareOptions.length && (
          <Alert
            type="error"
            showIcon
            style={{ marginBottom: 16 }}
            message="当前没有与训练方案兼容的可训练硬件"
          />
        )}
      <Form.Item
        name="hardwareTargetId"
        label="计算硬件"
        extra="GPU 选项会写出宿主机编号；提交后按 UUID 固定到这张卡，卡忙时等待，不会自动换卡。"
        rules={[{ required: true, message: '请选择计算硬件' }]}
      >
        <Select
          loading={hardwareOptionsLoading}
          disabled={!hardwareOptions.length}
          placeholder="请选择检测到的 CPU 或物理 GPU"
          onChange={(hardwareTargetId: string) => {
            const option = hardwareOptions.find(
              (item) => item.hardwareTargetId === hardwareTargetId,
            );
            form.setFieldsValue({
              resourceProfileId: option?.resourceProfileId,
              resourceMode: 'recommended',
              cpuCores: undefined,
              memoryMiB: undefined,
              gpuMemoryLimitMiB: undefined,
            });
          }}
          options={hardwareOptions.map((option) => ({
            value: option.hardwareTargetId,
            label: formatTrainingHardwareOptionLabel(option, formatMiB),
          }))}
        />
      </Form.Item>
      {selectedResourceProfile && selectedHardwareOption && (
        <>
          <Alert
            type={
              resourceStatus.type as 'success' | 'info' | 'warning' | 'error'
            }
            showIcon
            style={{ marginBottom: 16 }}
            message={
              hardwareOptionsLoading
                ? '正在读取可训练硬件'
                : resourceStatus.message
            }
            description={resourceStatus.description}
          />
          <Form.Item
            name="resourceMode"
            label="配置方式"
            rules={[{ required: true, message: '请选择资源配置方式' }]}
          >
            <Radio.Group
              onChange={(event) => {
                if (event.target.value === 'custom' && selectedHardwareOption) {
                  form.setFieldsValue({
                    cpuCores: selectedHardwareOption.cpu.limitCores,
                    memoryMiB: selectedHardwareOption.memory.limitMiB,
                    gpuMemoryLimitMiB: undefined,
                  });
                } else {
                  form.setFieldsValue({
                    cpuCores: undefined,
                    memoryMiB: undefined,
                    gpuMemoryLimitMiB: undefined,
                  });
                }
              }}
            >
              <Radio.Button value="recommended">推荐配置</Radio.Button>
              <Radio.Button value="custom" disabled={!selectedHardwareOption}>
                自定义配置
              </Radio.Button>
            </Radio.Group>
          </Form.Item>
          <Descriptions size="small" column={1} bordered>
            <Descriptions.Item label="硬件型号">
              {selectedHardwareOption.displayName}
            </Descriptions.Item>
            <Descriptions.Item label="可用节点">
              {selectedHardwareOption.eligibleNodeCount} 个
            </Descriptions.Item>
            <Descriptions.Item label="CPU（最小 / 最大）">
              {selectedHardwareOption.cpu.requestCores} /{' '}
              {selectedHardwareOption.cpu.limitCores} 核
            </Descriptions.Item>
            <Descriptions.Item label="系统内存（最小 / 最大）">
              {formatMiB(selectedHardwareOption.memory.requestMiB)} /{' '}
              {formatMiB(selectedHardwareOption.memory.limitMiB)}
            </Descriptions.Item>
            {selectedResourceProfile.deviceType === 'NVIDIA_GPU' && (
              <>
                <Descriptions.Item label="本次 GPU 数量">
                  {selectedHardwareOption.gpuCount} 卡
                  （当前训练方案仅支持单卡）
                </Descriptions.Item>
                <Descriptions.Item label="GPU 分配方式">
                  按 UUID 固定到所选物理卡
                </Descriptions.Item>
                <Descriptions.Item label="宿主机编号">
                  GPU {selectedHardwareOption.gpu?.hostGpuIndex ?? '-'}
                </Descriptions.Item>
                <Descriptions.Item label="GPU UUID">
                  {selectedHardwareOption.gpu?.uuid ?? '-'}
                </Descriptions.Item>
                <Descriptions.Item label="单卡总显存">
                  {formatMiB(selectedHardwareOption.gpu?.safeTotalMemoryMiB)}
                </Descriptions.Item>
                <Descriptions.Item label="当前最大空闲显存（仅参考）">
                  {formatMiB(selectedHardwareOption.gpu?.maxFreeMemoryMiB)}
                </Descriptions.Item>
              </>
            )}
          </Descriptions>
          {resourceMode === 'custom' && selectedHardwareOption && (
            <div style={{ marginTop: 16 }}>
              <Form.Item
                name="cpuCores"
                label="CPU 核数"
                rules={[{ required: true, message: '请输入 CPU 核数' }]}
                extra={`所选硬件允许范围：${selectedHardwareOption.cpu.requestCores} ～ ${selectedHardwareOption.cpu.limitCores} 核`}
              >
                <InputNumber
                  min={selectedHardwareOption.cpu.requestCores}
                  max={selectedHardwareOption.cpu.limitCores}
                  step={0.1}
                  style={{ width: '100%' }}
                  addonAfter="核"
                />
              </Form.Item>
              <Form.Item
                name="memoryMiB"
                label="系统内存"
                rules={[{ required: true, message: '请输入系统内存' }]}
                extra={`所选硬件允许范围：${formatMiB(selectedHardwareOption.memory.requestMiB)} ～ ${formatMiB(selectedHardwareOption.memory.limitMiB)}`}
              >
                <InputNumber
                  min={selectedHardwareOption.memory.requestMiB}
                  max={selectedHardwareOption.memory.limitMiB}
                  step={256}
                  precision={0}
                  style={{ width: '100%' }}
                  addonAfter="MiB"
                />
              </Form.Item>
              {selectedResourceProfile.deviceType === 'NVIDIA_GPU' && (
                <Form.Item
                  name="gpuMemoryLimitMiB"
                  label="单卡 GPU 显存软预算（可选）"
                  extra={
                    selectedHardwareOption.gpu?.metricsComplete
                      ? `所选型号最多 ${formatMiB(selectedHardwareOption.gpu.safeTotalMemoryMiB)}。这是标准 PyTorch 进程软限制，超出后训练会显存不足，不等于硬隔离。`
                      : 'GPU 详情缺失或过期，暂不能设置显存预算。'
                  }
                >
                  <InputNumber
                    min={1}
                    max={selectedHardwareOption.gpu?.safeTotalMemoryMiB}
                    step={256}
                    precision={0}
                    disabled={!selectedHardwareOption.gpu?.metricsComplete}
                    style={{ width: '100%' }}
                    addonAfter="MiB"
                  />
                </Form.Item>
              )}
            </div>
          )}
        </>
      )}
    </>
  );
}

/** 第 6 步：只展示状态，通过原回调更新向导。 */
export function TrainingStep5({
  state,
}: {
  state: Pick<
    TrainingCreateState,
    | 'codeCheck'
    | 'selectedCodeApprovalStatus'
    | 'selectedTrainingPlan'
    | 'selectedTrainingPlanId'
    | 'selectedBaseModelVersionId'
    | 'selectedDatasetVersionId'
    | 'selectedCodeVersionId'
    | 'form'
    | 'selectedHardwareOption'
  >;
}) {
  const {
    codeCheck,
    selectedCodeApprovalStatus,
    selectedTrainingPlan,
    selectedTrainingPlanId,
    selectedBaseModelVersionId,
    selectedDatasetVersionId,
    selectedCodeVersionId,
    form,
    selectedHardwareOption,
  } = state;
  return (
    <>
      {(!codeCheck.passed ||
        !isCodeApproved(
          selectedCodeApprovalStatus || codeCheck.approvalStatus,
        )) && (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 16 }}
          message={
            !codeCheck.passed
              ? '训练代码校验未通过，不能用于训练'
              : '训练代码尚未审核通过，不能用于训练'
          }
          description={
            !codeCheck.passed
              ? (codeCheck.reasons || []).join('；')
              : '请等待管理员审核通过，或改选其他训练代码。'
          }
        />
      )}
      <Descriptions size="small" column={1} bordered>
        <Descriptions.Item label="配置方式">训练代码</Descriptions.Item>
        <Descriptions.Item label="训练方案">
          {selectedTrainingPlan?.displayName || selectedTrainingPlanId || '-'}
        </Descriptions.Item>
        <Descriptions.Item label="baseModelVersionId">
          <Typography.Text copyable code>
            {selectedBaseModelVersionId || '-'}
          </Typography.Text>
        </Descriptions.Item>
        <Descriptions.Item label="datasetVersionId">
          <Typography.Text copyable code>
            {selectedDatasetVersionId || '-'}
          </Typography.Text>
        </Descriptions.Item>
        <Descriptions.Item label="代码版本编号">
          <Typography.Text copyable code>
            {selectedCodeVersionId || '-'}
          </Typography.Text>
        </Descriptions.Item>
        <Descriptions.Item label="hyperParams">
          <code>{form.getFieldValue('hyperParams') || '{}'}</code>
        </Descriptions.Item>
        <Descriptions.Item label="执行方式">Kubernetes Job</Descriptions.Item>
        <Descriptions.Item label="运行规格">
          {formatTrainingResourceSummary(
            form.getFieldValue('resourceMode'),
            form.getFieldsValue(['cpuCores', 'memoryMiB']),
            selectedHardwareOption,
          )}
        </Descriptions.Item>
        <Descriptions.Item label="资源配置方式">
          {form.getFieldValue('resourceMode') === 'custom'
            ? '自定义配置'
            : '训练方案推荐配置'}
        </Descriptions.Item>
        {form.getFieldValue('gpuMemoryLimitMiB') ? (
          <Descriptions.Item label="单卡 GPU 显存软预算">
            {formatMiB(form.getFieldValue('gpuMemoryLimitMiB'))}
          </Descriptions.Item>
        ) : null}
        <Descriptions.Item label="模型权重目录">
          /workspace/job/model（是否加载由训练方案和训练代码决定）
        </Descriptions.Item>
      </Descriptions>
    </>
  );
}
