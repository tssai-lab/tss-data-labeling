import { App } from 'antd';
import { useRef, useState } from 'react';
import { describeLabelingError, operationKey } from '@/services/labeling';

/** Keep the same key for an identical request after a lost response; lock synchronously. */
export default function useOwnerAction(
  onBusyChange?: (busy: boolean) => void,
  writeBlocked?: string,
) {
  const { message } = App.useApp();
  const lock = useRef(false);
  const keys = useRef(new Map<string, string>());
  const prepared = useRef(new Map<string, unknown>());
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  async function run<T>(
    operation: string,
    body: unknown,
    send: (
      key: string,
      prepare: <P>(read: () => Promise<P>) => Promise<P>,
    ) => Promise<T>,
    success: string,
  ): Promise<T | undefined> {
    if (lock.current) return;
    if (writeBlocked) {
      setError(writeBlocked);
      return;
    }
    lock.current = true;
    onBusyChange?.(true);
    setBusy(true);
    setError('');
    const fingerprint = JSON.stringify([operation, body]);
    const key = keys.current.get(fingerprint) || operationKey();
    keys.current.set(fingerprint, key);
    try {
      async function prepare<P>(read: () => Promise<P>) {
        if (!prepared.current.has(fingerprint))
          prepared.current.set(fingerprint, await read());
        return prepared.current.get(fingerprint) as P;
      }
      const result = await send(key, prepare);
      keys.current.delete(fingerprint);
      prepared.current.delete(fingerprint);
      message.success(success);
      return result;
    } catch (e) {
      const failure = e as { status?: number; code?: string };
      // A definite server rejection did not apply the write. Refresh its selection/CAS;
      // transport failures keep the original payload and key for recovery.
      if (
        failure?.status === 410 ||
        failure?.status === 422 ||
        (failure?.status === 409 &&
          [
            'REVISION_CONFLICT',
            'RESULT_VERSION_CHANGED',
            'SELECTION_PURPOSE_MISMATCH',
            'SAMPLE_BUSY',
          ].includes(failure.code || ''))
      ) {
        keys.current.delete(fingerprint);
        prepared.current.delete(fingerprint);
      }
      setError(describeLabelingError(e));
      return undefined;
    } finally {
      lock.current = false;
      onBusyChange?.(false);
      setBusy(false);
    }
  }
  return { busy, error, setError, run };
}
