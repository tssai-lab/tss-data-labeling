import { useCallback, useEffect, useRef, useState } from 'react';
import { listExperimentVersions } from '@/services/platform';
import { getApiErrorMessage } from '@/utils/apiError';

/** 历史读取失败保留同一实验的上次结果；切换实验后旧请求无权回写。 */
export function useExperimentVersions(experimentId?: string) {
  const [versions, setVersions] = useState<API.TrainingExperimentVersion[]>([]);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const sequence = useRef(0);
  const refreshVersions = useCallback(
    async (id = experimentId) => {
      if (!id || id !== experimentId) return;
      const request = ++sequence.current;
      setLoading(true);
      try {
        const result = await listExperimentVersions(id, {
          skipErrorHandler: true,
        });
        if (!Array.isArray(result?.data)) throw new Error('训练历史回执不完整');
        if (request !== sequence.current) return;
        setVersions(result.data);
        setError('');
      } catch (cause) {
        if (request === sequence.current)
          setError(getApiErrorMessage(cause, '训练历史读取失败'));
      } finally {
        if (request === sequence.current) setLoading(false);
      }
    },
    [experimentId],
  );
  useEffect(() => {
    setVersions([]);
    setError('');
    setLoading(false);
    void refreshVersions();
    return () => {
      ++sequence.current;
    };
  }, [refreshVersions]);
  return { versions, setVersions, error, loading, refreshVersions };
}
