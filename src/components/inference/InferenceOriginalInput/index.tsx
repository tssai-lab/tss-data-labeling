import {
  DownloadOutlined,
  EyeOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import { Alert, Button, Descriptions, Image, Space, Spin, Typography } from 'antd';
import React, { useEffect, useMemo, useRef, useState } from 'react';
import { downloadObject } from '@/services/files';
import {
  buildInferenceInputPresentation,
  type InferenceInputPresentation,
} from './presentationTypes';

const MAX_TEXT_PREVIEW_BYTES = 2 * 1024 * 1024;
const MAX_IMAGE_PREVIEW_BYTES = 20 * 1024 * 1024;

export type InferenceOriginalInputProps = {
  inputMode?: string;
  inputObjectName?: string | null;
  datasetVersionId?: string | null;
  datasetDisplayName?: string;
  onDownloadObject: (objectName: string, filename: string) => void | Promise<void>;
};

function readablePreviewError(error: unknown) {
  const fallback = '原始输入不存在、已被清理或当前账号无权访问';
  if (!error || typeof error !== 'object') return fallback;
  const message = (error as { message?: string }).message?.trim();
  return message && !message.includes('objectName') ? message : fallback;
}

const InferenceOriginalInput: React.FC<InferenceOriginalInputProps> = ({
  inputMode,
  inputObjectName,
  datasetVersionId,
  datasetDisplayName,
  onDownloadObject,
}) => {
  const presentation = useMemo(
    () =>
      buildInferenceInputPresentation({
        inputMode,
        inputObjectName,
        datasetVersionId,
        datasetDisplayName,
      }) as InferenceInputPresentation,
    [datasetDisplayName, datasetVersionId, inputMode, inputObjectName],
  );
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [textPreview, setTextPreview] = useState('');
  const [imageUrl, setImageUrl] = useState('');
  const previewRequestRef = useRef(0);

  const resetPreview = () => {
    setError('');
    setTextPreview('');
    setImageUrl('');
  };

  useEffect(() => {
    previewRequestRef.current += 1;
    resetPreview();
    setLoading(false);
    return () => {
      previewRequestRef.current += 1;
    };
  }, [presentation.identifier]);

  useEffect(
    () => () => {
      if (imageUrl) URL.revokeObjectURL(imageUrl);
    },
    [imageUrl],
  );

  if (presentation.kind === 'dataset') {
    return (
      <div>
        <Typography.Title level={5} style={{ margin: 0 }}>
          原始输入
        </Typography.Title>
        <Descriptions column={1} size="small" bordered style={{ marginTop: 8 }}>
          <Descriptions.Item label="输入方式">数据集版本</Descriptions.Item>
          <Descriptions.Item label="数据集">
            {presentation.displayName}
          </Descriptions.Item>
          <Descriptions.Item label="版本 ID">
            <Typography.Text copyable={!!presentation.identifier}>
              {presentation.identifier || '-'}
            </Typography.Text>
          </Descriptions.Item>
        </Descriptions>
        <Typography.Paragraph type="secondary" style={{ margin: '8px 0 0' }}>
          数据集模式只展示本次任务锁定的数据集版本，不在结果页加载整份数据。
        </Typography.Paragraph>
      </div>
    );
  }

  const canPreview =
    presentation.previewKind === 'image' || presentation.previewKind === 'text';
  const loadPreview = async () => {
    if (!presentation.identifier || !canPreview) return;
    const requestVersion = ++previewRequestRef.current;
    const objectName = presentation.identifier;
    const previewKind = presentation.previewKind;
    setLoading(true);
    resetPreview();
    try {
      const blob = await downloadObject(objectName, {
        skipErrorHandler: true,
      });
      if (previewRequestRef.current !== requestVersion) return;
      if (!(blob instanceof Blob)) {
        throw new Error('原始输入响应不是文件流');
      }
      if (
        previewKind === 'image' &&
        blob.size > MAX_IMAGE_PREVIEW_BYTES
      ) {
        throw new Error('图片超过 20 MiB，请下载后查看');
      }
      if (
        previewKind === 'text' &&
        blob.size > MAX_TEXT_PREVIEW_BYTES
      ) {
        throw new Error('文本超过 2 MiB，请下载后查看');
      }
      if (previewKind === 'image') {
        setImageUrl(URL.createObjectURL(blob));
      } else {
        const text = await blob.text();
        if (previewRequestRef.current !== requestVersion) return;
        setTextPreview(text);
      }
    } catch (loadError) {
      if (previewRequestRef.current !== requestVersion) return;
      setError(readablePreviewError(loadError));
    } finally {
      if (previewRequestRef.current === requestVersion) {
        setLoading(false);
      }
    }
  };

  return (
    <div>
      <Typography.Title level={5} style={{ margin: 0 }}>
        原始输入
      </Typography.Title>
      <Descriptions column={1} size="small" bordered style={{ marginTop: 8 }}>
        <Descriptions.Item label="输入方式">单文件</Descriptions.Item>
        <Descriptions.Item label="文件名">
          {presentation.displayName}
        </Descriptions.Item>
        <Descriptions.Item label="对象标识">
          <Typography.Text copyable={!!presentation.identifier} ellipsis>
            {presentation.identifier || '-'}
          </Typography.Text>
        </Descriptions.Item>
      </Descriptions>
      <Space wrap style={{ marginTop: 8 }}>
        <Button
          icon={imageUrl || textPreview ? <ReloadOutlined /> : <EyeOutlined />}
          disabled={!presentation.identifier || !canPreview}
          loading={loading}
          onClick={() => void loadPreview()}
        >
          {imageUrl || textPreview ? '重新加载预览' : '加载预览'}
        </Button>
        <Button
          icon={<DownloadOutlined />}
          disabled={!presentation.identifier}
          onClick={() =>
            void onDownloadObject(
              presentation.identifier,
              presentation.displayName || 'inference-input',
            )
          }
        >
          下载原始输入
        </Button>
      </Space>
      {!canPreview && presentation.identifier && (
        <Typography.Text type="secondary" style={{ display: 'block', marginTop: 8 }}>
          该格式不在安全预览白名单中，请下载后查看。
        </Typography.Text>
      )}
      {loading && <Spin size="small" style={{ display: 'block', marginTop: 12 }} />}
      {error && <Alert type="warning" showIcon message={error} style={{ marginTop: 12 }} />}
      {imageUrl && (
        <div style={{ marginTop: 12, textAlign: 'center' }}>
          <Image
            src={imageUrl}
            alt={presentation.displayName}
            style={{ maxHeight: 480, objectFit: 'contain' }}
          />
        </div>
      )}
      {textPreview && (
        <pre
          style={{
            margin: '12px 0 0',
            padding: 12,
            maxHeight: 360,
            overflow: 'auto',
            whiteSpace: 'pre-wrap',
            overflowWrap: 'anywhere',
            background: '#f5f5f5',
            borderRadius: 6,
          }}
        >
          {textPreview}
        </pre>
      )}
    </div>
  );
};

export default InferenceOriginalInput;
