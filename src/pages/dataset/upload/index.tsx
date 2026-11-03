import { UploadOutlined } from '@ant-design/icons';
import { PageContainer } from '@ant-design/pro-components';
import { history, useSearchParams } from '@umijs/max';
import type { UploadFile } from 'antd';
import {
  Alert,
  Button,
  Checkbox,
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
import React, { useEffect, useMemo, useState } from 'react';
import { usePageTour } from '@/components/Guide/usePageTour';
import { UPLOAD_CONFIG } from '@/constants/platform';
import type { DatasetType, MultimodalSampleGrouping } from '@/services/dataset';
import {
  calcUploadPercent,
  datasetUploadProgress,
  fetchDatasetDetail,
  uploadDataset,
} from '@/services/platform';
import { getApiErrorMessage } from '@/utils/apiError';
import {
  DATASET_VERSION_DESC_PLACEHOLDER,
  DATASET_VERSION_FORMAT_HINT,
  datasetVersionDescFormRules,
  datasetVersionFormRules,
  suggestNextDatasetVersion,
} from '@/utils/datasetVersion';
import { saveImportJobId } from '@/utils/importJobStorage';
import {
  buildDatasetFileFingerprint,
  LS_DATASET_UPLOAD_FP,
  LS_DATASET_UPLOAD_ID,
} from '@/utils/uploadResume';
import type {
  DatasetDirectory,
  RobotDataFormat,
  VisualFileLayout,
} from './datasetUploadUi.mjs';
import {
  DATASET_DIRECTORY_OPTIONS,
  directoryFromBackendType,
  inheritedDatasetIdentity,
  ROBOT_DATA_FORMAT_OPTIONS,
  resolveDatasetUploadMetadata,
  VISUAL_FILE_LAYOUT_OPTIONS,
  visualLayoutFromSpecId,
  visualUploadViolation,
} from './datasetUploadUi.mjs';

const POINT_CLOUD_ACCEPT = '.ply,.pcd,.zip';

function isPointCloudFileName(fileName: string) {
  const ext = fileName.split('.').pop()?.toLowerCase();
  return ext === 'ply' || ext === 'pcd' || ext === 'zip';
}

const ROBOT_ACCEPT = '.xml,.yaml,.yml,.zip';
const LEROBOT_ACCEPT = '.zip';
const VISUAL_ACCEPT = '.jpg,.jpeg,.png,.bmp,.gif,.webp,.tif,.tiff,.zip';
const TEXT_ACCEPT = '.txt,.json,.jsonl,.csv,.xlsx,.xls,.pdf,.docx,.zip';
const OTHER_ACCEPT =
  '.jpg,.jpeg,.png,.bmp,.gif,.webp,.tif,.tiff,.txt,.json,.jsonl,.csv,.xlsx,.xls,.pdf,.docx,.xml,.yaml,.yml,.ply,.pcd,.parquet,.mp4,.mkv,.md,.zip';

function isRobotFileName(fileName: string) {
  const ext = fileName.split('.').pop()?.toLowerCase();
  return ext === 'xml' || ext === 'yaml' || ext === 'yml' || ext === 'zip';
}

/**
 * 数据集上传：CV/NLP/POINT_CLOUD、版本、版本描述、单文件分片与 CV 多文件文件夹（module2-api-doc）
 */
const DatasetUpload: React.FC = () => {
  const [searchParams] = useSearchParams();
  const [form] = Form.useForm();
  const directoryCategory = Form.useWatch('directoryCategory', form) as
    | DatasetDirectory
    | undefined;
  const visualFileLayout = Form.useWatch('visualFileLayout', form) as
    | VisualFileLayout
    | undefined;
  const robotDataFormat = Form.useWatch('robotDataFormat', form) as
    | RobotDataFormat
    | undefined;
  const sampleGrouping = Form.useWatch('sampleGrouping', form) as
    | MultimodalSampleGrouping
    | undefined;
  const [uploading, setUploading] = useState(false);
  const [uploadPercent, setUploadPercent] = useState(0);
  const [merging, setMerging] = useState(false);
  const [resumeHint, setResumeHint] = useState<string | null>(null);
  const [existingVersions, setExistingVersions] = useState<string[]>([]);
  const [prefillLoading, setPrefillLoading] = useState(false);
  const [inheritedIdentity, setInheritedIdentity] = useState<{
    id: string;
    name: string;
    type: DatasetType;
    directory: DatasetDirectory;
    artifactSpecId?: string;
  }>();

  // 引导段 S1：数据集上传讲解
  const tourProps = usePageTour(1, { ready: !prefillLoading });

  const assetId = searchParams.get('assetId') ?? undefined;
  const isNewVersionUpload = !!assetId;
  const uploadMetadata = isNewVersionUpload
    ? inheritedIdentity?.type === 'CV'
      ? resolveDatasetUploadMetadata('VISUAL', visualFileLayout, undefined)
      : inheritedIdentity
        ? { type: inheritedIdentity.type }
        : undefined
    : resolveDatasetUploadMetadata(
        directoryCategory,
        visualFileLayout,
        robotDataFormat,
      );
  const datasetType = uploadMetadata?.type as DatasetType | undefined;
  const multipleFilesAllowed =
    datasetType === 'CV' && visualFileLayout === 'UNLABELED';

  useEffect(() => {
    const uploadId = localStorage.getItem(LS_DATASET_UPLOAD_ID);
    if (!uploadId) {
      return;
    }

    let cancelled = false;
    void (async () => {
      try {
        const res = await datasetUploadProgress(uploadId, {
          skipErrorHandler: true,
        });
        if (cancelled) return;
        const progress = res?.data;
        if (!progress) {
          setResumeHint(
            '检测到本地续传记录，但无法查询服务端进度。请重新选择同一文件后提交。',
          );
          return;
        }

        const percent = calcUploadPercent(progress);
        const fileLabel = progress.fileName || '文件';

        if (progress.status === 'COMPLETED') {
          setResumeHint(
            `检测到已完成的上传会话（${fileLabel}）。若需继续操作，可清除续传记录后重新上传。`,
          );
          setUploadPercent(100);
          return;
        }

        if (progress.status === 'COMPLETING') {
          setMerging(true);
          setUploadPercent(100);
          setResumeHint(
            `服务端正在合并分片（${fileLabel}）。请勿重复提交，稍后可重新选择同一文件继续或等待合并完成。`,
          );
          return;
        }

        setUploadPercent(percent);
        setResumeHint(
          `检测到未完成上传：${fileLabel}，已传 ${progress.uploadedPartIndexes?.length ?? progress.uploadedChunks ?? 0}/${progress.totalChunks} 个分片（约 ${percent}%）。请保持数据集名称、版本、类型与上次一致，并重新选择同一文件后提交。`,
        );
      } catch {
        if (!cancelled) {
          setResumeHint(
            '检测到本地续传记录，但无法查询服务端进度。请重新选择同一文件后提交。',
          );
        }
      }
    })();

    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    const datasetName = searchParams.get('datasetName');
    const type = searchParams.get('type') as DatasetType | null;
    if (datasetName) {
      form.setFieldValue('name', datasetName);
    }
    const requestedDirectory = directoryFromBackendType(type);
    if (!assetId && requestedDirectory) {
      form.setFieldValue('directoryCategory', requestedDirectory);
      if (type === 'ROBOT' || type === 'LEROBOT') {
        form.setFieldValue(
          'robotDataFormat',
          type === 'LEROBOT' ? 'LEROBOT' : 'CONFIG',
        );
      }
    }

    if (!assetId) {
      setExistingVersions([]);
      setInheritedIdentity(undefined);
      return;
    }

    setPrefillLoading(true);
    fetchDatasetDetail(assetId, { skipErrorHandler: true })
      .then((res) => {
        const detail = res?.data;
        const identity = inheritedDatasetIdentity(detail);
        if (!detail || !identity) {
          throw new Error('数据集资产信息不完整');
        }
        setInheritedIdentity(identity);
        form.setFieldsValue({
          name: identity.name,
          directoryCategory: identity.directory,
          robotDataFormat:
            identity.type === 'LEROBOT'
              ? 'LEROBOT'
              : identity.type === 'ROBOT'
                ? 'CONFIG'
                : undefined,
          visualFileLayout:
            identity.type === 'CV'
              ? visualLayoutFromSpecId(identity.artifactSpecId)
              : undefined,
        });
        const versions = detail.versions.map((v) => v.version).filter(Boolean);
        setExistingVersions(versions);
        form.setFieldValue('version', suggestNextDatasetVersion(versions));
      })
      .catch(() => {
        setInheritedIdentity(undefined);
        message.error('未能加载数据集资产信息，请返回详情页后重试');
      })
      .finally(() => setPrefillLoading(false));
  }, [assetId, form, searchParams]);

  const versionRules = useMemo(
    () => datasetVersionFormRules(existingVersions),
    [existingVersions],
  );

  const clearResumeStorage = () => {
    localStorage.removeItem(LS_DATASET_UPLOAD_ID);
    localStorage.removeItem(LS_DATASET_UPLOAD_FP);
    setResumeHint(null);
    setMerging(false);
    setUploadPercent(0);
  };

  const handleSubmit = async (values: any) => {
    if (isNewVersionUpload && !inheritedIdentity) {
      message.error('数据集资产信息尚未加载完成，不能上传新版本');
      return;
    }
    if (!uploadMetadata) {
      message.error(
        directoryCategory === 'ROBOT'
          ? '请选择机器人数据格式'
          : '请选择视觉文件组织方式',
      );
      return;
    }
    const fileList = (values.files ?? []) as UploadFile[];
    const files = fileList
      .map((f) => f.originFileObj)
      .filter(Boolean) as File[];
    if (!files.length) {
      message.error('请选择要上传的文件');
      return;
    }
    const name = values.name?.trim();
    if (!name) {
      message.error('请输入数据集名称');
      return;
    }
    const version = (values.version || 'v1.0.0').trim();
    const type = uploadMetadata.type as DatasetType;
    const remark = values.remark?.trim();
    const multimodalGrouping = values.sampleGrouping as
      | MultimodalSampleGrouping
      | undefined;
    const manifestPath = values.manifestPath?.trim();
    const strictManifest = Boolean(values.strictManifest);
    const cvTaskType = uploadMetadata.cvTaskType;
    const annotationFormat = uploadMetadata.annotationFormat;

    const maxBytes = UPLOAD_CONFIG.DATASET.MAX_SIZE;
    for (const f of files) {
      if (f.size > maxBytes) {
        message.error(`单个文件不能超过 ${maxBytes / 1024 / 1024 / 1024}GB`);
        return;
      }
    }

    if (type === 'POINT_CLOUD') {
      if (files.length !== 1) {
        message.error('点云数据集仅支持上传单个 .ply、.pcd 或 .zip 文件');
        return;
      }
      if (!isPointCloudFileName(files[0].name)) {
        message.error('点云数据集仅支持 .ply、.pcd 或 .zip 格式');
        return;
      }
    }

    if (type === 'CV') {
      const visualError = visualUploadViolation(
        visualFileLayout,
        files.map((file) => file.name),
      );
      if (visualError) {
        message.error(visualError);
        return;
      }
    }

    if (type === 'MULTIMODAL') {
      if (files.length !== 1) {
        message.error('多模态数据集仅支持上传单个 zip 文件');
        return;
      }
      if (!files[0].name.toLowerCase().endsWith('.zip')) {
        message.error('多模态数据集仅支持 .zip 格式');
        return;
      }
    }

    if (type === 'ROBOT') {
      if (files.length !== 1) {
        message.error('机器人数据集仅支持上传单个配置文件或 zip');
        return;
      }
      if (!isRobotFileName(files[0].name)) {
        message.error('机器人数据集仅支持 .xml、.yaml、.yml 或 .zip 格式');
        return;
      }
    }

    if (type === 'LEROBOT') {
      if (files.length !== 1 || !files[0].name.toLowerCase().endsWith('.zip')) {
        message.error('LeRobot 数据集仅支持上传单个 LeRobot v3 zip 文件');
        return;
      }
    }

    setUploading(true);
    setMerging(false);
    setUploadPercent(0);
    const requestOpts = { skipErrorHandler: true } as const;

    try {
      let createdAssetId: string | undefined;
      let artifactSpecId: string | undefined;
      if (files.length === 1) {
        const file = files[0];
        const fp = buildDatasetFileFingerprint(
          file,
          name,
          version,
          type,
          annotationFormat,
          cvTaskType,
        );
        const uploadRes = await uploadDataset(
          {
            name,
            files,
            type,
            version,
            assetId,
            cvTaskType,
            annotationFormat,
            remark,
            sampleGrouping:
              type === 'MULTIMODAL'
                ? (multimodalGrouping ?? 'AUTO_DIRECTORY')
                : undefined,
            manifestPath:
              type === 'MULTIMODAL' &&
              (multimodalGrouping ?? 'AUTO_DIRECTORY') === 'MANIFEST'
                ? manifestPath
                : undefined,
            strictManifest:
              type === 'MULTIMODAL' &&
              (multimodalGrouping ?? 'AUTO_DIRECTORY') === 'MANIFEST'
                ? strictManifest
                : undefined,
            fileFingerprint: fp,
            onProgress: (p) => setUploadPercent(p),
            onMergeStatus: (status) => {
              setMerging(status === 'COMPLETING');
            },
            onUploadSession: ({ uploadId, fileFingerprint: fgp }) => {
              localStorage.setItem(LS_DATASET_UPLOAD_ID, uploadId);
              localStorage.setItem(LS_DATASET_UPLOAD_FP, fgp);
            },
          },
          requestOpts,
        );
        createdAssetId = uploadRes?.data?.assetId;
        artifactSpecId = uploadRes?.data?.artifactSpecId;
        if (type === 'MULTIMODAL' && uploadRes?.data?.importJobId) {
          const jobDatasetId =
            assetId || createdAssetId || uploadRes?.data?.assetId;
          if (jobDatasetId) {
            saveImportJobId(jobDatasetId, uploadRes.data.importJobId);
          }
          message.info(
            multimodalGrouping === 'MANIFEST'
              ? 'zip 上传完成，后台正在解析 manifest 并导入样本，请在详情页查看导入进度。'
              : 'zip 上传完成，后台正在按目录结构导入样本，请在详情页查看导入进度。',
          );
        }
      } else {
        if (type === 'NLP' || type === 'POINT_CLOUD') {
          message.error(
            type === 'POINT_CLOUD'
              ? '点云数据集仅支持单个 .ply、.pcd 或 .zip 文件'
              : 'NLP 数据集请将多个文件打包为 zip 后作为单个文件上传',
          );
          setUploading(false);
          return;
        }
        const uploadRes = await uploadDataset(
          {
            name,
            files,
            type: 'CV',
            version,
            cvTaskType,
            annotationFormat,
            remark,
          },
          requestOpts,
        );
        artifactSpecId = uploadRes?.data?.artifactSpecId;
        setUploadPercent(100);
      }
      clearResumeStorage();
      message.success(
        type === 'MULTIMODAL'
          ? 'zip 上传成功，正在后台导入样本'
          : artifactSpecId
            ? `上传成功，已识别训练规格：${artifactSpecId}`
            : '上传成功；当前版本未识别为可训练规格，可继续存储和下载',
      );
      const detailAssetId = assetId || createdAssetId;
      if (detailAssetId && type === 'MULTIMODAL') {
        history.push(`/dataset/detail/${encodeURIComponent(detailAssetId)}`);
      } else if (assetId) {
        history.push(`/dataset/detail/${encodeURIComponent(assetId)}`);
      } else {
        history.push('/dataset/list');
      }
    } catch (error: any) {
      message.error(getApiErrorMessage(error));
    } finally {
      setUploading(false);
      setMerging(false);
      setUploadPercent(0);
    }
  };

  const backPath = assetId
    ? `/dataset/detail/${encodeURIComponent(assetId)}`
    : '/dataset/list';

  return (
    <PageContainer
      title={isNewVersionUpload ? '上传新版本' : '上传数据集'}
      onBack={() => history.push(backPath)}
    >
      {isNewVersionUpload && inheritedIdentity && (
        <Descriptions
          bordered
          size="small"
          column={1}
          style={{ marginBottom: 16 }}
          title="继承的数据集资产信息"
        >
          <Descriptions.Item label="数据集名称">
            {inheritedIdentity.name}
          </Descriptions.Item>
          <Descriptions.Item label="目录类别">
            {
              DATASET_DIRECTORY_OPTIONS.find(
                (item) => item.value === inheritedIdentity.directory,
              )?.label
            }
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
      <Form
        form={form}
        onFinish={handleSubmit}
        layout="vertical"
        initialValues={{
          directoryCategory: 'VISUAL',
          version: 'v1.0.0',
          sampleGrouping: 'AUTO_DIRECTORY',
          strictManifest: false,
        }}
      >
        {isNewVersionUpload ? (
          <Form.Item name="name" hidden>
            <Input />
          </Form.Item>
        ) : (
          <Form.Item
            name="name"
            label="数据集名称"
            rules={[{ required: true, message: '请输入数据集名称' }]}
          >
            <Input placeholder="请输入数据集名称" data-tour="ds-name" />
          </Form.Item>
        )}
        <Form.Item
          name="version"
          label="版本号"
          rules={versionRules}
          extra={DATASET_VERSION_FORMAT_HINT}
        >
          <Input
            placeholder="例如 v1.0.0"
            disabled={prefillLoading}
            data-tour="ds-version"
          />
        </Form.Item>
        {!isNewVersionUpload && (
          <Form.Item
            name="directoryCategory"
            label="目录类别"
            rules={[{ required: true, message: '请选择目录类别' }]}
            extra="类别只用于管理和检索；真正能否训练，由文件内容识别结果和训练方案决定。"
          >
            <Select
              data-tour="ds-category"
              options={DATASET_DIRECTORY_OPTIONS.map((item) => ({
                value: item.value,
                label: item.label,
              }))}
              onChange={(value) => {
                form.setFieldValue('files', []);
                form.setFieldValue('visualFileLayout', undefined);
                form.setFieldValue('robotDataFormat', undefined);
                if (value === 'MULTIMODAL') {
                  form.setFieldValue('sampleGrouping', 'AUTO_DIRECTORY');
                  form.setFieldValue('manifestPath', undefined);
                }
              }}
            />
          </Form.Item>
        )}
        {directoryCategory === 'VISUAL' && (
          <Form.Item
            name="visualFileLayout"
            label="文件组织方式"
            rules={[{ required: true, message: '请选择文件组织方式' }]}
            extra="这里只选文件实际结构，不再分别组合“CV 子任务”和“标注格式”。"
          >
            <Select
              disabled={
                isNewVersionUpload &&
                Boolean(
                  visualLayoutFromSpecId(inheritedIdentity?.artifactSpecId),
                )
              }
              options={VISUAL_FILE_LAYOUT_OPTIONS.map((item) => ({
                value: item.value,
                label: item.label,
              }))}
              onChange={() => form.setFieldValue('files', [])}
            />
          </Form.Item>
        )}
        {directoryCategory === 'ROBOT' && !isNewVersionUpload && (
          <Form.Item
            name="robotDataFormat"
            label="机器人数据格式"
            rules={[{ required: true, message: '请选择机器人数据格式' }]}
          >
            <Select
              options={ROBOT_DATA_FORMAT_OPTIONS.map((item) => ({
                value: item.value,
                label: item.label,
              }))}
              onChange={() => form.setFieldValue('files', [])}
            />
          </Form.Item>
        )}
        {directoryCategory === 'OTHER' && (
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 16 }}
            message="暂未归类数据集"
            description="OTHER 只用于保存和检索暂时无法归类的数据；仅支持平台安全白名单文件或包含这些文件的 zip，是否可训练由后续选择的训练方案决定。"
          />
        )}
        {datasetType === 'MULTIMODAL' && (
          <>
            <Form.Item
              name="sampleGrouping"
              label="样本分组方式"
              rules={[{ required: true, message: '请选择样本分组方式' }]}
              extra="AUTO_DIRECTORY 无需 manifest；MANIFEST 需在 zip 内提供 manifest 索引文件"
            >
              <Select>
                <Select.Option value="AUTO_DIRECTORY">
                  自动目录（AUTO_DIRECTORY，推荐）
                </Select.Option>
                <Select.Option value="MANIFEST">
                  Manifest 索引（MANIFEST）
                </Select.Option>
              </Select>
            </Form.Item>
            {sampleGrouping === 'MANIFEST' && (
              <>
                <Form.Item
                  name="manifestPath"
                  label="Manifest 路径"
                  extra="zip 内 manifest 相对路径，留空则默认 manifest.json"
                >
                  <Input placeholder="例如 metadata/manifest.json" />
                </Form.Item>
                <Form.Item
                  name="strictManifest"
                  valuePropName="checked"
                  extra="开启后，zip 内未被 manifest 声明的普通文件会导致导入失败"
                >
                  <Checkbox>严格 Manifest（strictManifest）</Checkbox>
                </Form.Item>
              </>
            )}
            {sampleGrouping === 'AUTO_DIRECTORY' && (
              <Alert
                type="info"
                showIcon
                style={{ marginBottom: 16 }}
                message="AUTO_DIRECTORY zip 结构要求"
                description={
                  <>
                    zip 根目录须直接为样本目录（如
                    scene_001/、scene_002/），根级不能有普通文件。
                    每个样本目录内：annotations/ 下为标注文件，其余为数据文件。
                    目录名即 externalId；无需 manifest.json。
                  </>
                }
              />
            )}
          </>
        )}
        <Form.Item
          name="remark"
          label="版本描述"
          rules={datasetVersionDescFormRules()}
          extra="记录本版本的更新原因与内容，便于长期维护与训练选型"
        >
          <Input.TextArea
            rows={4}
            placeholder={DATASET_VERSION_DESC_PLACEHOLDER}
            showCount
            maxLength={2000}
          />
        </Form.Item>
        <Form.Item
          name="files"
          label="文件"
          valuePropName="fileList"
          getValueFromEvent={(e) => e?.fileList ?? []}
          rules={[
            {
              required: true,
              validator: (_, value) => {
                const list = Array.isArray(value) ? value : [];
                if (
                  !list.length ||
                  !list.some((x: UploadFile) => x.originFileObj)
                ) {
                  return Promise.reject(new Error('请上传文件'));
                }
                return Promise.resolve();
              },
            },
          ]}
        >
          <Upload
            multiple={multipleFilesAllowed}
            accept={
              datasetType === 'POINT_CLOUD'
                ? POINT_CLOUD_ACCEPT
                : datasetType === 'MULTIMODAL'
                  ? '.zip'
                  : datasetType === 'ROBOT'
                    ? ROBOT_ACCEPT
                    : datasetType === 'LEROBOT'
                      ? LEROBOT_ACCEPT
                      : datasetType === 'NLP'
                        ? TEXT_ACCEPT
                        : datasetType === 'OTHER'
                          ? OTHER_ACCEPT
                          : datasetType === 'CV'
                            ? visualFileLayout === 'IMAGE_FOLDER' ||
                              visualFileLayout === 'YOLO'
                              ? '.zip'
                              : VISUAL_ACCEPT
                            : undefined
            }
            beforeUpload={() => false}
            onChange={(e) => {
              let fileList = e.fileList ?? [];
              if (!multipleFilesAllowed && fileList.length > 1) {
                fileList = fileList.slice(-1);
                message.info('当前类型仅支持单个文件，已保留最新选择');
              }
              form.setFieldValue('files', fileList);
            }}
          >
            <Button icon={<UploadOutlined />} data-tour="ds-upload">
              {datasetType === 'POINT_CLOUD'
                ? '选择点云文件（.ply / .pcd / .zip）'
                : datasetType === 'MULTIMODAL'
                  ? '选择多模态 zip（单文件分片上传）'
                  : datasetType === 'ROBOT'
                    ? '选择机器人配置（.xml / .yaml / .yml / .zip）'
                    : datasetType === 'LEROBOT'
                      ? '选择 LeRobot v3 数据集（.zip）'
                      : datasetType === 'NLP'
                        ? '选择文本文件或 zip（单文件）'
                        : datasetType === 'OTHER'
                          ? '选择安全白名单文件或 zip（单文件）'
                          : visualFileLayout === 'YOLO'
                            ? '选择 YOLO zip（单文件）'
                            : visualFileLayout === 'IMAGE_FOLDER'
                              ? '选择 ImageFolder zip（单文件）'
                              : '选择图片或 zip（可多选图片）'}
            </Button>
          </Upload>
          <div style={{ marginTop: 8, color: '#999' }}>
            单文件最大 {UPLOAD_CONFIG.DATASET.MAX_SIZE / 1024 / 1024 / 1024}
            GB。
            {datasetType === 'POINT_CLOUD'
              ? ' 点云仅支持单个 .ply、.pcd 或 .zip；zip 内需至少包含一个 .ply 或 .pcd。'
              : datasetType === 'MULTIMODAL'
                ? sampleGrouping === 'MANIFEST'
                  ? ' MANIFEST 模式：zip 须含 manifest.json（或指定路径）；上传后 DRAFT，后台异步导入。'
                  : ' AUTO_DIRECTORY：zip 根目录为样本子目录，无需 manifest；上传后 DRAFT，后台异步导入。'
                : datasetType === 'ROBOT'
                  ? ' ROBOT：支持单文件 .xml/.yaml/.yml 或仅含配置类文件的 zip；上传完成后为 READY。'
                  : datasetType === 'LEROBOT'
                    ? ' LeRobot：上传标准 LeRobot v3 zip，包含 meta、data 与 videos 目录；上传完成后可按时序查看。'
                    : datasetType === 'NLP'
                      ? ' 文本类支持 txt/json/jsonl/csv/xlsx/xls/pdf/docx 或包含这些文件的 zip。'
                      : datasetType === 'OTHER'
                        ? ' OTHER：支持平台安全白名单中的单个文件或包含这些文件的 zip；是否可训练由训练方案决定。'
                        : visualFileLayout === 'YOLO'
                          ? ' YOLO：zip 内须有 data.yaml 和匹配的图片/标签。'
                          : visualFileLayout === 'IMAGE_FOLDER'
                            ? ' ImageFolder：按类别子目录组织图片，并整体打包为单个 zip。'
                            : ' 未标注图片：可多选图片或上传只含图片的 zip。'}
          </div>
        </Form.Item>
        {uploading && (
          <Form.Item
            label={merging ? '合并进度' : '上传进度'}
            extra={
              merging
                ? '分片已传完，服务端正在合并文件，请勿重复点击提交'
                : undefined
            }
          >
            <Progress
              percent={uploadPercent}
              status={merging ? 'active' : 'active'}
            />
          </Form.Item>
        )}
        <Form.Item>
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
              清除本地续传记录
            </Button>
            <Button
              type="primary"
              htmlType="submit"
              loading={uploading || prefillLoading}
              disabled={isNewVersionUpload && !inheritedIdentity}
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

export default DatasetUpload;
