import { history } from '@umijs/max';
import { Alert, Button, List, message, Space, Tag, Typography } from 'antd';
import React, { useMemo, useState } from 'react';
import { getModelDetail } from '@/services/model';
import {
  downloadObjectWithBrowser,
  getModelVersion,
  publishTaskModel,
} from '@/services/platform';
import {
  buildConsistencyArtifactItems,
  errorMessageFromDownloadError,
  isLikelyDirectoryPath,
  minioPathToObjectName,
  type TaskDetailInfo,
} from './detailPresentation';
import { buildTrainingOutputArtifactItems } from './trainingDetailPresentation.mjs';
export type ArtifactListItem = {
  key: string;
  name: string;
  desc: string;
  objectName?: string;
  kind?: 'resultModel' | 'file';
  producedModelVersionId?: string;
};

export function formatArtifactSize(sizeBytes?: number) {
  if (sizeBytes == null || Number.isNaN(sizeBytes)) return '';
  if (sizeBytes < 1024) return `${sizeBytes} B`;
  const units = ['KB', 'MB', 'GB'];
  let value = sizeBytes / 1024;
  let i = 0;
  while (value >= 1024 && i < units.length - 1) {
    value /= 1024;
    i += 1;
  }
  return `${value.toFixed(2)} ${units[i]}`;
}

export function fileNameFromObjectName(objectName?: string) {
  if (!objectName) return undefined;
  const parts = objectName.replace(/\\/g, '/').split('/');
  return parts[parts.length - 1] || undefined;
}

