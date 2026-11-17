import { Alert, App, Button, Card, Input, Space, Table } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { describeLabelingError, type SpecVersion } from '@/services/labeling';
import {
  type Aggregation,
  type AggregationItem,
  type LiveResult,
  ownerApi,
  readAllPages,
  selectionItem,
} from '@/services/labelingOwner';
import useOwnerAction from './useOwnerAction';

export default function LiveAggregation({
  projectId,
  spec,
  selected,
  onChanged,
  onBusyChange,
  writeBlocked,
}: {
  projectId: string;
  spec: SpecVersion;
  selected: LiveResult[];
  onChanged: () => void;
  onBusyChange?: (busy: boolean) => void;
  writeBlocked?: string;
}) {
  const { modal } = App.useApp(),
    action = useOwnerAction(onBusyChange, writeBlocked);
  const [candidates, setCandidates] = useState<LiveResult[]>([]),
    [chosen, setChosen] = useState<React.Key[]>([]);
  const [job, setJob] = useState<Aggregation>(),
    [items, setItems] = useState<AggregationItem[]>([]);
  const [known, setKnown] = useState(''),
    [error, setError] = useState(''),
    [loading, setLoading] = useState(false);
  const [algorithm, setAlgorithm] = useState('');
  const version = useRef(0),
    lock = useRef(false);
  useEffect(() => {
    ++version.current;
    setCandidates([]);
    setChosen([]);
    return () => {
      ++version.current;
    };
  }, [
    projectId,
    spec.specVersionId,
    JSON.stringify(selected.map(selectionItem)),
  ]);
  useEffect(() => {
    const abort = new AbortController();
    void ownerApi
      .algorithms(abort.signal)
      .then((rows) => {
        if (!abort.signal.aborted)
          setAlgorithm(
            rows.find(
              (r) =>
                r.algorithmId === 'majority-vote-v1' &&
                r.taskType === 'IMAGE_CLASSIFICATION',
            )?.algorithmId || '',
          );
      })
      .catch((e) => {
        if (!abort.signal.aborted) setError(describeLabelingError(e));
      });
    return () => abort.abort();
  }, []);
  async function prepare() {
    if (lock.current) return;
    lock.current = true;
    const token = ++version.current;
    setLoading(true);
    setError('');
    setCandidates([]);
    setChosen([]);
    try {
      const values: LiveResult[] = [];
      for (const sample of selected) {
        const rows = await readAllPages(
          (c) =>
            ownerApi.history(projectId, sample.sampleId, spec.specVersionId, c),
          (r) => r.resultId || '',
        );
        values.push(
          ...rows.filter(
            (r) =>
              ['HUMAN', 'AI'].includes(r.sourceKind || '') &&
              r.reviewable &&
              !r.activeProcessing,
          ),
        );
      }
      if (token === version.current) setCandidates(values);
    } catch (e) {
      if (token === version.current) setError(describeLabelingError(e));
    } finally {
      lock.current = false;
      if (token === version.current) setLoading(false);
    }
  }
  async function read(id: string) {
    if (lock.current) return;
    lock.current = true;
    const token = ++version.current;
    setLoading(true);
    setError('');
    setItems([]);
    setJob(undefined);
    try {
      const value = await ownerApi.aggregation(id);
      if (value.projectId !== projectId) throw new Error('集成不属于当前项目');
      const rows = await readAllPages(
        (c) => ownerApi.aggregationItems(id, c),
        (r) => r.sampleId,
      );
      if (token === version.current) {
        setJob(value);
        setItems(rows);
        setKnown(id);
      }
    } catch (e) {
      if (token === version.current) setError(describeLabelingError(e));
    } finally {
      lock.current = false;
      if (token === version.current) setLoading(false);
    }
  }
  function create() {
    const inputs = selected.map((s) => ({
      sampleId: s.sampleId,
      resultIds: candidates
        .filter(
          (r) => r.sampleId === s.sampleId && chosen.includes(r.resultId || ''),
        )
        .map((r) => r.resultId || ''),
    }));
    if (!inputs.length || inputs.some((i) => i.resultIds.length < 2)) {
      setError('每个所选样本至少明确勾选两份独立参与者的原始答案');
      return;
    }
    for (const input of inputs) {
      const actors = candidates
        .filter((r) => input.resultIds.includes(r.resultId || ''))
        .map((r) => r.sourceActor);
      if (actors.some((a) => !a) || new Set(actors).size !== actors.length) {
        setError('同一参与者只能一票，请调整候选');
        return;
      }
    }
    const body = {
      algorithmId: algorithm,
      specVersionId: spec.specVersionId,
      items: inputs,
    };
    modal.confirm({
      title: `为 ${inputs.length} 个样本计算多数投票建议？`,
      content:
        '只使用下方明确勾选的原始答案。严格过半才生成建议，建议仍需单独审核确认。',
      onOk: async () => {
        const value = await action.run(
          `aggregate:${projectId}`,
          body,
          (key) => ownerApi.aggregate(projectId, body, key),
          '集成计算完成，建议待审核',
        );
        if (value) {
          setKnown(value.aggregationId);
          await read(value.aggregationId);
          onChanged();
        }
      },
    });
  }
  if (spec.taskType === 'TIMESERIES_ANNOTATION')
    return (
      <Alert
        type="info"
        message="能耗任务暂无已注册的集成算法，仍可逐份审核和修订。"
      />
    );
  return (
    <Card title="多来源集成建议" style={{ marginTop: 16 }}>
      <p>
        选择样本 → 读取来源 → 勾选每个参与者的一份原始答案 →
        计算建议。派生修订不参与投票。
      </p>
      {(error || action.error) && (
        <Alert type="error" message={error || action.error} />
      )}
      <Space wrap>
        <Button
          disabled={!selected.length || loading || action.busy || !algorithm}
          onClick={() => void prepare()}
        >
          读取所选 {selected.length} 个样本的候选
        </Button>
        <Button
          type="primary"
          disabled={!chosen.length || loading || action.busy || !algorithm}
          onClick={create}
        >
          计算明确勾选的候选
        </Button>
      </Space>
      <Table<LiveResult>
        rowKey={(r) => r.resultId || ''}
        dataSource={candidates}
        pagination={{ pageSize: 10 }}
        scroll={{ x: 600 }}
        rowSelection={{
          selectedRowKeys: chosen,
          onChange: setChosen,
          getCheckboxProps: () => ({ disabled: loading || action.busy }),
        }}
        columns={[
          { title: '样本', dataIndex: 'sourcePath', ellipsis: true },
          { title: '来源', dataIndex: 'sourceKind' },
          { title: '参与者', dataIndex: 'sourceActor', ellipsis: true },
          {
            title: '答案',
            render: (_, r) =>
              spec.labels.find((l) => l.id === r.result?.labelId)?.name || '—',
          },
        ]}
      />
      <details>
        <summary>恢复已有集成记录</summary>
        <Space>
          <Input
            aria-label="集成记录编号"
            value={known}
            onChange={(e) => setKnown(e.target.value)}
            disabled={loading || action.busy}
          />
          <Button
            disabled={!known.trim() || loading || action.busy}
            onClick={() => void read(known.trim())}
          >
            读取集成
          </Button>
        </Space>
      </details>
      {job && (
        <>
          <p>
            集成编号：{job.aggregationId}；建议请回到“结果与审核”选择后确认。
          </p>
          <Table<AggregationItem>
            rowKey="sampleId"
            dataSource={items}
            pagination={{ pageSize: 10 }}
            scroll={{ x: 600 }}
            columns={[
              { title: '样本', dataIndex: 'sampleId', ellipsis: true },
              {
                title: '结论',
                render: (_, r) =>
                  ({
                    SUGGESTED: '已生成建议',
                    TIE: '未严格过半',
                    INSUFFICIENT_INPUT: '独立来源不足',
                  })[r.outcome] || r.outcome,
              },
              {
                title: '建议',
                render: (_, r) =>
                  spec.labels.find((l) => l.id === r.result?.labelId)?.name ||
                  '—',
              },
              { title: '票数', render: (_, r) => JSON.stringify(r.votes) },
            ]}
          />
        </>
      )}
    </Card>
  );
}
