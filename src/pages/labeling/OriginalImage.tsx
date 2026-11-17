import { Alert, Button, Empty, Spin } from 'antd';
import { useEffect, useState } from 'react';
import { describeLabelingError, sampleBlob } from '@/services/labeling';

export default function OriginalImage({
  projectId,
  sampleId,
}: {
  projectId: string;
  sampleId: string;
}) {
  const [url, setUrl] = useState(''),
    [error, setError] = useState('');
  const [retry, setRetry] = useState(0),
    [loading, setLoading] = useState(false);
  useEffect(() => {
    const abort = new AbortController();
    let objectUrl = '';
    setUrl('');
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
        <img
          src={url}
          alt={`样本 ${sampleId} 的授权原图`}
          style={{ maxWidth: '100%', maxHeight: 450, objectFit: 'contain' }}
          onError={() => setError('原图解码失败')}
        />
      ) : (
        <Empty description="暂无原图" />
      )}
    </div>
  );
}
