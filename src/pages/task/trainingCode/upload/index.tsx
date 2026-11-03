import { UploadOutlined } from '@ant-design/icons';
import { PageContainer } from '@ant-design/pro-components';
import { history, useAccess } from '@umijs/max';
import type { UploadFile } from 'antd';
import {
  Alert,
  Button,
  Descriptions,
  Form,
  Input,
  message,
  Select,
  Space,
  Tag,
  Typography,
  Upload,
} from 'antd';
import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import {
  hasSyncedTrainingCodeReviewConfig,
  isTrainingCodeAdminReviewEnabled,
  syncTrainingCodeReviewConfigFromServer,
} from '@/constants/trainingCode';
import {
  approveCodeVersion,
  autoApproveCodeVersionIfEnabled,
  CONSISTENCY_TRAINING_PROFILE,
  checkCodeVersionForTraining,
  getCodeVersionDetail,
  normalizeCodeApprovalStatus,
  uploadCodeZip,
} from '@/services/platform';
import {
  fetchTrainingPlans,
  type TrainingPlan,
} from '@/services/trainingPlans';
import { getApiErrorMessage } from '@/utils/apiError';
import { resolveOwnerFacingApproval } from '@/utils/codeApprovalDisplay';
import {
  markPendingCodeApproved,
  markPendingCodeStatus,
  upsertPendingCodeVersion,
} from '@/utils/pendingCodeVersions';

type UploadResultState = {
  codeVersionId: string;
  approvalStatus?: string;
  /** 就绪状态 READY 等 */
  status?: string;
  validationStatus?: string;
  validationPolicyVersion?: string;
  artifactSha256?: string;
  fileName?: string;
  trainingProfile?: string;
  reviewDisposition?: string;
  riskLevel?: string;
};

