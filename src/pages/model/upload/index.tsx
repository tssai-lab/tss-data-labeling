import { UploadOutlined } from '@ant-design/icons';
import { PageContainer } from '@ant-design/pro-components';
import { history, useSearchParams } from '@umijs/max';
import {
  Alert,
  Button,
  Descriptions,
  Form,
  Input,
  message,
  Progress,
  Select,
  Space,
  Tour,
  Upload,
} from 'antd';
import type { UploadFile } from 'antd/es/upload/interface';
import React, { useEffect, useMemo, useState } from 'react';
import { usePageTour } from '@/components/Guide/usePageTour';
import { MODEL_TYPE_VALUE_ENUM } from '@/constants/model';
import { UPLOAD_CONFIG } from '@/constants/platform';
import {
  fetchModelAssetDetail,
  modelUploadChunk,
  modelUploadComplete,
  modelUploadInit,
} from '@/services/platform';
import { getApiErrorMessage } from '@/utils/apiError';
import {
  formatAssetVersionLabel,
  getLatestAssetVersion,
} from '@/utils/datasetVersion';
import {
  MODEL_VERSION_FORMAT_HINT,
  modelNewVersionFormRules,
  suggestNextModelVersion,
} from '@/utils/modelVersion';
import {
  buildModelFileFingerprint,
  LS_MODEL_UPLOAD_FP,
  LS_MODEL_UPLOAD_ID,
} from '@/utils/uploadResume';
import {
  inheritedModelIdentity,
  isModelUploadCategory,
  MODEL_UPLOAD_CATEGORY_OPTIONS,
} from './modelUploadUi.mjs';

const CHUNK_FALLBACK = 5 * 1024 * 1024;

