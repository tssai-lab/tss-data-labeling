import {
  Alert,
  App,
  Button,
  Card,
  Drawer,
  Input,
  Select,
  Space,
  Table,
  Tag,
} from 'antd';
import { useEffect, useRef, useState } from 'react';
import {
  describeLabelingError,
  operationKey,
  type SpecVersion,
} from '@/services/labeling';
import {
  type LiveResult,
  ownerApi,
  type ResultFilter,
  type ResultStatistics,
  readAllPages,
  selectionItem,
} from '@/services/labelingOwner';
import { resultLabel } from '@/services/labelingStructured';
import type { Selection, SelectionItem } from '@/services/labelingWorkflow';
import OriginalImage from './OriginalImage';
import ReviewWorkbench, {
  confidenceText,
  reviewStatusNames,
  sourceNames,
} from './ReviewWorkbench';
import StructuredPreview from './StructuredPreview';
import useOwnerAction from './useOwnerAction';

export default function LiveResults({
  projectId,
  spec,
  selected,
  onSelected,
  onChanged,
  onBusyChange,
  writeBlocked,
  refresh,
  filterRequest,
}: {
  projectId: string;
  spec: SpecVersion;
  selected: LiveResult[];
  onSelected: (rows: LiveResult[]) => void;
  onChanged: () => void;
  onBusyChange?: (busy: boolean) => void;
  writeBlocked?: string;
  refresh: number;
  filterRequest?: { filter: ResultFilter; nonce: number };
}) {
  const { modal } = App.useApp();
  const action = useOwnerAction(onBusyChange, writeBlocked);
  const [rows, setRows] = useState<LiveResult[]>([]),
    [filter, setFilter] = useState<ResultFilter>({});
  const [stats, setStats] = useState<ResultStatistics>(),
    [cursor, setCursor] = useState<string | null>(null);
  const [loading, setLoading] = useState(false),
    [error, setError] = useState(''),
    [selectedId, setSelectedId] = useState('');
  const [reason, setReason] = useState(''),
    [history, setHistory] = useState<LiveResult[]>();
  const [revisionRow, setRevisionRow] = useState<LiveResult>();
  const [revisionLabel, setRevisionLabel] = useState('');
  const [revisionReason, setRevisionReason] = useState('');
  const [bulkReason, setBulkReason] = useState('');
  const [historyLoading, setHistoryLoading] = useState(false);
  const [historyError, setHistoryError] = useState('');
  const [scopeMode, setScopeMode] = useState<'explicit' | 'filter'>('explicit');
  const [purpose, setPurpose] = useState<'ACCEPT' | 'NEEDS_REVISION'>('ACCEPT');
  const [frozen, setFrozen] = useState<{
    selection: Selection;
    items: SelectionItem[];
    stats: ResultStatistics;
  }>();
  const [freezeError, setFreezeError] = useState(''),
    [freezing, setFreezing] = useState(false);
  const version = useRef(0),
    historyVersion = useRef(0),
    freezeLock = useRef(false),
    pageCursors = useRef(new Set<string>()),
    freezeVersion = useRef(0);
  const effective = { ...filter, specVersionId: spec.specVersionId };
  useEffect(() => {
    if (filterRequest) setFilter(filterRequest.filter);
  }, [filterRequest?.nonce]);
  useEffect(() => {
    ++freezeVersion.current;
    setFrozen(undefined);
    setFreezeError('');
    return () => {
      ++freezeVersion.current;
    };
  }, [
    spec.specVersionId,
    JSON.stringify(filter),
    JSON.stringify(selected.map(selectionItem)),
    purpose,
    scopeMode,
  ]);
  async function load(next?: string | null) {
    const current = ++version.current;
    setLoading(true);
    setError('');
    if (!next) {
      setRows([]);
      setStats(undefined);
      setCursor(null);
    }
    try {
      const [page, statistics] = await Promise.all([
        ownerApi.results(projectId, effective, next),
        ownerApi.statistics(projectId, effective),
      ]);
      if (version.current !== current) return;
      if (!next) pageCursors.current.clear();
      if (
        (page.nextCursor && pageCursors.current.has(page.nextCursor)) ||
        (next &&
          page.items.some((item) =>
            rows.some((old) => old.sampleId === item.sampleId),
          ))
      )
        throw new Error('结果分页重复，请刷新后重新读取');
      if (page.nextCursor) pageCursors.current.add(page.nextCursor);
      setRows((old) => (next ? [...old, ...page.items] : page.items));
      setCursor(page.nextCursor);
      setStats(statistics);
      if (!next) setSelectedId(page.items[0]?.sampleId || '');
    } catch (e) {
      if (version.current === current) setError(describeLabelingError(e));
    } finally {
      if (version.current === current) setLoading(false);
    }
  }
  useEffect(() => {
    onSelected([]);
    setFrozen(undefined);
    setHistory(undefined);
    ++historyVersion.current;
    void load();
    return () => {
      ++version.current;
      ++historyVersion.current;
    };
  }, [projectId, spec.specVersionId, JSON.stringify(filter), refresh]);
  async function showHistory(row: LiveResult) {
    const current = ++historyVersion.current;
    setHistory([]);
    setHistoryLoading(true);
    setHistoryError('');
    try {
      const values = await readAllPages(
        (c) => ownerApi.history(projectId, row.sampleId, spec.specVersionId, c),
        (r) => r.resultId || '',
      );
      if (current === historyVersion.current) setHistory(values);
    } catch (e) {
      if (current === historyVersion.current)
        setHistoryError(describeLabelingError(e));
    } finally {
      if (current === historyVersion.current) setHistoryLoading(false);
    }
  }
  function chooseCandidate(candidate: LiveResult) {
    const row = { ...candidate, requiresResultSelection: false };
    setRows((old) => old.map((r) => (r.sampleId === row.sampleId ? row : r)));
    onSelected(selected.map((r) => (r.sampleId === row.sampleId ? row : r)));
    setSelectedId(row.sampleId);
    setReason('');
    setHistory(undefined);
  }
  function review(
    target: LiveResult[],
    decision: 'ACCEPT' | 'NEEDS_REVISION',
    note = reason,
  ) {
    if (decision === 'NEEDS_REVISION' && !note.trim()) {
      action.setError('请先填写待修正原因');
      return;
    }
    modal.confirm({
      title: `${decision === 'ACCEPT' ? '确认采用' : '标记待修正'} ${target.length} 个样本？`,
      content:
        decision === 'ACCEPT'
          ? '只采用当前明确选择的答案版本。任一样本的审核版本变化，整个操作将被拒绝，请刷新后重新选择。'
          : '保留历史答案，记录待修正原因。不会自动创建新标注任务。',
      onOk: async () => {
        const value = await action.run(
          'sample-review',
          { target: target.map(selectionItem), decision, reason: note },
          async (key, prepare) => {
            const selection = await prepare(() =>
              ownerApi.select(
                projectId,
                {
                  specVersionId: spec.specVersionId,
                  purpose: 'REVIEW',
                  reviewAction: decision,
                  items: target.map(selectionItem),
                },
                operationKey(),
              ),
            );
            return ownerApi.atomicReview(
              projectId,
              {
                selectionId: selection.selectionId,
                action: decision,
                reason: note.trim(),
              },
              key,
            );
          },
          '样本审核已保存',
        );
        if (value) {
          onSelected([]);
          setReason('');
          setBulkReason('');
          onChanged();
        }
      },
    });
  }
  async function freeze() {
    if (freezeLock.current) return;
    freezeLock.current = true;
    setFreezing(true);
    setFreezeError('');
    setFrozen(undefined);
    const current = ++freezeVersion.current;
    try {
      const selection = await ownerApi.select(
        projectId,
        {
          specVersionId: spec.specVersionId,
          purpose: 'REVIEW',
          reviewAction: purpose,
          ...(scopeMode === 'filter'
            ? { filter: effective, excludeSampleIds: [] }
            : { items: selected.map(selectionItem) }),
        },
        operationKey(),
      );
      const [items, statistics] = await Promise.all([
        readAllPages(
          (c) => ownerApi.selectionItems(selection.selectionId, c),
          (item) => item.sampleId,
        ),
        ownerApi.selectionStatistics(selection.selectionId),
      ]);
      if (
        items.length !== selection.count ||
        statistics.total !== selection.count
      )
        throw new Error('固定范围数量不一致，请重新预览');
      if (Date.parse(selection.expiresAt) <= Date.now())
        throw new Error('固定范围已过期，请重新预览');
      if (current === freezeVersion.current)
        setFrozen({ selection, items, stats: statistics });
    } catch (e) {
      if (current === freezeVersion.current)
        setFreezeError(describeLabelingError(e));
    } finally {
      freezeLock.current = false;
      setFreezing(false);
    }
  }
  return (
    <>
      <Card size="small">
        <Space wrap>
          {stats && (
            <span>
              当前筛选：{stats.total} 个样本 · 有答案 {stats.hasResultCount} ·
              无答案 {stats.noResultCount} · 已确认 {stats.acceptedCount} ·
              待修正 {stats.needsRevisionCount} · 处理中 {stats.activeCount}
              （实时统计）
            </span>
          )}
          <Button
            disabled={action.busy || loading}
            onClick={() => {
              onSelected([]);
              void load();
            }}
          >
            刷新结果
          </Button>
        </Space>
        <details style={{ marginTop: 12 }}>
          <summary>高级筛选：按任务编号或置信度分档</summary>
          <Space wrap style={{ marginTop: 12 }}>
            {(
              [
                'packageId',
                'roundId',
                'executionId',
                'confidencePolicyId',
              ] as const
            ).map((key) => (
              <Input.Search
                key={key}
                aria-label={key}
                placeholder={
                  {
                    packageId: '按任务包编号',
                    roundId: '按轮次编号',
                    executionId: '按 AI 执行编号',
                    confidencePolicyId: '按分档策略编号',
                  }[key]
                }
                allowClear
                disabled={action.busy || loading}
                onSearch={(value) =>
                  setFilter((old) => ({
                    ...old,
                    [key]: value.trim() || undefined,
                    ...(key === 'executionId'
                      ? { confidencePolicyId: undefined, band: undefined }
                      : {}),
                  }))
                }
                style={{ width: 220 }}
              />
            ))}
            <Select
              aria-label="置信度分档筛选"
              placeholder="置信度分档"
              allowClear
              disabled={!filter.confidencePolicyId || loading || action.busy}
              value={filter.band}
              onChange={(band) => setFilter((old) => ({ ...old, band }))}
              options={['HIGH', 'MEDIUM', 'LOW', 'UNKNOWN'].map((value) => ({
                value,
                label: (
                  {
                    HIGH: '高置信度',
                    MEDIUM: '中置信度',
                    LOW: '低置信度',
                    UNKNOWN: '置信度未知',
                  } as Record<string, string>
                )[value],
              }))}
            />
          </Space>
        </details>
        {(error || action.error) && (
          <Alert type="error" showIcon message={error || action.error} />
        )}
        <Space wrap style={{ marginTop: 12 }}>
          <Tag>已勾选 {selected.length} 个具体样本</Tag>
          <Button
            disabled={
              !selected.length ||
              loading ||
              action.busy ||
              selected.some(
                (row) =>
                  !row.resultId ||
                  !row.reviewable ||
                  row.requiresResultSelection ||
                  row.activeProcessing,
              )
            }
            onClick={() => review(selected, 'ACCEPT')}
          >
            批量确认所选答案
          </Button>
          <Button
            disabled={!selected.length || loading || action.busy}
            onClick={() => review(selected, 'NEEDS_REVISION', bulkReason)}
          >
            批量标记待修正
          </Button>
          <Button
            disabled={!selected.length || action.busy}
            onClick={() => onSelected([])}
          >
            清空勾选
          </Button>
        </Space>
        {(selected.length > 0 || purpose === 'NEEDS_REVISION') && (
          <Input.TextArea
            style={{ marginTop: 12 }}
            aria-label="批量待修正原因"
            placeholder="批量标记待修正时，请在这里填写共同原因"
            value={bulkReason}
            maxLength={500}
            rows={2}
            disabled={action.busy}
            onChange={(event) => setBulkReason(event.target.value)}
          />
        )}
      </Card>
      <ReviewWorkbench
        rows={rows}
        selectedId={selectedId}
        onSelect={(id) => {
          setSelectedId(id);
          setReason('');
        }}
        chosen={selected.map((row) => row.sampleId)}
        onChoose={(keys) =>
          onSelected(rows.filter((row) => keys.includes(row.sampleId)))
        }
        filter={
          Object.fromEntries(
            Object.entries(filter).filter(
              ([, value]) => typeof value === 'string',
            ),
          ) as Record<string, string>
        }
        onFilter={(key, value) =>
          setFilter((old) => ({ ...old, [key]: value }))
        }
        onClear={() => setFilter({})}
        busy={action.busy}
        loading={loading}
        total={stats?.total}
        cursor={cursor}
        onMore={() => void load(cursor)}
        reason={reason}
        onReason={setReason}
        onReview={(row, decision) => review([row], decision)}
        onHistory={(row) => void showHistory(row)}
        onRevise={(row) => {
          setRevisionRow(row);
          setRevisionLabel(row.result?.labelId || '');
          setRevisionReason('');
        }}
        labels={spec.labels}
        allowRevision={true}
        renderPreview={(row) =>
          spec.taskType === 'TIMESERIES_ANNOTATION' ? (
            <StructuredPreview projectId={projectId} row={row} spec={spec} />
          ) : (
            <OriginalImage
              key={row.sampleId}
              projectId={projectId}
              sampleId={row.sampleId}
            />
          )
        }
      />
      <details style={{ marginTop: 16 }}>
        <summary>高级：预览并审核全部筛选结果</summary>
        <Card title="固定范围预览" style={{ marginTop: 16 }}>
          <p>
            固定预览记录样本、候选版本及审核版本。范围固定后，审核按此清单原子执行；任一样本变化或范围过期时整体拒绝，需重新预览。
          </p>
          <Space wrap>
            <Select
              aria-label="预览范围"
              value={scopeMode}
              onChange={setScopeMode}
              options={[
                { value: 'explicit', label: '明确勾选的样本' },
                { value: 'filter', label: '当前筛选的全部匹配样本' },
              ]}
            />
            <Select
              aria-label="预览用途"
              value={purpose}
              onChange={setPurpose}
              options={[
                { value: 'ACCEPT', label: '确认采用' },
                { value: 'NEEDS_REVISION', label: '待修正' },
              ]}
            />
            <Button
              loading={freezing}
              disabled={
                loading ||
                action.busy ||
                (scopeMode === 'explicit' && !selected.length)
              }
              onClick={() => void freeze()}
            >
              创建固定预览
            </Button>
          </Space>
          {freezeError && <Alert type="error" message={freezeError} />}
          {frozen && (
            <>
              <p>
                固定 {frozen.selection.count} 个样本 · 已确认{' '}
                {frozen.stats.acceptedCount} · 有答案{' '}
                {frozen.stats.hasResultCount} · 到期时间{' '}
                {new Date(frozen.selection.expiresAt).toLocaleString('zh-CN')}
              </p>
              <p style={{ overflowWrap: 'anywhere' }}>
                范围编号：{frozen.selection.selectionId} · 校验摘要：
                {frozen.selection.selectionHash}
              </p>
              {['ACCEPT', 'NEEDS_REVISION'].includes(purpose) && (
                <Button
                  disabled={
                    action.busy ||
                    freezing ||
                    Date.parse(frozen.selection.expiresAt) <= Date.now()
                  }
                  onClick={() =>
                    modal.confirm({
                      title: `对固定的 ${frozen.selection.count} 个样本执行${purpose === 'ACCEPT' ? '确认采用' : '标记待修正'}？`,
                      content:
                        '仅处理上方固定范围。待修正使用批量原因；版本变化或过期时请重新预览。',
                      onOk: async () => {
                        const body = {
                          selectionId: frozen.selection.selectionId,
                          action: purpose as 'ACCEPT' | 'NEEDS_REVISION',
                          reason: bulkReason.trim(),
                        };
                        if (purpose === 'NEEDS_REVISION' && !body.reason) {
                          action.setError('请填写批量待修正原因');
                          return;
                        }
                        const value = await action.run(
                          'frozen-review',
                          body,
                          (key) => ownerApi.atomicReview(projectId, body, key),
                          '固定范围审核已保存',
                        );
                        if (value) {
                          setFrozen(undefined);
                          onChanged();
                        }
                      },
                    })
                  }
                >
                  执行固定范围审核
                </Button>
              )}
              <Table
                rowKey="sampleId"
                size="small"
                dataSource={frozen.items}
                pagination={{ pageSize: 10 }}
                scroll={{ x: 650 }}
                columns={[
                  { title: '样本编号', dataIndex: 'sampleId' },
                  { title: '候选结果编号', dataIndex: 'resultId' },
                  { title: '审核版本', dataIndex: 'reviewRevision' },
                ]}
              />
            </>
          )}
        </Card>
      </details>
      <Drawer
        title="修订答案（保存后仍需确认）"
        open={Boolean(revisionRow)}
        onClose={() => {
          if (!action.busy) setRevisionRow(undefined);
        }}
      >
        <Space direction="vertical" style={{ width: '100%' }}>
          {spec.taskType !== 'TIMESERIES_ANNOTATION' && (
            <Select
              aria-label="修订分类"
              value={revisionLabel || undefined}
              options={spec.labels.map((l) => ({ value: l.id, label: l.name }))}
              onChange={setRevisionLabel}
              disabled={action.busy}
              style={{ width: '100%' }}
            />
          )}
          <Input.TextArea
            aria-label="修订原因"
            placeholder="填写修订原因"
            value={revisionReason}
            onChange={(e) => setRevisionReason(e.target.value)}
            maxLength={1000}
            disabled={action.busy}
          />
          {action.error && <Alert type="error" message={action.error} />}
          {spec.taskType === 'TIMESERIES_ANNOTATION' && revisionRow && (
            <StructuredPreview
              projectId={projectId}
              row={revisionRow}
              spec={spec}
              busy={action.busy || !revisionReason.trim()}
              onSave={async (result) => {
                if (!revisionRow.resultId || !revisionReason.trim())
                  throw new Error('填写修订原因后再保存');
                const body = {
                  specVersionId: spec.specVersionId,
                  sampleId: revisionRow.sampleId,
                  baseReviewRevision: revisionRow.reviewRevision,
                  resultFormat: 'annotation-record-v1',
                  result,
                  reason: revisionReason.trim(),
                };
                const value = await action.run(
                  `revise:${revisionRow.resultId}`,
                  body,
                  (key) =>
                    ownerApi.revise(
                      projectId,
                      revisionRow.resultId || '',
                      body,
                      key,
                    ),
                  '修订已保存，需另行确认采用',
                );
                if (!value) throw new Error('修订未确认成功，请重新读取核对');
                setRevisionRow(undefined);
                onChanged();
              }}
            />
          )}
          {spec.taskType !== 'TIMESERIES_ANNOTATION' && (
            <Button
              type="primary"
              disabled={
                !revisionRow?.resultId ||
                !revisionLabel ||
                !revisionReason.trim() ||
                action.busy
              }
              onClick={async () => {
                if (!revisionRow?.resultId) return;
                const body = {
                  specVersionId: spec.specVersionId,
                  sampleId: revisionRow.sampleId,
                  baseReviewRevision: revisionRow.reviewRevision,
                  resultFormat: 'image-classification-v1',
                  result: { labelId: revisionLabel },
                  reason: revisionReason.trim(),
                };
                const value = await action.run(
                  `revise:${revisionRow.resultId}`,
                  body,
                  (key) =>
                    ownerApi.revise(
                      projectId,
                      revisionRow.resultId || '',
                      body,
                      key,
                    ),
                  '修订已保存，需另行确认采用',
                );
                if (value) {
                  setRevisionRow(undefined);
                  onChanged();
                }
              }}
            >
              保存修订
            </Button>
          )}
        </Space>
      </Drawer>
      <Drawer
        title="来源历史 · 明确选择一份答案"
        open={history !== undefined}
        width={800}
        onClose={() => {
          ++historyVersion.current;
          setHistory(undefined);
        }}
      >
        {historyError && <Alert type="error" message={historyError} />}
        <Alert
          type="info"
          message="历史按结果编号分页，排序不代表时间新旧。人工提交如已有修订，审核和成果采用该来源的当前答案。"
        />
        <Table<LiveResult>
          rowKey={(row) => row.resultId || ''}
          loading={historyLoading}
          dataSource={history}
          pagination={{ pageSize: 10 }}
          scroll={{ x: 700 }}
          columns={[
            { title: '候选编号', dataIndex: 'resultId', ellipsis: true },
            {
              title: '标签',
              render: (_, row) =>
                spec.labels.find(
                  (label) => label.id === resultLabel(row.result),
                )?.name || '—',
            },
            {
              title: '来源',
              render: (_, row) => sourceNames[row.sourceKind || ''] || '—',
            },
            { title: '置信度', render: (_, row) => confidenceText(row) },
            {
              title: '审核状态',
              render: (_, row) =>
                reviewStatusNames[row.reviewStatus] || row.reviewStatus,
            },
            {
              title: '操作',
              render: (_, row) => (
                <Button
                  disabled={
                    !row.reviewable ||
                    row.activeProcessing ||
                    action.busy ||
                    historyLoading
                  }
                  onClick={() => chooseCandidate(row)}
                >
                  选择此答案
                </Button>
              ),
            },
          ]}
        />
      </Drawer>
    </>
  );
}
