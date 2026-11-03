import { PageContainer } from '@ant-design/pro-components';
import { history } from '@umijs/max';
import { Alert, Button, Form, Space, Steps, Tour } from 'antd';
import React from 'react';
import {
  FUSION_HYPER_PARAMS_DEFAULT,
  isCodeApproved,
} from './createPresentation';
import {
  TrainingStep0,
  TrainingStep1,
  TrainingStep2,
  TrainingStep3,
  TrainingStep4,
  TrainingStep5,
} from './TrainingCreateSteps';
import { useTrainingCreate } from './useTrainingCreate';

const TaskCreate: React.FC = () => {
  const state = useTrainingCreate();
  const {
    isExperimentContinue,
    experimentId,
    backFromModelDetail,
    presetBaseModelVersionId,
    fromModelAssetId,
    form,
    currentStep,
    stepItems,
    selectedCodeApprovalStatus,
    codeCheck,
    handlePrev,
    handleNext,
    handleSubmit,
    submitting,
    tourProps,
  } = state;
  return (
    <PageContainer
      title={isExperimentContinue ? '基于此版本继续训练' : '发起训练'}
      subTitle="选择训练方案、模型、数据集、训练代码和资源规格"
      onBack={() => {
        if (isExperimentContinue) {
          history.push(`/task/detail/${encodeURIComponent(experimentId)}`);
          return;
        }
        if (backFromModelDetail) {
          const q = presetBaseModelVersionId
            ? `?versionId=${encodeURIComponent(presetBaseModelVersionId)}`
            : '';
          history.push(
            `/model/detail/${encodeURIComponent(fromModelAssetId)}${q}`,
          );
          return;
        }
        history.push('/task/list');
      }}
    >
      {isExperimentContinue && (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          message="基于此版本继续训练"
          description="系统会尝试带入该版本使用的模型、数据集、训练代码和参数，请在各步骤核对；提交后会保留原版本并生成新版本。"
        />
      )}

      <Form
        form={form}
        preserve
        layout="vertical"
        initialValues={{
          hyperParams: JSON.stringify(FUSION_HYPER_PARAMS_DEFAULT, null, 2),
          modelVersion: 'v1.0.0',
          datasetVersion: 'v1.0.0',
          resourceMode: 'recommended',
        }}
      >
        <Steps
          current={currentStep}
          items={stepItems}
          style={{ marginBottom: 24 }}
          data-tour="train-steps"
        />

        <div
          style={{ minHeight: 280, marginBottom: 24 }}
          data-tour="train-panel"
        >
          {currentStep === 0 && <TrainingStep0 state={state} />}

          {currentStep === 1 && <TrainingStep1 state={state} />}

          {currentStep === 2 && <TrainingStep2 state={state} />}

          {currentStep === 3 && <TrainingStep3 state={state} />}

          {currentStep === 4 && <TrainingStep4 state={state} />}

          {currentStep === 5 && <TrainingStep5 state={state} />}
        </div>

        <Space data-tour="train-actions">
          {currentStep > 0 && (
            <Button htmlType="button" onClick={handlePrev} disabled={submitting}>
              上一步
            </Button>
          )}
          {currentStep < 5 ? (
            <Button type="primary" htmlType="button" onClick={handleNext}>
              下一步
            </Button>
          ) : (
            <Button
              type="primary"
              htmlType="button"
              disabled={
                !codeCheck.passed ||
                !isCodeApproved(
                  selectedCodeApprovalStatus || codeCheck.approvalStatus,
                )
              }
              onClick={handleSubmit}
              loading={submitting}
            >
              {isExperimentContinue ? '提交并创建新版本' : '提交 K8s 训练'}
            </Button>
          )}
        </Space>
      </Form>
      <Tour {...tourProps} />
    </PageContainer>
  );
};
export default TaskCreate;
