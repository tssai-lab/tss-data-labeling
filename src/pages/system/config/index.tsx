import { PageContainer } from '@ant-design/pro-components';
import { history, useAccess } from '@umijs/max';
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Form,
  InputNumber,
  Modal,
  message,
  Space,
  Spin,
  Switch,
  Typography,
} from 'antd';
import React, { useCallback, useEffect, useState } from 'react';
import {
  getTrainingCodeReviewLocalConfig,
  setTrainingCodeReviewLocalConfig,
} from '@/constants/trainingCode';
import {
  DEFAULT_USER_LOG_LIMIT_MB,
  fetchKubernetesResourcePolicy,
  fetchSystemConfig,
  type KubernetesResourcePolicy,
  MAX_JOB_QUOTA,
  MAX_JOB_TTL_SECONDS,
  MAX_POD_QUOTA,
  MAX_USER_LOG_LIMIT_MB,
  MIN_JOB_QUOTA,
  MIN_JOB_TTL_SECONDS,
  MIN_POD_QUOTA,
  MIN_USER_LOG_LIMIT_MB,
  type SystemConfig,
  updateKubernetesResourcePolicy,
  updateSystemConfig,
} from '@/services/system/config';
import {
  automaticReviewSelectedFromMode,
  normalizeTrainingCodeReviewMode,
  reviewEnabledFromMode,
  reviewModeFromSwitches,
} from '@/services/system/trainingCodeReviewPolicy';
import { storage } from '@/utils/storage';

const DEFAULT_CONFIG: SystemConfig = {
  enableTrainingCodeAdminReview: true,
  enableTrainingCodeAutoReview: true,
  trainingCodeReviewMode: 'STANDARD_REVIEW',
  userLogStorageLimitMb: DEFAULT_USER_LOG_LIMIT_MB,
  logMaxSize: DEFAULT_USER_LOG_LIMIT_MB,
};

const LOCAL_SYSTEM_CONFIG_KEY = 'SYSTEM_CONFIG_LOCAL';

function isNotFoundError(error: unknown): boolean {
  const err = error as {
    response?: { status?: number };
    status?: number;
    message?: string;
  };
  const status = err?.response?.status ?? err?.status;
  if (status === 404) return true;
  return /status code 404|Not Found/i.test(String(err?.message || ''));
}

function readLocalSystemConfig(): SystemConfig {
  const cached = storage.get<Partial<SystemConfig>>(LOCAL_SYSTEM_CONFIG_KEY);
  const review = getTrainingCodeReviewLocalConfig();
  const limitMb =
    cached?.userLogStorageLimitMb ??
    cached?.logMaxSize ??
    DEFAULT_USER_LOG_LIMIT_MB;
  const mode = normalizeTrainingCodeReviewMode(
    cached?.trainingCodeReviewMode || review.trainingCodeReviewMode,
  );
  return {
    enableTrainingCodeAdminReview: reviewEnabledFromMode(mode),
    enableTrainingCodeAutoReview: automaticReviewSelectedFromMode(mode),
    trainingCodeReviewMode: mode,
    userLogStorageLimitMb: limitMb,
    logMaxSize: limitMb,
    updatedAt: cached?.updatedAt,
  };
}

function writeLocalSystemConfig(
  config: SystemConfig,
  syncedFromServer: boolean,
) {
  storage.set(LOCAL_SYSTEM_CONFIG_KEY, config);
  setTrainingCodeReviewLocalConfig({
    enableTrainingCodeAdminReview: config.enableTrainingCodeAdminReview ?? true,
    enableTrainingCodeAutoReview: config.enableTrainingCodeAutoReview ?? true,
    trainingCodeReviewMode: config.trainingCodeReviewMode,
    syncedFromServer,
  });
}

function formatUpdatedAt(value?: string) {
  if (!value) return '-';
  return value.replace('T', ' ').replace('Z', ' UTC').slice(0, 23);
}

/**
 * 系统配置页（仅超管）
 * 对齐后端：trainingCodeReviewMode、logMaxSize / userLogStorageLimitMb、updatedAt
 */
