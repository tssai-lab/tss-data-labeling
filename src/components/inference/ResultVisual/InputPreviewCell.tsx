import { DownloadOutlined, EyeOutlined, FileOutlined } from '@ant-design/icons';
import { Alert, Button, Modal, Space, Spin, Typography } from 'antd';
import React, { useEffect, useMemo, useRef, useState } from 'react';
import { downloadObject } from '@/services/files';
import {
  type RowInputPreview,
  resolveRowInputPreview,
  type TextRowInputPreview,
} from './inputPreviewPresentation.mjs';
import MinioImage from './MinioImage';
import { joinOutputObject } from './resolveView';
import type { ResultVisualProps } from './types';

const MAX_TEXT_PREVIEW_BYTES = 2 * 1024 * 1024;

const InputPreviewCell: React.FC<{
  row: Record<string, unknown>;
  outputPath?: string | null;
  onDownloadObject?: ResultVisualProps['onDownloadObject'];
}> = ({ row, outputPath, onDownloadObject }) => {
  const preview = useMemo(
    () => resolveRowInputPreview(row) as RowInputPreview | null,
    [row],
  );
  if (!preview) {
    return <Typography.Text type="secondary">无可视化输入</Typography.Text>;
  }
  if (preview.kind === 'image') {
    const objectName = joinOutputObject(outputPath, preview.path);
    return objectName ? (
      <div style={{ width: 72 }}>
        <MinioImage
          objectName={objectName}
          alt={preview.name}
          width={72}
          height={56}
          fit="cover"
        />
      </div>
    ) : (
      <Typography.Text type="secondary">图片不可用</Typography.Text>
    );
  }
  if (preview.kind === 'file') {
    const objectName = joinOutputObject(outputPath, preview.path);
    return (
      <Space direction="vertical" size={2}>
        <Typography.Text ellipsis style={{ maxWidth: 180 }}>
          <FileOutlined /> {preview.name}
        </Typography.Text>
        <Button
          type="link"
          size="small"
          icon={<DownloadOutlined />}
          disabled={!objectName || !onDownloadObject}
          onClick={() =>
            objectName && onDownloadObject?.(objectName, preview.name)
          }
        >
          下载
        </Button>
      </Space>
    );
  }
  return (
    <TextInputPreview
      preview={preview}
      outputPath={outputPath}
      onDownloadObject={onDownloadObject}
    />
  );
};

const TextInputPreview: React.FC<{
  preview: TextRowInputPreview;
  outputPath?: string | null;
  onDownloadObject?: ResultVisualProps['onDownloadObject'];
}> = ({ preview, outputPath, onDownloadObject }) => {
  const [open, setOpen] = useState(false);
  const [loading, setLoading] = useState(false);
  const [loadedText, setLoadedText] = useState('');
  const [error, setError] = useState('');
  const requestVersionRef = useRef(0);
  const objectName = joinOutputObject(outputPath, preview.path);

  useEffect(
    () => () => {
      requestVersionRef.current += 1;
    },
    [],
  );

  const showText = async () => {
    setOpen(true);
    setError('');
    if (preview.text || !objectName || loadedText) return;
    const requestVersion = ++requestVersionRef.current;
    setLoading(true);
    try {
      const blob = await downloadObject(objectName, { skipErrorHandler: true });
      if (requestVersionRef.current !== requestVersion) return;
      if (!(blob instanceof Blob)) throw new Error('原始文本响应不是文件流');
      if (blob.size > MAX_TEXT_PREVIEW_BYTES) {
        throw new Error('文本超过 2 MiB，请下载后查看');
      }
      setLoadedText(await blob.text());
    } catch (loadError) {
      if (requestVersionRef.current !== requestVersion) return;
      setError(
        loadError instanceof Error ? loadError.message : '原始文本加载失败',
      );
    } finally {
      if (requestVersionRef.current === requestVersion) setLoading(false);
    }
  };

  const text = preview.text || loadedText;
  return (
    <>
      <Space direction="vertical" size={2} style={{ maxWidth: 360 }}>
        <Typography.Paragraph
          style={{ marginBottom: 0 }}
          ellipsis={{ rows: 2 }}
        >
          {preview.summary}
        </Typography.Paragraph>
        {(preview.truncated || preview.text.length > 0 || objectName) && (
          <Button
            type="link"
            size="small"
            icon={<EyeOutlined />}
            onClick={() => void showText()}
          >
            查看全文
          </Button>
        )}
      </Space>
      <Modal
        title={preview.name}
        open={open}
        width={760}
        footer={
          objectName && onDownloadObject ? (
            <Button
              icon={<DownloadOutlined />}
              onClick={() => onDownloadObject(objectName, preview.name)}
            >
              下载原始文本
            </Button>
          ) : null
        }
        onCancel={() => setOpen(false)}
      >
        {loading && <Spin size="small" />}
        {error && <Alert type="warning" showIcon message={error} />}
        {preview.contentTruncated && (
          <Alert
            type="info"
            showIcon
            message="原文过大，本弹窗只展示前 2 MiB；完整内容请从推理结果文件下载。"
            style={{ marginBottom: 12 }}
          />
        )}
        {!loading && !error && (
          <pre
            style={{
              margin: 0,
              padding: 12,
              maxHeight: '60vh',
              overflow: 'auto',
              whiteSpace: 'pre-wrap',
              overflowWrap: 'anywhere',
              background: '#f5f5f5',
              borderRadius: 6,
            }}
          >
            {text || preview.summary}
          </pre>
        )}
      </Modal>
    </>
  );
};

export default InputPreviewCell;