const ModelUpload: React.FC = () => {
  const [searchParams] = useSearchParams();
  const [form] = Form.useForm();
  const [uploading, setUploading] = useState(false);
  const [uploadPercent, setUploadPercent] = useState(0);
  const [resumeHint, setResumeHint] = useState<string | null>(null);
  const [existingVersions, setExistingVersions] = useState<string[]>([]);
  const [prefillLoading, setPrefillLoading] = useState(false);
  const [inheritedIdentity, setInheritedIdentity] = useState<{
    id: string;
    name: string;
    type: API.ModelItem['type'];
    remark: string;
    artifactSpecId?: string;
  }>();

  // 引导段 S2：模型上传讲解
  const tourProps = usePageTour(2, { ready: !prefillLoading });

  const assetId = searchParams.get('assetId') ?? undefined;
  const isNewVersionUpload = !!assetId;
  const selectedType = Form.useWatch('type', form);

  useEffect(() => {
    const id = localStorage.getItem(LS_MODEL_UPLOAD_ID);
    const fp = localStorage.getItem(LS_MODEL_UPLOAD_FP);
    if (id && fp) {
      setResumeHint(
        '检测到未完成的上传会话，请重新选择同一个模型文件继续上传。',
      );
    }
  }, []);

  useEffect(() => {
    const modelName = searchParams.get('modelName');
    const requestedType = searchParams.get('type');
    const version = searchParams.get('version');
    const type =
      !assetId && isModelUploadCategory(requestedType)
        ? requestedType?.toUpperCase()
        : undefined;
    if (modelName || type || version) {
      form.setFieldsValue({
        ...(modelName ? { modelName } : {}),
        ...(type ? { type } : {}),
        ...(version ? { version } : {}),
      });
    }

    if (!assetId) {
      setExistingVersions([]);
      setInheritedIdentity(undefined);
      return;
    }

    setPrefillLoading(true);
    fetchModelAssetDetail(assetId, { skipErrorHandler: true })
      .then((res) => {
        const detail = res?.data;
        const identity = inheritedModelIdentity(detail);
        if (!detail || !identity) {
          throw new Error('模型资产信息不完整');
        }
        setInheritedIdentity(identity);
        form.setFieldsValue({
          modelName: identity.name,
          type: identity.type,
          remark: identity.remark,
        });
        const versions = detail.versions.map((v) => v.version).filter(Boolean);
        setExistingVersions(versions);
        form.setFieldValue('version', suggestNextModelVersion(versions));
      })
      .catch(() => {
        setInheritedIdentity(undefined);
        message.error('未能加载模型资产信息，请返回详情页后重试');
      })
      .finally(() => setPrefillLoading(false));
  }, [assetId, form, searchParams]);

  const versionRules = useMemo(
    () =>
      isNewVersionUpload
        ? modelNewVersionFormRules(existingVersions)
        : [{ required: true, message: '请输入版本号' }],
    [existingVersions, isNewVersionUpload],
  );

  const latestVersionLabel = useMemo(() => {
    const latest = getLatestAssetVersion(existingVersions);
    return latest ? formatAssetVersionLabel(latest) : undefined;
  }, [existingVersions]);

  const clearResumeStorage = () => {
    localStorage.removeItem(LS_MODEL_UPLOAD_ID);
    localStorage.removeItem(LS_MODEL_UPLOAD_FP);
    setResumeHint(null);
  };

  const handleSubmit = async (values: any) => {
    if (isNewVersionUpload && !inheritedIdentity) {
      message.error('模型资产信息尚未加载完成，不能上传新版本');
      return;
    }
    const fileList = (values.file ?? []) as UploadFile[];
    const file = fileList[0]?.originFileObj as File | undefined;
    if (!file) {
      message.error('请选择模型文件');
      return;
    }
    if (
      !UPLOAD_CONFIG.MODEL.ACCEPT_TYPES.some((suffix) =>
        file.name.toLowerCase().endsWith(suffix),
      )
    ) {
      message.error('模型仅支持 .zip、.safetensors、.pt、.pth、.ckpt 或 .onnx');
      return;
    }
    if (file.size > UPLOAD_CONFIG.MODEL.MAX_SIZE) {
      message.error(
        `文件大小不能超过 ${UPLOAD_CONFIG.MODEL.MAX_SIZE / 1024 / 1024 / 1024}GB`,
      );
      return;
    }

    setUploading(true);
    setUploadPercent(0);
    const requestOpts = { skipErrorHandler: true } as const;

    try {
      const commitInfo = String(
        values.commitInfo ?? values.remark ?? '',
      ).trim();
      if (!commitInfo) {
        throw new Error('请填写本次更新说明');
      }
      if (commitInfo.length > 1024) {
        throw new Error('本次更新说明不能超过 1024 个字符');
      }

      let hyperParams: Record<string, unknown> | undefined;
      const hyperRaw = String(values.hyperParamsJson ?? '').trim();
      if (hyperRaw) {
        try {
          const parsed = JSON.parse(hyperRaw);
          if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
            throw new Error('超参必须是 JSON 对象');
          }
          hyperParams = parsed as Record<string, unknown>;
        } catch (e: any) {
          throw new Error(e?.message || '超参 JSON 格式不正确');
        }
      }

      const fileFingerprint = buildModelFileFingerprint(
        file,
        values.modelName,
        values.version,
        values.type,
      );
      const initRes = await modelUploadInit(
        {
          fileName: file.name,
          fileSize: file.size,
          fileFingerprint,
          commitInfo,
          modelName: values.modelName.trim(),
          modelVersion: values.version.trim(),
          taskType: values.type,
          remark: String(values.remark ?? '').trim() || commitInfo,
          ...(assetId ? { targetAssetId: assetId } : {}),
          ...(hyperParams ? { hyperParams } : {}),
        },
        requestOpts,
      );
      const initData = initRes?.data as API.ModelUploadInitResult | undefined;
      const uploadId = initData?.uploadId;
      if (!uploadId) {
        throw new Error('初始化上传失败：缺少 uploadId');
      }

      localStorage.setItem(LS_MODEL_UPLOAD_ID, uploadId);
      localStorage.setItem(LS_MODEL_UPLOAD_FP, fileFingerprint);

      const chunkSize =
        initData?.chunkSize && initData.chunkSize > 0
          ? initData.chunkSize
          : CHUNK_FALLBACK;
      const totalChunks =
        initData?.totalChunks && initData.totalChunks > 0
          ? initData.totalChunks
          : Math.max(1, Math.ceil(file.size / chunkSize));

      const uploaded = new Set(initData?.uploadedPartIndexes ?? []);
      let finishedParts = uploaded.size;

      for (let partIndex = 0; partIndex < totalChunks; partIndex += 1) {
        if (uploaded.has(partIndex)) {
          setUploadPercent(
            Math.min(100, Math.round((finishedParts / totalChunks) * 100)),
          );
          continue;
        }
        const start = partIndex * chunkSize;
        const end = Math.min(start + chunkSize, file.size);
        await modelUploadChunk(
          uploadId,
          partIndex,
          file.slice(start, end),
          requestOpts,
        );
        finishedParts += 1;
        setUploadPercent(
          Math.min(100, Math.round((finishedParts / totalChunks) * 100)),
        );
      }

      const completeRes = await modelUploadComplete(
        {
          uploadId,
          ...(assetId ? { assetId } : {}),
          modelName: values.modelName,
          version: values.version.trim(),
          type: values.type,
          remark: String(values.remark ?? '').trim() || commitInfo,
          commitInfo,
          ...(hyperParams ? { hyperParams } : {}),
        },
        requestOpts,
      );

      clearResumeStorage();
      const artifactSpecId = completeRes?.data?.artifactSpecId;
      message.success(
        artifactSpecId
          ? `上传成功，已识别训练规格：${artifactSpecId}`
          : '上传成功；当前版本未识别为可训练规格，可继续存储和下载',
      );
      if (assetId) {
        history.push(`/model/detail/${encodeURIComponent(assetId)}`);
      } else {
        history.push('/model/list');
      }
    } catch (error: any) {
      message.error(getApiErrorMessage(error));
    } finally {
      setUploading(false);
      setUploadPercent(0);
    }
  };

  const backPath = assetId
    ? `/model/detail/${encodeURIComponent(assetId)}`
    : '/model/list';

  return (
    <PageContainer
      title={isNewVersionUpload ? '上传模型新版本' : '上传模型'}
      onBack={() => history.push(backPath)}
    >
      {isNewVersionUpload && latestVersionLabel && (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          message="版本号要求"
          description={`当前最新版本为 ${latestVersionLabel}，新版本号必须大于该版本（${MODEL_VERSION_FORMAT_HINT}）。`}
        />
      )}
      {isNewVersionUpload && inheritedIdentity && (
        <Descriptions
          bordered
          size="small"
          column={1}
          style={{ marginBottom: 16 }}
          title="继承的模型资产信息"
        >
          <Descriptions.Item label="模型名称">
            {inheritedIdentity.name}
          </Descriptions.Item>
          <Descriptions.Item label="目录类别">
            {MODEL_TYPE_VALUE_ENUM[inheritedIdentity.type]?.text ??
              inheritedIdentity.type}
          </Descriptions.Item>
          <Descriptions.Item label="最近已识别训练规格">
            {inheritedIdentity.artifactSpecId ??
              '暂无（只代表可存储，不代表可训练）'}
          </Descriptions.Item>
        </Descriptions>
      )}
      {resumeHint && (
        <Alert
          type="info"
          showIcon
          closable
          onClose={() => setResumeHint(null)}
          message="断点续传"
          description={resumeHint}
          style={{ marginBottom: 16 }}
        />
      )}
      <Form form={form} onFinish={handleSubmit} layout="vertical">
        {isNewVersionUpload ? (
          <>
            <Form.Item name="modelName" hidden>
              <Input />
            </Form.Item>
            <Form.Item name="type" hidden>
              <Input />
            </Form.Item>
            <Form.Item name="remark" hidden>
              <Input />
            </Form.Item>
          </>
        ) : (
          <Form.Item
            name="modelName"
            label="模型名称"
            rules={[{ required: true, message: '请输入模型名称' }]}
          >
            <Input placeholder="请输入模型名称" data-tour="model-name" />
          </Form.Item>
        )}
        <Form.Item
          name="version"
          label="版本号"
          rules={versionRules}
          extra={
            isNewVersionUpload
              ? `须大于当前最新版本${latestVersionLabel ? `（${latestVersionLabel}）` : ''}`
              : MODEL_VERSION_FORMAT_HINT
          }
        >
          <Input placeholder="例如：v1.0.0 或 v2" />
        </Form.Item>
        {!isNewVersionUpload && (
          <>
            <Form.Item
              name="type"
              label="目录类别"
              rules={[{ required: true, message: '请选择目录类别' }]}
              extra="类别只用于管理和检索；是否能训练，由文件内容识别结果和训练方案共同决定。"
            >
              <Select
                data-tour="model-type"
                placeholder="请选择目录类别"
                options={MODEL_UPLOAD_CATEGORY_OPTIONS.map((option) => ({
                  value: option.value,
                  label: option.label,
                }))}
              />
            </Form.Item>
            {selectedType === 'POINT_CLOUD' && (
              <Alert
                type="info"
                showIcon
                style={{ marginBottom: 16 }}
                message="点云模型"
                description="请上传 zip 权重包；包内只允许模型相关文件，不能包含 .py 脚本。"
              />
            )}
            {selectedType === 'OTHER' && (
              <Alert
                type="info"
                showIcon
                style={{ marginBottom: 16 }}
                message="暂未归类模型"
                description="OTHER 只用于保存和检索暂时无法归类的模型；平台仍会执行文件安全校验，是否可训练由后续选择的训练方案决定。"
              />
            )}
            <Form.Item
              name="remark"
              label="资产备注"
              rules={[
                { required: true, message: '请输入资产备注' },
                {
                  validator: async (_, value) => {
                    if (value != null && !String(value).trim()) {
                      throw new Error('资产备注不能为空或纯空格');
                    }
                  },
                },
                { max: 200, message: '资产备注不能超过 200 个字符' },
              ]}
              extra="说明整份模型资产的用途或来源；以后上传新版本时自动继承。"
            >
              <Input.TextArea
                rows={3}
                placeholder="例如：ImageNet 预训练权重，供视觉任务使用"
                maxLength={200}
                showCount
                data-tour="model-remark"
              />
            </Form.Item>
          </>
        )}
        <Form.Item
          name="commitInfo"
          label="本次更新说明"
          rules={[
            { required: true, message: '请输入本次更新说明' },
            {
              validator: async (_, value) => {
                const text = String(value ?? '').trim();
                if (!text) {
                  throw new Error('本次更新说明不能为空或纯空格');
                }
                if (text.length > 1024) {
                  throw new Error('本次更新说明不能超过 1024 个字符');
                }
              },
            },
          ]}
          extra="只描述这个版本相对上一版本的变化。"
        >
          <Input.TextArea
            rows={2}
            placeholder="例如：v1 基线权重；或 fix: 调整学习率后重训"
            maxLength={1024}
            showCount
            data-tour="model-commit"
          />
        </Form.Item>
        <Form.Item
          name="hyperParamsJson"
          label="超参（JSON，可选）"
          extra='训练超参快照（可选）：须为 JSON 对象，例如 {"lr":0.001,"epochs":10}。不填则按空对象上传，不影响上传成功。'
          rules={[
            {
              validator: async (_, value) => {
                const text = String(value ?? '').trim();
                if (!text) return;
                try {
                  const parsed = JSON.parse(text);
                  if (
                    !parsed ||
                    typeof parsed !== 'object' ||
                    Array.isArray(parsed)
                  ) {
                    throw new Error('须为 JSON 对象');
                  }
                } catch {
                  throw new Error('超参 JSON 格式不正确');
                }
              },
            },
          ]}
        >
          <Input.TextArea
            rows={4}
            placeholder='可选，例如 {"imageSize":224,"epochs":3}'
          />
        </Form.Item>
        <Form.Item
          name="file"
          label="模型包"
          valuePropName="fileList"
          getValueFromEvent={(event) => event?.fileList ?? []}
          rules={[
            {
              required: true,
              validator: (_, value) => {
                const list = Array.isArray(value)
                  ? value
                  : (value?.fileList ?? []);
                if (!list?.length || !list[0]?.originFileObj) {
                  return Promise.reject(new Error('请上传模型包'));
                }
                return Promise.resolve();
              },
            },
          ]}
        >
          <Upload
            beforeUpload={() => false}
            maxCount={1}
            accept={UPLOAD_CONFIG.MODEL.ACCEPT_TYPES.join(',')}
            disabled={uploading}
            onChange={(event) => {
              form.setFieldValue('file', event.fileList ?? []);
            }}
          >
            <Button icon={<UploadOutlined />}>选择文件</Button>
          </Upload>
        </Form.Item>
        {uploading && (
          <Form.Item label="上传进度">
            <Progress percent={uploadPercent} status="active" />
          </Form.Item>
        )}
        <Form.Item
          extra={`支持 .zip、.safetensors、.pt、.pth、.ckpt、.onnx，大小限制 ${UPLOAD_CONFIG.MODEL.MAX_SIZE / 1024 / 1024 / 1024}GB。`}
        >
          <Space>
            <Button onClick={() => history.push(backPath)} disabled={uploading}>
              取消
            </Button>
            <Button
              danger
              type="default"
              disabled={uploading}
              onClick={clearResumeStorage}
            >
              清除续传记录
            </Button>
            <Button
              type="primary"
              htmlType="submit"
              loading={uploading || prefillLoading}
              disabled={isNewVersionUpload && !inheritedIdentity}
              data-tour="model-submit"
            >
              提交
            </Button>
          </Space>
        </Form.Item>
      </Form>
      <Tour {...tourProps} />
    </PageContainer>
  );
};

export default ModelUpload;