const SystemConfigPage: React.FC = () => {
  const access = useAccess();
  const [form] = Form.useForm<SystemConfig>();
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [usingLocalFallback, setUsingLocalFallback] = useState(false);
  const [updatedAt, setUpdatedAt] = useState<string>();
  const [resourceForm] = Form.useForm<KubernetesResourcePolicy>();
  const [resourceLoading, setResourceLoading] = useState(false);
  const [resourceSaving, setResourceSaving] = useState(false);
  const [resourcePolicy, setResourcePolicy] =
    useState<KubernetesResourcePolicy>();
  const [resourceError, setResourceError] = useState<string>();
  const reviewEnabled = Form.useWatch('enableTrainingCodeAdminReview', form);

  useEffect(() => {
    if (!access.canAccessSystemConfig) {
      history.replace('/403');
    }
  }, [access.canAccessSystemConfig]);

  const applyConfig = useCallback(
    (next: SystemConfig, syncedFromServer = true) => {
      form.setFieldsValue(next);
      setUpdatedAt(next.updatedAt);
      writeLocalSystemConfig(next, syncedFromServer);
    },
    [form],
  );

  const loadConfig = useCallback(async () => {
    setLoading(true);
    try {
      const res = await fetchSystemConfig({ skipErrorHandler: true });
      if (res.code === 200 && res.data) {
        applyConfig(res.data);
        setUsingLocalFallback(false);
        return;
      }
      applyConfig(readLocalSystemConfig(), false);
      setUsingLocalFallback(true);
    } catch (error: unknown) {
      applyConfig(readLocalSystemConfig(), false);
      setUsingLocalFallback(true);
      if (!isNotFoundError(error)) {
        message.warning('系统配置接口暂不可用，已加载本机缓存');
      }
    } finally {
      setLoading(false);
    }
  }, [applyConfig]);

  useEffect(() => {
    if (access.canAccessSystemConfig) {
      void loadConfig();
    }
  }, [access.canAccessSystemConfig, loadConfig]);

  const loadResourcePolicy = useCallback(async () => {
    setResourceLoading(true);
    setResourceError(undefined);
    try {
      const res = await fetchKubernetesResourcePolicy({
        skipErrorHandler: true,
      });
      if (res.code !== 200 || !res.data) {
        throw new Error(res.message || '无法读取 Kubernetes 资源策略');
      }
      setResourcePolicy(res.data);
      resourceForm.setFieldsValue(res.data);
    } catch (error: unknown) {
      setResourcePolicy(undefined);
      setResourceError(
        error instanceof Error ? error.message : '无法读取 Kubernetes 资源策略',
      );
    } finally {
      setResourceLoading(false);
    }
  }, [resourceForm]);

  useEffect(() => {
    if (access.canAccessSystemConfig) {
      void loadResourcePolicy();
    }
  }, [access.canAccessSystemConfig, loadResourcePolicy]);

  const buildPayload = (values: SystemConfig): SystemConfig => {
    const limitMb =
      values.userLogStorageLimitMb ??
      values.logMaxSize ??
      DEFAULT_USER_LOG_LIMIT_MB;
    const enableReview = values.enableTrainingCodeAdminReview ?? false;
    const enableAutomaticReview = values.enableTrainingCodeAutoReview ?? true;
    return {
      enableTrainingCodeAdminReview: enableReview,
      enableTrainingCodeAutoReview: enableAutomaticReview,
      trainingCodeReviewMode: reviewModeFromSwitches(
        enableReview,
        enableAutomaticReview,
      ),
      userLogStorageLimitMb: limitMb,
      logMaxSize: limitMb,
    };
  };

  const handleSubmit = async () => {
    if (usingLocalFallback) {
      message.error('后端系统配置接口不可用，无法修改全局审核策略');
      return;
    }
    let values: SystemConfig;
    try {
      values = await form.validateFields();
    } catch {
      return;
    }
    const payload = buildPayload(values);
    setSaving(true);
    try {
      const res = await updateSystemConfig(payload, {
        skipErrorHandler: true,
      });
      if (res.code !== 200) {
        throw new Error(res.message || '保存失败');
      }
      const verified = await fetchSystemConfig({ skipErrorHandler: true });
      if (
        verified.code !== 200 ||
        !verified.data ||
        verified.data.trainingCodeReviewMode !== payload.trainingCodeReviewMode
      ) {
        throw new Error('后端未返回刚保存的审核策略，请重新加载后重试');
      }
      applyConfig(verified.data, true);
      setUsingLocalFallback(false);
      message.success(res.message || '保存成功');
    } catch (error: unknown) {
      message.error(
        error instanceof Error ? error.message : '保存失败，请稍后重试',
      );
    } finally {
      setSaving(false);
    }
  };

  const saveResourcePolicy = async (values: KubernetesResourcePolicy) => {
    setResourceSaving(true);
    try {
      const res = await updateKubernetesResourcePolicy(
        {
          podQuota: values.podQuota,
          jobQuota: values.jobQuota,
          jobTtlSecondsAfterFinished: values.jobTtlSecondsAfterFinished,
        },
        { skipErrorHandler: true },
      );
      if (res.code !== 200 || !res.data) {
        throw new Error(res.message || '资源策略保存失败');
      }
      setResourcePolicy(res.data);
      resourceForm.setFieldsValue(res.data);
      setResourceError(undefined);
      message.success(res.message || '资源策略保存成功');
    } catch (error: unknown) {
      const detail =
        error instanceof Error ? error.message : '资源策略保存失败';
      setResourceError(detail);
      message.error(`${detail}；未使用本地值替代集群配置`);
      await loadResourcePolicy();
    } finally {
      setResourceSaving(false);
    }
  };

  const handleResourceSubmit = async () => {
    let values: KubernetesResourcePolicy;
    try {
      values = await resourceForm.validateFields();
    } catch {
      return;
    }
    Modal.confirm({
      title: '确认更新 Kubernetes 资源策略？',
      content:
        '降低配额不会删除现有 Pod/Job；TTL 只写入之后新建的 Job，不追溯修改已有任务。',
      okText: '确认更新',
      cancelText: '取消',
      onOk: () => saveResourcePolicy(values),
    });
  };

  if (!access.canAccessSystemConfig) return null;

  return (
    <PageContainer
      title="系统配置"
      subTitle="系统级参数配置，仅超级管理员可查看与修改。"
    >
      <Card>
        <Spin spinning={loading}>
          {usingLocalFallback ? (
            <Typography.Paragraph type="secondary" style={{ marginBottom: 16 }}>
              后端系统配置接口暂不可用，当前只展示最近一次缓存；恢复连接前不能修改全局审核策略。
            </Typography.Paragraph>
          ) : null}
          <Form
            form={form}
            layout="vertical"
            style={{ maxWidth: 640 }}
            initialValues={DEFAULT_CONFIG}
          >
            <Form.Item
              name="enableTrainingCodeAdminReview"
              label="启用训练代码审核"
              valuePropName="checked"
              extra="关闭：基础校验通过后直接批准。开启：按下方自动审核设置进入自动审核或全部人工待审。"
            >
              <Switch
                checkedChildren="开启"
                unCheckedChildren="关闭"
                disabled={usingLocalFallback}
              />
            </Form.Item>
            <Form.Item
              name="enableTrainingCodeAutoReview"
              label="启用自动审核"
              valuePropName="checked"
              extra={
                reviewEnabled
                  ? '开启：自动扫描，低风险自动通过、阻断项自动拒绝，其余人工审核。关闭：所有新代码通过基础校验后进入人工待审。'
                  : '训练代码审核关闭时不生效；重新开启审核时默认选择自动审核，可再手动关闭。'
              }
            >
              <Switch
                checkedChildren="开启"
                unCheckedChildren="关闭"
                disabled={!reviewEnabled || usingLocalFallback}
              />
            </Form.Item>
            <Form.Item
              name="userLogStorageLimitMb"
              label="每用户日志容量上限"
              extra={`单位 MB。默认 ${DEFAULT_USER_LOG_LIMIT_MB}，范围 ${MIN_USER_LOG_LIMIT_MB}～${MAX_USER_LOG_LIMIT_MB}。`}
              rules={[
                { required: true, message: '请输入每用户日志容量上限' },
                {
                  type: 'number',
                  min: MIN_USER_LOG_LIMIT_MB,
                  max: MAX_USER_LOG_LIMIT_MB,
                  message: `请输入 ${MIN_USER_LOG_LIMIT_MB}～${MAX_USER_LOG_LIMIT_MB} 之间的整数`,
                },
              ]}
            >
              <InputNumber
                min={MIN_USER_LOG_LIMIT_MB}
                max={MAX_USER_LOG_LIMIT_MB}
                step={1}
                precision={0}
                addonAfter="MB"
                style={{ width: 240 }}
                placeholder={String(DEFAULT_USER_LOG_LIMIT_MB)}
                disabled={usingLocalFallback}
              />
            </Form.Item>
            <Form.Item label="最近更新时间">
              <Typography.Text type="secondary">
                {formatUpdatedAt(updatedAt)}
              </Typography.Text>
            </Form.Item>
            <Form.Item>
              <Button
                type="primary"
                onClick={handleSubmit}
                loading={saving}
                disabled={usingLocalFallback}
              >
                保存配置
              </Button>
              <Button
                style={{ marginLeft: 8 }}
                onClick={() => void loadConfig()}
                disabled={loading || saving}
              >
                重新加载
              </Button>
            </Form.Item>
          </Form>
        </Spin>
      </Card>
      <Card
        title="Kubernetes Pod / Job 配额与清理时间"
        style={{ marginTop: 16 }}
      >
        <Spin spinning={resourceLoading}>
          {resourceError ? (
            <Alert
              type="error"
              showIcon
              message="当前无法确认集群真实配置"
              description={resourceError}
              style={{ marginBottom: 16 }}
            />
          ) : null}
          {resourcePolicy ? (
            <Descriptions size="small" column={2} style={{ marginBottom: 16 }}>
              <Descriptions.Item label="目标集群">
                {resourcePolicy.clusterName}
              </Descriptions.Item>
              <Descriptions.Item label="命名空间">
                {resourcePolicy.namespace}
              </Descriptions.Item>
              <Descriptions.Item label="当前 Pod 占用">
                {resourcePolicy.usedPods} / {resourcePolicy.podQuota}
              </Descriptions.Item>
              <Descriptions.Item label="当前 Job 占用">
                {resourcePolicy.usedJobs} / {resourcePolicy.jobQuota}
              </Descriptions.Item>
            </Descriptions>
          ) : null}
          <Alert
            type="info"
            showIcon
            message="修改规则"
            description="低于当前占用的配额会被拒绝，不会删除任务。TTL 从 Job 完成后开始计时，只影响保存后新建的训练或推理 Job；推荐先使用 180 秒。"
            style={{ marginBottom: 16 }}
          />
          <Form
            form={resourceForm}
            layout="vertical"
            style={{ maxWidth: 640 }}
            disabled={resourceLoading || resourceSaving}
          >
            <Form.Item
              name="podQuota"
              label="Pod 总数上限"
              rules={[
                { required: true, message: '请输入 Pod 配额' },
                {
                  type: 'number',
                  min: MIN_POD_QUOTA,
                  max: MAX_POD_QUOTA,
                  message: `请输入 ${MIN_POD_QUOTA}～${MAX_POD_QUOTA} 之间的整数`,
                },
              ]}
            >
              <InputNumber
                min={MIN_POD_QUOTA}
                max={MAX_POD_QUOTA}
                precision={0}
                style={{ width: 240 }}
              />
            </Form.Item>
            <Form.Item
              name="jobQuota"
              label="Job 总数上限"
              extra="已完成但尚未被 TTL 清理的 Job 也会占用此配额。"
              rules={[
                { required: true, message: '请输入 Job 配额' },
                {
                  type: 'number',
                  min: MIN_JOB_QUOTA,
                  max: MAX_JOB_QUOTA,
                  message: `请输入 ${MIN_JOB_QUOTA}～${MAX_JOB_QUOTA} 之间的整数`,
                },
              ]}
            >
              <InputNumber
                min={MIN_JOB_QUOTA}
                max={MAX_JOB_QUOTA}
                precision={0}
                style={{ width: 240 }}
              />
            </Form.Item>
            <Form.Item
              name="jobTtlSecondsAfterFinished"
              label="Job 完成后保留时间"
              rules={[
                { required: true, message: '请输入 Job TTL' },
                {
                  type: 'number',
                  min: MIN_JOB_TTL_SECONDS,
                  max: MAX_JOB_TTL_SECONDS,
                  message: `请输入 ${MIN_JOB_TTL_SECONDS}～${MAX_JOB_TTL_SECONDS} 秒`,
                },
              ]}
            >
              <InputNumber
                min={MIN_JOB_TTL_SECONDS}
                max={MAX_JOB_TTL_SECONDS}
                precision={0}
                addonAfter="秒"
                style={{ width: 240 }}
              />
            </Form.Item>
            <Form.Item>
              <Space>
                <Button
                  type="primary"
                  onClick={() => void handleResourceSubmit()}
                  loading={resourceSaving}
                  disabled={!resourcePolicy}
                >
                  保存资源策略
                </Button>
                <Button
                  onClick={() => void loadResourcePolicy()}
                  disabled={resourceLoading || resourceSaving}
                >
                  重新读取集群
                </Button>
              </Space>
            </Form.Item>
          </Form>
        </Spin>
      </Card>
    </PageContainer>
  );
};

export default SystemConfigPage;