const TrainingCodeUpload: React.FC = () => {
  const access = useAccess();
  const [form] = Form.useForm();
  const [uploading, setUploading] = useState(false);
  const [approving, setApproving] = useState(false);
  const [statusRefreshing, setStatusRefreshing] = useState(false);
  const [plansLoading, setPlansLoading] = useState(true);
  const [trainingPlans, setTrainingPlans] = useState<TrainingPlan[]>([]);
  const [uploadResult, setUploadResult] = useState<UploadResultState | null>(
    null,
  );
  const [adminReviewEnabled, setAdminReviewEnabled] = useState(() =>
    isTrainingCodeAdminReviewEnabled(),
  );
  const [reviewConfigSynced, setReviewConfigSynced] = useState(() =>
    hasSyncedTrainingCodeReviewConfig(),
  );
  const announcedApprovedRef = useRef<string | null>(null);
  const announcedRejectedRef = useRef<string | null>(null);
  const selectedPlanId = Form.useWatch('trainingProfile', form);
  const selectedPlan = useMemo(
    () => trainingPlans.find((plan) => plan.id === selectedPlanId),
    [selectedPlanId, trainingPlans],
  );

  const approvalStatus = String(
    uploadResult?.approvalStatus || 'PENDING',
  ).toUpperCase();
  /** 普通用户读不到系统配置：展示侧一律按管理员审核链路处理 */
  const ownerAdminReviewMode = !access.isAdmin || adminReviewEnabled;
  const facing = resolveOwnerFacingApproval({
    approvalStatus,
    reviewDisposition: uploadResult?.reviewDisposition,
    adminReviewMode: ownerAdminReviewMode,
  });
  const isApproved = facing.status === 'APPROVED';
  const isRejected = facing.status === 'REJECTED';
  const isRevoked = facing.status === 'REVOKED';
  const isSettled = isApproved || isRejected || isRevoked;

  const applyDetailToUploadResult = useCallback(
    (codeVersionId: string, detail: Record<string, any>) => {
      const rawStatus =
        normalizeCodeApprovalStatus(detail.approvalStatus) ||
        String(detail.approvalStatus || '')
          .trim()
          .toUpperCase();
      const reviewDisposition = String(
        detail.reviewDisposition || detail.riskAssessment?.disposition || '',
      ).toUpperCase();
      const facingStatus = resolveOwnerFacingApproval({
        approvalStatus: rawStatus,
        reviewDisposition,
        // 上传页普通用户始终按管理员审核展示；管理员以开关为准
        adminReviewMode: !access.isAdmin || adminReviewEnabled,
      }).status;

      setUploadResult((prev) => {
        if (!prev || prev.codeVersionId !== codeVersionId) return prev;
        return {
          ...prev,
          // 展示与本地登记用归一后的状态；系统 BLOCK 在管理员审核下记为 PENDING
          approvalStatus: facingStatus || rawStatus || prev.approvalStatus,
          status: detail.status || prev.status,
          validationStatus: detail.validationStatus || prev.validationStatus,
          validationPolicyVersion:
            detail.validationPolicyVersion || prev.validationPolicyVersion,
          artifactSha256: detail.artifactSha256 || prev.artifactSha256,
          fileName: detail.fileName || prev.fileName,
          trainingProfile: detail.trainingProfile || prev.trainingProfile,
          reviewDisposition: reviewDisposition || prev.reviewDisposition,
          riskLevel: detail.riskLevel || prev.riskLevel,
        };
      });
      if (facingStatus === 'PENDING' || rawStatus === 'PENDING') {
        setAdminReviewEnabled(true);
      }
      if (facingStatus === 'APPROVED') {
        markPendingCodeApproved(codeVersionId);
        if (announcedApprovedRef.current !== codeVersionId) {
          announcedApprovedRef.current = codeVersionId;
          message.success('训练代码已审核通过，可发起训练');
        }
      }
      if (facingStatus === 'REJECTED') {
        markPendingCodeStatus(codeVersionId, 'REJECTED');
        if (announcedRejectedRef.current !== codeVersionId) {
          announcedRejectedRef.current = codeVersionId;
          message.error('该训练代码版本未通过审核，不能用于发起训练');
        }
      } else if (facingStatus === 'PENDING' && rawStatus === 'REJECTED') {
        // 系统 BLOCK 等：按审核中登记，不提示「已拒绝」
        markPendingCodeStatus(codeVersionId, 'PENDING');
      }
      if (facingStatus === 'REVOKED') {
        markPendingCodeStatus(codeVersionId, 'REVOKED');
      }
      return facingStatus;
    },
    [access.isAdmin, adminReviewEnabled],
  );

  const refreshUploadStatus = useCallback(
    async (codeVersionId: string, opts?: { silent?: boolean }) => {
      const id = codeVersionId?.trim();
      if (!id) return undefined;
      if (!opts?.silent) setStatusRefreshing(true);
      try {
        const res = await getCodeVersionDetail(id, {
          skipErrorHandler: true,
        });
        if (res?.success === false || !res?.data) {
          if (!opts?.silent) {
            message.warning(
              res?.errorMessage || '暂未能刷新审核状态，请稍后重试',
            );
          }
          return undefined;
        }
        return applyDetailToUploadResult(id, res.data);
      } catch (error: any) {
        if (!opts?.silent) {
          message.error(getApiErrorMessage(error, '刷新审核状态失败'));
        }
        return undefined;
      } finally {
        if (!opts?.silent) setStatusRefreshing(false);
      }
    },
    [applyDetailToUploadResult],
  );

  // 未终态时轮询审批状态（通过 / 拒绝 / 撤销后停止）
  useEffect(() => {
    const id = uploadResult?.codeVersionId?.trim();
    if (!id || isSettled) return;

    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let attempts = 0;
    const maxAttempts = 12;

    const tick = async () => {
      if (cancelled) return;
      attempts += 1;
      await refreshUploadStatus(id, { silent: true });
      if (cancelled) return;
      if (attempts < maxAttempts) {
        timer = setTimeout(tick, attempts <= 3 ? 1500 : 3000);
      }
    };

    timer = setTimeout(tick, 600);
    return () => {
      cancelled = true;
      if (timer) clearTimeout(timer);
    };
  }, [uploadResult?.codeVersionId, isSettled, refreshUploadStatus]);

  useEffect(() => {
    let active = true;
    void syncTrainingCodeReviewConfigFromServer()
      .then((cfg) => {
        if (!active) return;
        const synced = cfg.syncedFromServer === true;
        setReviewConfigSynced(synced);
        // 未同步成功（普通用户常 403）时按需审核展示，禁止误报「已关闭」
        setAdminReviewEnabled(
          synced ? !!cfg.enableTrainingCodeAdminReview : true,
        );
      })
      .catch(() => undefined);
    return () => {
      active = false;
    };
  }, []);

  useEffect(() => {
    let active = true;
    setPlansLoading(true);
    fetchTrainingPlans({ skipErrorHandler: true })
      .then((res) => {
        if (!active) return;
        const plans = (res?.data ?? []).filter((plan) => plan.enabled);
        setTrainingPlans(plans);
        const current = form.getFieldValue('trainingProfile');
        const fallback =
          plans.find((plan) => plan.id === CONSISTENCY_TRAINING_PROFILE) ??
          plans[0];
        if (!plans.some((plan) => plan.id === current) && fallback) {
          form.setFieldValue('trainingProfile', fallback.id);
        }
        if (!fallback) {
          message.error('没有可用训练方案，请检查后端 training-plans 配置');
        }
      })
      .catch((error) => {
        if (active) {
          message.error(getApiErrorMessage(error, '加载训练方案失败'));
        }
      })
      .finally(() => {
        if (active) setPlansLoading(false);
      });
    return () => {
      active = false;
    };
  }, [form]);

  const handleUpload = async () => {
    const values = await form.validateFields();
    const file = (values.file as UploadFile[])?.[0]?.originFileObj as
      | File
      | undefined;
    if (!file) {
      message.warning('请选择训练代码 zip 文件');
      return;
    }
    if (!file.name.toLowerCase().endsWith('.zip')) {
      message.error('仅支持 .zip 格式');
      return;
    }

    setUploading(true);
    announcedApprovedRef.current = null;
    announcedRejectedRef.current = null;
    try {
      const trainingProfile =
        values.trainingProfile || CONSISTENCY_TRAINING_PROFILE;
      const res = await uploadCodeZip(
        {
          file,
          codeName: values.codeName.trim(),
          trainingProfile,
          remark: values.remark?.trim(),
        },
        { skipErrorHandler: true },
      );
      if (res?.success === false) {
        message.error(res?.errorMessage || '训练代码上传失败');
        return;
      }
      const data = res?.data;
      if (!data?.codeVersionId) {
        message.error('上传成功，但未返回版本编号');
        return;
      }
      const reviewDisposition = String(
        (data as { reviewDisposition?: string }).reviewDisposition || '',
      ).toUpperCase();
      const rawStatus =
        normalizeCodeApprovalStatus(data.approvalStatus) ||
        String(data.approvalStatus || 'PENDING').toUpperCase();
      const ownerAdminReviewMode = !access.isAdmin || adminReviewEnabled;
      const initialFacing = resolveOwnerFacingApproval({
        approvalStatus: rawStatus,
        reviewDisposition,
        adminReviewMode: ownerAdminReviewMode,
      });
      const initialStatus = initialFacing.status;

      setUploadResult({
        codeVersionId: data.codeVersionId,
        approvalStatus: initialStatus,
        status: data.status,
        validationStatus: (data as { validationStatus?: string })
          .validationStatus,
        fileName: data.fileName,
        trainingProfile: data.trainingProfile || trainingProfile,
        reviewDisposition:
          (data as { reviewDisposition?: string }).reviewDisposition ||
          undefined,
        riskLevel: (data as { riskLevel?: string }).riskLevel,
      });

      // 上传接口已返回 APPROVED（后端侧自动通过）时，直接成功，勿再调审批接口
      if (initialStatus === 'APPROVED') {
        markPendingCodeApproved(data.codeVersionId);
        announcedApprovedRef.current = data.codeVersionId;
        message.success('训练代码已上传并审核通过');
        return;
      }

      if (initialStatus === 'PENDING') {
        setAdminReviewEnabled(true);
      }

      // 普通用户没有审批权限，不要调 approve（否则会弹出「代码版本审批失败」）
      // 仅管理员且已确认关闭人工审核时，才走前端自动审批旁路
      if (access.isAdmin && reviewConfigSynced && !adminReviewEnabled) {
        try {
          const approved = await autoApproveCodeVersionIfEnabled(
            data.codeVersionId,
            {
              trainingProfile: data.trainingProfile || trainingProfile,
              skipErrorHandler: true,
            },
          );
          setUploadResult((prev) =>
            prev
              ? {
                  ...prev,
                  approvalStatus: approved?.approvalStatus || 'APPROVED',
                }
              : prev,
          );
          markPendingCodeApproved(data.codeVersionId);
          announcedApprovedRef.current = data.codeVersionId;
          message.success('训练代码已上传并自动审核通过');
          return;
        } catch {
          // 不向用户展示审批接口错误，改为对账真实状态
          const latest = await refreshUploadStatus(data.codeVersionId, {
            silent: true,
          });
          if (latest === 'APPROVED') {
            return;
          }
        }
      }

      if (initialStatus !== 'APPROVED') {
        upsertPendingCodeVersion({
          codeVersionId: data.codeVersionId,
          codeAssetId: data.codeAssetId,
          codeAssetName: values.codeName.trim(),
          fileName: data.fileName,
          trainingProfile: data.trainingProfile || trainingProfile,
          approvalStatus: initialStatus || 'PENDING',
          sizeBytes: data.sizeBytes,
          source: 'upload',
        });
      }
      message.success(
        initialStatus === 'REJECTED'
          ? '训练代码已上传，但未通过审核'
          : '训练代码已上传，正在等待审核',
      );
      // 人工审核模式下也立即对账一次（后端可能已默认通过）
      void refreshUploadStatus(data.codeVersionId, { silent: true });
    } catch (error: any) {
      const details =
        error?.info?.data?.details || error?.response?.data?.details;
      const detailText =
        details && typeof details === 'object'
          ? Object.entries(details)
              .map(([k, v]) => `${k}=${String(v)}`)
              .join('；')
          : '';
      const base = getApiErrorMessage(error, '训练代码上传失败');
      message.error(detailText ? `${base}（${detailText}）` : base);
    } finally {
      setUploading(false);
    }
  };

  const handleApprove = async () => {
    if (!uploadResult?.codeVersionId) return;
    setApproving(true);
    try {
      let checkResult:
        | Awaited<ReturnType<typeof checkCodeVersionForTraining>>
        | undefined;
      try {
        checkResult = await checkCodeVersionForTraining(
          uploadResult.codeVersionId,
          uploadResult.trainingProfile || CONSISTENCY_TRAINING_PROFILE,
          { skipErrorHandler: true },
        );
      } catch {
        // 由后续 approve 返回明确错误
      }
      if (checkResult?.success && checkResult.data) {
        setUploadResult((prev) =>
          prev
            ? {
                ...prev,
                approvalStatus:
                  checkResult?.data?.approvalStatus || prev.approvalStatus,
                validationStatus:
                  checkResult?.data?.validationStatus || prev.validationStatus,
                validationPolicyVersion:
                  checkResult?.data?.validationPolicyVersion ||
                  prev.validationPolicyVersion,
                artifactSha256:
                  checkResult?.data?.artifactSha256 || prev.artifactSha256,
              }
            : prev,
        );
      }
      const res = await approveCodeVersion(uploadResult.codeVersionId, {
        skipErrorHandler: true,
      });
      if (res?.success === false) {
        // 审批接口失败时仍对账：可能实际已通过
        const latest = await refreshUploadStatus(uploadResult.codeVersionId, {
          silent: true,
        });
        if (latest === 'APPROVED') return;
        message.error(res?.errorMessage || '审核失败');
        return;
      }
      setUploadResult((prev) =>
        prev
          ? {
              ...prev,
              approvalStatus: res?.data?.approvalStatus || 'APPROVED',
            }
          : prev,
      );
      markPendingCodeApproved(uploadResult.codeVersionId);
      announcedApprovedRef.current = uploadResult.codeVersionId;
      message.success(
        `审核通过${res?.data?.decisionSource ? `（${res.data.decisionSource}）` : ''}，可在训练代码列表中查看`,
      );
    } catch (error: any) {
      const latest = await refreshUploadStatus(uploadResult.codeVersionId, {
        silent: true,
      });
      if (latest === 'APPROVED') return;
      message.error(getApiErrorMessage(error, '审核失败'));
    } finally {
      setApproving(false);
    }
  };
  return (
    <PageContainer
      title="上传训练代码"
      subTitle="上传 zip 训练代码包，校验通过后可用于训练"
      onBack={() => history.push('/task/code/list')}
      breadcrumb={{
        items: [
          {
            title: (
              <a onClick={() => history.push('/task/code/list')}>训练代码</a>
            ),
          },
          { title: '上传训练代码' },
        ],
      }}
    >
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="上传说明"
        description={
          <span>
            zip 须包含入口脚本{' '}
            <Typography.Text code>
              {selectedPlan?.execution?.entrypoint || '请先选择训练方案'}
            </Typography.Text>
            。上传后平台会自动校验；需要人工处理时会显示“待审核”。
          </span>
        }
      />

      {uploadResult ? (
        <>
          {isRejected && (
            <Alert
              type="error"
              showIcon
              style={{ marginBottom: 16 }}
              message="该训练代码版本未通过审核"
              description="该版本不能用于训练，可到训练代码列表查看详情。"
            />
          )}
          {isRevoked && (
            <Alert
              type="warning"
              showIcon
              style={{ marginBottom: 16 }}
              message="该版本的批准已被撤销"
              description="该版本不能用于训练。"
            />
          )}
          {!isSettled && (
            <Alert
              type="warning"
              showIcon
              style={{ marginBottom: 16 }}
              message="等待审核结果"
              description="可先返回训练代码列表继续其他操作。"
            />
          )}
          {isApproved && (
            <Alert
              type="success"
              showIcon
              style={{ marginBottom: 16 }}
              message="已审核通过，可直接发起训练"
              description="点击下方「用于发起训练」将跳转到训练创建页，并自动选中本次上传的代码版本。"
            />
          )}
          <Descriptions
            bordered
            size="small"
            column={1}
            style={{ marginBottom: 16 }}
          >
            <Descriptions.Item label="版本编号">
              <Typography.Text copyable code>
                {uploadResult.codeVersionId}
              </Typography.Text>
            </Descriptions.Item>
            <Descriptions.Item label="审核状态">
              <Tag
                color={
                  facing.tone === 'success'
                    ? 'success'
                    : facing.tone === 'error'
                      ? 'error'
                      : facing.tone === 'warning'
                        ? 'warning'
                        : 'default'
                }
              >
                {facing.label}
              </Tag>
              {statusRefreshing ? (
                <Typography.Text type="secondary" style={{ marginLeft: 8 }}>
                  刷新中…
                </Typography.Text>
              ) : null}
            </Descriptions.Item>
            {uploadResult.status && (
              <Descriptions.Item label="就绪状态">
                <Tag
                  color={
                    String(uploadResult.status).toUpperCase() === 'READY'
                      ? 'success'
                      : 'default'
                  }
                >
                  {uploadResult.status}
                </Tag>
              </Descriptions.Item>
            )}
            {uploadResult.validationStatus && (
              <Descriptions.Item label="校验状态">
                <Tag
                  color={
                    uploadResult.validationStatus === 'PASSED'
                      ? 'success'
                      : 'default'
                  }
                >
                  {uploadResult.validationStatus}
                </Tag>
              </Descriptions.Item>
            )}
            {uploadResult.fileName && (
              <Descriptions.Item label="文件名">
                {uploadResult.fileName}
              </Descriptions.Item>
            )}
            {uploadResult.validationPolicyVersion && (
              <Descriptions.Item label="校验策略版本">
                <Typography.Text code>
                  {uploadResult.validationPolicyVersion}
                </Typography.Text>
              </Descriptions.Item>
            )}
            {uploadResult.artifactSha256 && (
              <Descriptions.Item label="artifactSha256">
                <Typography.Text code style={{ fontSize: 12 }}>
                  {uploadResult.artifactSha256}
                </Typography.Text>
              </Descriptions.Item>
            )}
          </Descriptions>
          <Space wrap>
            {access.isAdmin && facing.status === 'PENDING' && (
              <Button
                type="primary"
                loading={approving}
                onClick={handleApprove}
              >
                审核通过
              </Button>
            )}
            {access.isAdmin && (
              <Button onClick={() => history.push('/task/code/pending')}>
                打开待审核页
              </Button>
            )}
            <Button
              loading={statusRefreshing}
              onClick={() =>
                void refreshUploadStatus(uploadResult.codeVersionId)
              }
            >
              刷新状态
            </Button>
            <Button
              onClick={() =>
                history.push(
                  `/task/code/detail/${encodeURIComponent(uploadResult.codeVersionId)}`,
                  { from: 'upload' },
                )
              }
            >
              查看详情
            </Button>
            <Button onClick={() => history.push('/task/code/list')}>
              返回列表
            </Button>
            <Button
              type="primary"
              disabled={!isApproved}
              onClick={() =>
                history.push(
                  `/task/create?codeVersionId=${encodeURIComponent(uploadResult.codeVersionId)}`,
                )
              }
            >
              用于发起训练
            </Button>
            <Button
              onClick={() => {
                setUploadResult(null);
                announcedApprovedRef.current = null;
                announcedRejectedRef.current = null;
                form.resetFields();
              }}
            >
              继续上传
            </Button>
          </Space>
        </>
      ) : (
        <Form
          form={form}
          layout="vertical"
          initialValues={{
            trainingProfile: CONSISTENCY_TRAINING_PROFILE,
          }}
          style={{ maxWidth: 640 }}
        >
          <Form.Item
            name="codeName"
            label="代码资产名称"
            rules={[{ required: true, message: '请输入代码资产名称' }]}
          >
            <Input placeholder="例如：consistency-train-code" />
          </Form.Item>
          <Form.Item
            name="trainingProfile"
            label="训练方案"
            extra={
              selectedPlan
                ? `入口脚本：${selectedPlan.execution.entrypoint}`
                : '训练方案由平台统一配置'
            }
            rules={[{ required: true, message: '请选择训练方案' }]}
          >
            <Select
              loading={plansLoading}
              disabled={plansLoading || !trainingPlans.length}
              options={trainingPlans.map((plan) => ({
                value: plan.id,
                label: `${plan.displayName} (${plan.id})`,
              }))}
            />
          </Form.Item>
          <Form.Item name="remark" label="备注（可选）">
            <Input.TextArea
              rows={3}
              placeholder="说明训练代码用途"
              maxLength={200}
              showCount
            />
          </Form.Item>
          <Form.Item
            name="file"
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
                    return Promise.reject(new Error('请选择 zip 文件'));
                  }
                  return Promise.resolve();
                },
              },
            ]}
            extra={`仅支持 .zip；允许 .py/.json/.jsonl/.yaml/.yml/.txt/.md，禁止脚本执行器与二进制可执行文件；须包含 ${
              selectedPlan?.execution?.entrypoint || '所选方案入口脚本'
            }`}
          >
            <Upload accept=".zip" maxCount={1} beforeUpload={() => false}>
              <Button icon={<UploadOutlined />}>选择训练代码 zip</Button>
            </Upload>
          </Form.Item>
          <Space>
            <Button onClick={() => history.push('/task/code/list')}>
              取消
            </Button>
            <Button type="primary" loading={uploading} onClick={handleUpload}>
              上传
            </Button>
          </Space>
        </Form>
      )}
    </PageContainer>
  );
};

export default TrainingCodeUpload;
