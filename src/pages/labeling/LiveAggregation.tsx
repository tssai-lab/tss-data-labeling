import { Alert, App, Button, Card, Input, InputNumber, Select, Space, Table } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { describeLabelingError, type SpecVersion } from '@/services/labeling';
import {
  type Aggregation,
  type AggregationItem,
  type AggregationAlgorithm,
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
  const [strategyParameters, setStrategyParameters] = useState<Record<string, number>>({});
  const [algorithms, setAlgorithms] = useState<AggregationAlgorithm[]>([]);
  const selectedAlgorithm = algorithms.find((item) => item.algorithmId === algorithm);
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
      .algorithms(spec.taskType, abort.signal)
      .then((rows) => {
        if (!abort.signal.aborted) {
          setAlgorithms(rows);
          setAlgorithm(rows[0]?.algorithmId || '');
          setStrategyParameters({});
        }
      })
      .catch((e) => {
        if (!abort.signal.aborted) setError(describeLabelingError(e));
      });
    return () => abort.abort();
  }, [spec.taskType]);
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
          ...rows.filter((r) =>
            (selectedAlgorithm?.acceptedSourceKinds || []).includes(r.sourceKind || '') &&
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
    const minimumSources = Math.max(
      selectedAlgorithm?.minimumIndependentSources || 1,
      strategyParameters.minimumVotes || 0,
    );
    if (!inputs.length || inputs.some((i) => i.resultIds.length < minimumSources)) {
      setError(`每个所选样本至少明确勾选 ${minimumSources} 份独立参与者的原始答案`);
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
      parameters: strategyParameters,
    };
    modal.confirm({
      title: `为 ${inputs.length} 个样本运行「${selectedAlgorithm?.algorithmId}」？`,
      content: `${selectedAlgorithm?.description || '使用明确勾选的原始答案生成建议。'} 建议仍需单独审核确认。`,
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
  return (
    <Card title="多来源集成建议" style={{ marginTop: 16 }}>
      <p>
        选择样本 → 读取来源 → 勾选每个参与者的一份原始答案 →
        计算建议。派生修订不参与投票。
      </p>
      {(error || action.error) && (
        <Alert type="error" message={error || action.error} />
      )}
      {!algorithms.length ? (
        <Alert type="info" message="当前任务类型尚无已注册的集成算法，仍可逐份审核和修订。" />
      ) : (
        <Space direction="vertical" style={{ width: '100%', marginBottom: 12 }}>
          <Select
            aria-label="选择结果集成算法"
            value={algorithm}
            onChange={(value) => {
              setAlgorithm(value);
              setChosen([]);
              setCandidates([]);
              setStrategyParameters({});
            }}
            options={algorithms.map((item) => ({
              value: item.algorithmId,
              label: `${item.algorithmId} · 至少 ${item.minimumIndependentSources} 个独立来源`,
            }))}
          />
          {selectedAlgorithm?.description && <span>{selectedAlgorithm.description}</span>}
          {Object.entries(selectedAlgorithm?.parametersSchema?.properties || {}).map(
            ([name, schema]) => (
              <label key={name} style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                <span>{schema.description || name}</span>
                <InputNumber
                  aria-label={schema.description || name}
                  min={schema.minimum}
                  max={schema.maximum}
                  precision={schema.type === 'integer' ? 0 : undefined}
                  value={strategyParameters[name] ?? schema.default}
                  onChange={(value) => setStrategyParameters((current) => {
                    const next = { ...current };
                    if (value == null) delete next[name];
                    else next[name] = value;
                    return next;
                  })}
                />
              </label>
            ),
          )}
        </Space>
      )}
      <Space wrap>
        <Button
          disabled={!selected.length || loading || action.busy || !selectedAlgorithm}
          onClick={() => void prepare()}
        >
          读取所选 {selected.length} 个样本的候选
        </Button>
        <Button
          type="primary"
          disabled={!chosen.length || loading || action.busy || !selectedAlgorithm}
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
            render: (_, r) => JSON.stringify(r.result || null),
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
                render: (_, r) => JSON.stringify(r.result || null),
              },
              { title: '票数', render: (_, r) => JSON.stringify(r.votes) },
            ]}
          />
        </>
      )}
    </Card>
  );
}
