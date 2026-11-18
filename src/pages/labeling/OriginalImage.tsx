import { ZoomInOutlined } from '@ant-design/icons';
import { Alert, Button, Empty, Image, Spin } from 'antd';
import { useEffect, useState } from 'react';
import { describeLabelingError, sampleBlob } from '@/services/labeling';
import ui from './workspaceUi.less';

export default function OriginalImage({
  projectId,
  sampleId,
}: {
  projectId: string;
  sampleId: string;
}) {
  const [url, setUrl] = useState(''),
    [error, setError] = useState('');
  const [previewOpen, setPreviewOpen] = useState(false);
  const [retry, setRetry] = useState(0),
    [loading, setLoading] = useState(false);
  useEffect(() => {
    const abort = new AbortController();
    let objectUrl = '';
    setUrl('');
    setPreviewOpen(false);
    setError('');
    setLoading(true);
    void sampleBlob(projectId, sampleId, abort.signal)
      .then((blob) => {
        if (!abort.signal.aborted) {
          objectUrl = URL.createObjectURL(blob);
          setUrl(objectUrl);
        }
      })
      .catch((e) => {
        if (!abort.signal.aborted) setError(describeLabelingError(e));
      })
      .finally(() => {
        if (!abort.signal.aborted) setLoading(false);
      });
    return () => {
      abort.abort();
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [projectId, sampleId, retry]);
  if (loading) return <Spin />;
  return (
    <div>
      {error ? (
        <Alert
          type="error"
          message={error}
          action={
            <Button onClick={() => setRetry((n) => n + 1)}>重读原图</Button>
          }
        />
      ) : url ? (
        <button
          type="button"
          className={ui.imageButton}
          aria-label="查看原图大图"
          onClick={() => setPreviewOpen(true)}
        >
          <Image
            src={url}
            alt={`样本 ${sampleId} 的授权原图`}
            preview={{
              visible: previewOpen,
              onVisibleChange: setPreviewOpen,
              mask: null,
            }}
            onError={() => setError('原图解码失败')}
          />
          <span className={ui.imageZoom}>
            <ZoomInOutlined aria-hidden="true" />
            放大
          </span>
        </button>
      ) : (
        <Empty description="暂无原图" />
      )}
    </div>
  );
}
