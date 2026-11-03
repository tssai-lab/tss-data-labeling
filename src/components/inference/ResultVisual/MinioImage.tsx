import { Image, Spin, Typography } from 'antd';
import React, { useEffect, useState } from 'react';
import { downloadObject } from '@/services/files';

const MinioImage: React.FC<{
  objectName: string;
  alt?: string;
  width?: number | string;
  height?: number | string;
  fit?: React.CSSProperties['objectFit'];
}> = ({ objectName, alt, width = '100%', height, fit = 'contain' }) => {
  const [url, setUrl] = useState<string>();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string>();

  useEffect(() => {
    let cancelled = false;
    let objectUrl = '';
    setLoading(true);
    setError(undefined);
    setUrl(undefined);

    downloadObject(objectName, { skipErrorHandler: true })
      .then((blob) => {
        if (cancelled) return;
        objectUrl = URL.createObjectURL(blob);
        setUrl(objectUrl);
      })
      .catch((err: any) => {
        if (cancelled) return;
        setError(err?.message || '图片加载失败');
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });

    return () => {
      cancelled = true;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [objectName]);

  if (loading) {
    return (
      <div style={{ padding: 24, textAlign: 'center' }}>
        <Spin size="small" />
      </div>
    );
  }
  if (error || !url) {
    return (
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        {error || '暂无法预览图片'}
      </Typography.Text>
    );
  }
  return (
    <Image
      src={url}
      alt={alt || objectName}
      width={width}
      height={height}
      style={height ? { objectFit: fit } : undefined}
    />
  );
};

export default MinioImage;
