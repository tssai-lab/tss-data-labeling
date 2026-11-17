import { Alert, Spin } from 'antd';
import { useEffect, useState } from 'react';
import { describeLabelingError, type SpecVersion } from '@/services/labeling';
import type { EnergyInput, EnergyResult } from '@/services/labelingEnergy.mjs';
import { type LiveResult, ownerApi } from '@/services/labelingOwner';
import EnergyEditorAdapter from './EnergyEditorAdapter';

export default function StructuredPreview({
  projectId,
  row,
  spec,
  onSave,
  busy = false,
  assignmentId,
}: {
  assignmentId?: string;
  projectId: string;
  row: Pick<LiveResult, 'sampleId' | 'resultId' | 'reviewRevision' | 'result'>;
  spec: SpecVersion;
  busy?: boolean;
  onSave?: (result: EnergyResult) => Promise<void>;
}) {
  const [input, setInput] = useState<EnergyInput>(),
    [error, setError] = useState('');
  useEffect(() => {
    const abort = new AbortController();
    setInput(undefined);
    setError('');
    void (
      assignmentId
        ? ownerApi.assignmentData(assignmentId, row.sampleId, abort.signal)
        : ownerApi.sampleData(projectId, row.sampleId, abort.signal)
    )
      .then((v) => {
        if (v.sampleId !== row.sampleId) throw new Error('样本输入不一致');
        if (!abort.signal.aborted) setInput(v);
      })
      .catch((e) => {
        if (!abort.signal.aborted) setError(describeLabelingError(e));
      });
    return () => abort.abort();
  }, [projectId, row.sampleId, assignmentId]);
  if (error) return <Alert type="error" message={error} />;
  if (!input) return <Spin />;
  const result: EnergyResult =
    row.result?.schemaVersion && row.result.annotations
      ? {
          schemaVersion: row.result.schemaVersion,
          annotations: row.result.annotations,
        }
      : { schemaVersion: '1.0.0', annotations: [] };
  return (
    <EnergyEditorAdapter
      key={`${row.resultId}:${row.reviewRevision}`}
      input={input}
      labels={spec.labels}
      draft={{
        revision: row.reviewRevision,
        explicitDone: false,
        resultFormat: 'annotation-record-v1',
        result,
      }}
      readOnly={!onSave || busy}
      allowIncomplete={false}
      onStateChange={() => {}}
      onSave={async (value) => {
        if (!onSave) throw new Error('只读结果');
        await onSave(value.result);
        return {
          revision: row.reviewRevision + 1,
          explicitDone: true,
          resultFormat: 'annotation-record-v1',
          result: value.result,
        };
      }}
    />
  );
}