export const TrainingArtifactsList: React.FC<{
  taskId: string;
  taskStatus?: string;
  outputPath?: string;
  logPath?: string;
  files?: { name: string; desc: string; objectName?: string }[];
  trainingOutput?: TaskDetailInfo['trainingOutput'];
  consistencyProfile?: boolean;
  producedModelVersionId?: string;
  modelArtifactPath?: string;
  modelArtifactSizeBytes?: number;
  modelPublishStatus?: string;
  onPublished?: () => void;
}> = ({
  taskId,
  taskStatus,
  outputPath,
  logPath,
  files,
  trainingOutput,
  consistencyProfile,
  producedModelVersionId,
  modelArtifactPath,
  modelArtifactSizeBytes,
  modelPublishStatus,
  onPublished,
}) => {
  const [downloadingKey, setDownloadingKey] = useState<string>();
  const [publishing, setPublishing] = useState(false);

  const resultModelItem = useMemo((): ArtifactListItem | null => {
    const sizeText = formatArtifactSize(modelArtifactSizeBytes);
    if (producedModelVersionId || modelArtifactPath) {
      const objectName = modelArtifactPath
        ? minioPathToObjectName(modelArtifactPath)
        : undefined;
      return {
        key: 'result-model',
        name: '结果模型',
        desc: [
          producedModelVersionId
            ? `producedModelVersionId: ${producedModelVersionId}`
            : null,
          objectName ? `path: ${objectName}` : modelArtifactPath || null,
          sizeText ? `size: ${sizeText}` : null,
          modelPublishStatus ? `status: ${modelPublishStatus}` : null,
        ]
          .filter(Boolean)
          .join(' · '),
        objectName,
        kind: 'resultModel',
        producedModelVersionId,
      };
    }
    if (taskStatus === 'success') {
      return {
        key: 'result-model',
        name: '结果模型',
        desc: modelPublishStatus
          ? `尚未可下载（${modelPublishStatus}）`
          : '训练已成功，尚未发布为模型版本',
        kind: 'resultModel',
      };
    }
    return null;
  }, [
    modelArtifactPath,
    modelArtifactSizeBytes,
    modelPublishStatus,
    producedModelVersionId,
    taskStatus,
  ]);

  const outputItems = useMemo(
    () => buildTrainingOutputArtifactItems(trainingOutput),
    [trainingOutput],
  );
  const consistencyItems = useMemo(
    () =>
      consistencyProfile
        ? buildConsistencyArtifactItems(outputPath, logPath)
        : [],
    [consistencyProfile, outputPath, logPath],
  );
  const legacyItems = useMemo(() => {
    const list: ArtifactListItem[] = (files || []).map((f, i) => ({
      key: `file-${i}-${f.name}`,
      name: f.name,
      desc: f.desc,
      objectName: f.objectName,
      kind: 'file' as const,
    }));
    if (logPath) {
      list.push({
        key: 'train-log',
        name: 'train.log',
        desc: logPath,
        objectName: minioPathToObjectName(logPath),
        kind: 'file',
      });
    }
    if (outputPath) {
      const outputObjectName = minioPathToObjectName(outputPath);
      list.push({
        key: 'output-dir',
        name: '训练输出目录',
        desc: outputPath,
        objectName: isLikelyDirectoryPath(outputPath)
          ? undefined
          : outputObjectName,
        kind: 'file',
      });
    }
    return list;
  }, [files, logPath, outputPath]);

  const fileItems = outputItems.length
    ? outputItems.map((item, i) => ({
        key: `output-${i}-${item.name}`,
        name: item.name,
        desc: item.desc,
        objectName: item.objectName,
        kind: 'file' as const,
      }))
    : consistencyItems.length
      ? consistencyItems.map((item, i) => ({
          key: `c-${i}-${item.name}`,
          name: item.name,
          desc: item.desc,
          objectName: item.objectName,
          kind: 'file' as const,
        }))
      : legacyItems;

  const items: ArtifactListItem[] = [
    ...(resultModelItem ? [resultModelItem] : []),
    ...fileItems,
  ];

  const resolveResultModelDownload = async (): Promise<{
    objectName: string;
    fileName: string;
  } | null> => {
    if (modelArtifactPath) {
      const objectName = minioPathToObjectName(modelArtifactPath);
      if (objectName) {
        return {
          objectName,
          fileName: fileNameFromObjectName(objectName) || 'result-model.bin',
        };
      }
    }
    if (!producedModelVersionId) return null;
    try {
      const verRes: any = await getModelVersion(producedModelVersionId, {
        skipErrorHandler: true,
      });
      const ver = verRes?.data;
      if (ver?.storagePath) {
        return {
          objectName: minioPathToObjectName(ver.storagePath) || ver.storagePath,
          fileName:
            ver.fileName ||
            fileNameFromObjectName(ver.storagePath) ||
            'result-model.zip',
        };
      }
    } catch {
      // fall through
    }
    try {
      const detailRes: any = await getModelDetail(producedModelVersionId, {
        skipErrorHandler: true,
      });
      const d = detailRes?.data;
      if (d?.storagePath) {
        return {
          objectName: minioPathToObjectName(d.storagePath) || d.storagePath,
          fileName:
            d.fileName ||
            fileNameFromObjectName(d.storagePath) ||
            'result-model.zip',
        };
      }
    } catch {
      // ignore
    }
    return null;
  };

  const handleDownload = async (item: ArtifactListItem) => {
    const downloadKey = item.key;
    setDownloadingKey(downloadKey);
    try {
      if (item.kind === 'resultModel') {
        const resolved = await resolveResultModelDownload();
        if (!resolved) {
          message.warning('结果模型文件路径不可用，请先发布结果模型后再试');
          return;
        }
        await downloadObjectWithBrowser(resolved.objectName, resolved.fileName);
        return;
      }
      if (!item.objectName) {
        message.warning('该产物暂无下载路径');
        return;
      }
      await downloadObjectWithBrowser(item.objectName, item.name);
    } catch (error: any) {
      message.error(await errorMessageFromDownloadError(error));
    } finally {
      setDownloadingKey(undefined);
    }
  };

  const handlePublish = async () => {
    setPublishing(true);
    try {
      const res = await publishTaskModel(taskId, { skipErrorHandler: true });
      if (!res?.success && !res?.data?.producedModelVersionId) {
        message.error(res?.errorMessage || '发布结果模型失败');
        return;
      }
      message.success('结果模型已发布');
      onPublished?.();
    } catch (error: any) {
      message.error(error?.message || '发布结果模型失败');
    } finally {
      setPublishing(false);
    }
  };

  if (!items.length) {
    return (
      <Alert
        type="info"
        showIcon
        message="暂无训练产物"
        description="任务完成后，结果模型与产物文件（fusion_model.pkl、metrics.json、predictions、train.log 等）将在此展示。"
      />
    );
  }

  return (
    <List
      size="small"
      dataSource={items}
      renderItem={(item) => {
        const canDownload =
          item.kind === 'resultModel'
            ? Boolean(producedModelVersionId || modelArtifactPath)
            : Boolean(item.objectName);
        const actions: React.ReactNode[] = [];
        if (canDownload) {
          actions.push(
            <Button
              type="link"
              key="download"
              loading={downloadingKey === item.key}
              onClick={() => void handleDownload(item)}
            >
              下载
            </Button>,
          );
        }
        if (
          item.kind === 'resultModel' &&
          !producedModelVersionId &&
          taskStatus === 'success'
        ) {
          actions.push(
            <Button
              type="link"
              key="publish"
              loading={publishing}
              onClick={() => void handlePublish()}
            >
              发布结果模型
            </Button>,
          );
        }
        if (item.kind === 'resultModel' && producedModelVersionId) {
          actions.push(
            <Button
              type="link"
              key="open"
              onClick={() =>
                history.push(
                  `/model/detail/${encodeURIComponent(producedModelVersionId)}`,
                )
              }
            >
              查看模型
            </Button>,
          );
        }
        return (
          <List.Item actions={actions.length ? actions : undefined}>
            <List.Item.Meta
              title={
                <Space>
                  {item.name}
                  {item.kind === 'resultModel' && (
                    <Tag color={producedModelVersionId ? 'green' : 'default'}>
                      {producedModelVersionId ? '已发布' : '结果模型'}
                    </Tag>
                  )}
                </Space>
              }
              description={
                <Typography.Text
                  copyable={Boolean(
                    item.producedModelVersionId || item.objectName || item.desc,
                  )}
                  style={{ fontFamily: 'monospace', fontSize: 12 }}
                >
                  {item.desc}
                </Typography.Text>
              }
            />
          </List.Item>
        );
      }}
    />
  );
};
