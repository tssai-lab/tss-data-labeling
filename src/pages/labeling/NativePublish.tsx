import {
  Alert,
  Button,
  Card,
  Checkbox,
  Input,
  InputNumber,
  Radio,
  Select,
  Space,
  Table,
  Typography,
} from 'antd';
import { useEffect, useId, useMemo, useRef, useState } from 'react';
import { operationKey, post } from '@/services/labeling';
import type {
  NativeProject,
  NativeSample,
  NativeSpec,
} from '@/services/nativeLabeling';
import NativeAi from './NativeAi';
import ui from './workspaceUi.less';
export type ReworkScope = {
  ids: string[];
  references: Record<string, string>;
  specVersionId: string;
};

export default function NativePublish({
  project,
  samples,
  version,
  specVersions,
  onVersionChange,
  reviews,
  rework,
  run,
  refresh,
  busy,
  initialSelection,
  onPublishAi,
  mode,
  onModeChange,
}: {
  mode: 'HUMAN' | 'AI';
  onModeChange: (mode: 'HUMAN' | 'AI') => void;
  initialSelection?: string[];
  onPublishAi: (
    samples: NativeSample[],
    version: NativeSpec,
    config: { modelId: string; instructions: string; reopenAccepted: boolean },
  ) => void;
  project: NativeProject;
  samples: NativeSample[];
  version?: NativeSpec;
  specVersions: NativeSpec[];
  onVersionChange: (specVersionId: string) => void;
  reviews: any[];
  rework?: ReworkScope;
  run: (action: () => Promise<void>) => Promise<void>;
  refresh: () => void;
  busy: boolean;
}) {
  const sizeId = useId(),
    peopleId = useId();
  const [selected, setSelected] = useState<React.Key[]>([]),
    [size, setSize] = useState(10),
    [people, setPeople] = useState(1),
    [plan, setPlan] = useState<any>(),
    [publishedPlan, setPublishedPlan] = useState<any>();
  const [reopen, setReopen] = useState(false);
  const planAttempt = useRef<{ signature: string; key: string } | undefined>(
    undefined,
  );
  const [aiPage, setAiPage] = useState(false);
  const [search, setSearch] = useState('');
  const visible = samples.filter((sample) =>
    (sample.metadata.displayName || sample.sourcePath)
      .toLowerCase()
      .includes(search.toLowerCase()),
  );
  const accepted = new Set(
    reviews
      .filter(
        (r) =>
          r.specVersionId === version?.specVersionId && r.status === 'ACCEPTED',
      )
      .map((r) => r.sampleId),
  );
  const selectableIds = visible
    .filter((sample) => reopen || !accepted.has(sample.sampleId))
    .map((sample) => sample.sampleId);
  const selectionWithVisible = [
    ...new Set([...selected.map(String), ...selectableIds]),
  ];
  const allVisibleSelected =
    selectableIds.length > 0 &&
    selectableIds.every((id) => selected.includes(id));
  function selectVisible(checked: boolean): void {
    const visibleKeys = new Set(selectableIds);
    setSelected(
      checked
        ? selectionWithVisible
        : selected.filter((id) => !visibleKeys.has(String(id))),
    );
    setPlan(undefined);
  }
  const selectedSamples = useMemo(
    () =>
      selected.map((id) => {
        const sampleId = String(id);
        return (
          samples.find((sample) => sample.sampleId === sampleId) || {
            sampleId,
            batchId: '',
            sourcePath: '样本详情暂未加载',
            mediaType: '',
            contentSha256: '',
            metadata: { displayName: undefined },
          }
        );
      }),
    [selected, samples],
  );
  const specLabels = version?.labels.map((label) => label.name) || [];
  const locked = !['DRAFT', 'OPEN'].includes(project.status);
  const canConfigureAi =
    !locked &&
    Boolean(version) &&
    selected.length > 0 &&
    selected.length <= 10000 &&
    !busy;
  useEffect(() => {
    setSelected(rework?.ids || initialSelection || []);
    setPlan(undefined);
    setPublishedPlan(undefined);
    setAiPage(false);
  }, [project.projectId, rework, version?.specVersionId, initialSelection]);
  if (aiPage) {
    return (
      <div className={ui.aiConsole}>
        <header className={ui.aiPageHeader}>
          <div>
            <span>
              AI 标注 · 规范 v{version?.versionNo} · 已选{' '}
              {selectedSamples.length} 个样本
            </span>
          </div>
          <Button onClick={() => setAiPage(false)}>返回选择范围</Button>
        </header>
        <NativeAi
          project={project}
          selectedSamples={selectedSamples}
          version={version}
          submitting={busy}
          onPublish={(config) => {
            if (version)
              onPublishAi(selectedSamples, version, {
                ...config,
                reopenAccepted: reopen,
              });
          }}
        />
      </div>
    );
  }
  return (
    <Card>
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        {!version && <Alert type="warning" message="请先保存标注设置。" />}
        {publishedPlan && (
          <Alert
            type="success"
            showIcon
            message={`已发布 ${publishedPlan.packages.length} 个任务包，共 ${publishedPlan.sampleCount} 个样本`}
            description={publishedPlan.packages
              .map(
                (pkg: { sequenceNo: number; sampleCount: number }) =>
                  `第 ${pkg.sequenceNo} 包：${pkg.sampleCount} 个样本`,
              )
              .join('；')}
          />
        )}
        {rework && (
          <Alert message="重新标注：上一轮结果作为参考，仍需明确完成并提交。" />
        )}
        <Space wrap>
          <Typography.Text>本轮使用规范</Typography.Text>
          <Select
            aria-label="选择本轮使用的规范版本"
            style={{ minWidth: 160 }}
            value={version?.specVersionId}
            placeholder="尚未保存规范"
            disabled={!specVersions.length || !!rework}
            onChange={onVersionChange}
            options={specVersions.map((item) => ({
              value: item.specVersionId,
              label: `第 ${item.versionNo} 版`,
            }))}
          />
        </Space>
        <Radio.Group
          value={mode}
          onChange={(event) => {
            onModeChange(event.target.value);
            setAiPage(false);
            setPlan(undefined);
            setPublishedPlan(undefined);
          }}
          disabled={locked || !!plan}
        >
          <Radio value="HUMAN">人工标注</Radio>
          <Radio value="AI">AI 标注</Radio>
        </Radio.Group>
        {mode === 'AI' && (
          <Alert
            type="info"
            showIcon
            message="AI 预标注"
            description="选择本轮样本后，配置模型并提交后台执行，完成后核查候选结果。"
          />
        )}
        <Checkbox
          checked={reopen}
          disabled={locked || !!plan}
          onChange={(e) => {
            setReopen(e.target.checked);
            setSelected((ids) => ids.filter((id) => !accepted.has(String(id))));
          }}
        >
          允许重新处理已确认样本（发布后取消这些样本的确认）
        </Checkbox>
        <Input.Search
          aria-label="筛选发布样本"
          placeholder="搜索样本名称或来源"
          value={search}
          onChange={(event) => setSearch(event.target.value)}
        />
        <Space wrap>
          <Button
            disabled={
              locked ||
              !selectableIds.length ||
              (!allVisibleSelected && selectionWithVisible.length > 10000) ||
              busy ||
              !!plan
            }
            onClick={() => selectVisible(!allVisibleSelected)}
          >
            {allVisibleSelected ? '取消全选筛选结果' : '全选筛选结果'}（共{' '}
            {selectableIds.length} 个）
          </Button>
          <span>
            已选择 {selected.length} 个样本；每次发布最多 10000 个，可分批发布。
          </span>
        </Space>
        <Table
          rowKey="sampleId"
          size="small"
          dataSource={visible}
          scroll={{ x: 700 }}
          rowSelection={{
            hideSelectAll: true,
            columnWidth: 155,
            columnTitle: '选择',
            preserveSelectedRowKeys: true,
            selectedRowKeys: selected,
            onChange: (ids) => {
              setSelected(ids);
              setPlan(undefined);
            },
            getCheckboxProps: (sample) => ({
              disabled: locked || (!reopen && accepted.has(sample.sampleId)),
            }),
          }}
          columns={[
            {
              title: '样本',
              render: (_, sample) =>
                sample.metadata.displayName || sample.sourcePath,
            },
            {
              title: '审核状态',
              render: (_, sample) =>
                accepted.has(sample.sampleId)
                  ? '已确认'
                  : reviews.some(
                        (r) =>
                          r.sampleId === sample.sampleId &&
                          r.status === 'NEEDS_REVISION',
                      )
                    ? '需重标'
                    : '待处理',
            },
          ]}
        />
        {mode === 'HUMAN' && (
          <Space wrap>
            <label htmlFor={sizeId}>
              每包样本数{' '}
              <InputNumber
                id={sizeId}
                aria-label="每包样本数"
                min={1}
                max={1000}
                value={size}
                disabled={!!plan}
                onChange={(v) => setSize(v || 1)}
              />
            </label>
            <label htmlFor={peopleId}>
              每个样本标注人数{' '}
              <InputNumber
                id={peopleId}
                aria-label="每个样本标注人数"
                min={1}
                max={10}
                value={people}
                disabled={!!plan}
                onChange={(v) => setPeople(v || 1)}
              />
            </label>
            <Button
              disabled={
                locked ||
                !version ||
                !selected.length ||
                selected.length > 10000 ||
                !!plan
              }
              loading={busy}
              onClick={() =>
                void run(async () => {
                  if (!version) return;
                  const body = {
                    specVersionId: version.specVersionId,
                    packageSize: size,
                    annotatorsPerSample: people,
                    reopenAccepted: reopen,
                    samples: selected.map((id) => ({
                      sampleId: id,
                      ...(rework?.references[String(id)]
                        ? { referenceResultId: rework.references[String(id)] }
                        : {}),
                    })),
                  };
                  const signature = JSON.stringify([project.projectId, body]);
                  if (planAttempt.current?.signature !== signature) {
                    planAttempt.current = { signature, key: operationKey() };
                  }
                  const result = await post(
                    `/projects/${project.projectId}/package-plans`,
                    body,
                    planAttempt.current.key,
                  );
                  setPlan(result);
                })
              }
            >
              生成发布计划
            </Button>
          </Space>
        )}
        {mode === 'HUMAN' && selected.length > 0 && (
          <Typography.Text type="secondary">
            预计生成 {Math.ceil(selected.length / size)} 个任务包，每包最多{' '}
            {size} 个样本
            {selected.length % size > 0
              ? `，最后一包 ${selected.length % size} 个样本。剩余样本会单独成包。`
              : '。'}
          </Typography.Text>
        )}
        {mode === 'AI' && (
          <div className={ui.aiNextStep}>
            <div>
              <strong>本轮使用 AI 标注</strong>
              <span>
                已选 {selected.length} 个样本 · 下一步选择模型与执行要求
              </span>
            </div>
            <Button
              className={`${ui.aiDemoCta} ${!canConfigureAi ? ui.aiDemoCtaDisabled : ''}`}
              size="large"
              type="primary"
              style={
                canConfigureAi
                  ? {
                      backgroundColor: '#1c3faa',
                      borderColor: '#1c3faa',
                      color: '#fff',
                    }
                  : undefined
              }
              disabled={!canConfigureAi}
              onClick={() => {
                setAiPage(true);
                window.scrollTo({ top: 0, behavior: 'smooth' });
              }}
            >
              <span
                style={
                  canConfigureAi
                    ? {
                        color: '#fff',
                        WebkitTextFillColor: '#fff',
                        fontWeight: 700,
                      }
                    : undefined
                }
              >
                下一步：配置 AI 标注
              </span>
            </Button>
          </div>
        )}
        {plan && (
          <Card size="small" title="确认发布范围">
            <Alert
              type="warning"
              showIcon
              message="请在发布前核对本轮内容"
              description={`${plan.sampleCount} 个样本，${plan.packageSampleCounts.length} 个任务包，共 ${plan.totalWorkload} 份标注工作。确认后将按当前样本清单和规范版本发布。`}
              style={{ marginBottom: 16 }}
            />
            <Typography.Paragraph>
              分包明细：
              {plan.packageSampleCounts
                .map(
                  (count: number, index: number) =>
                    `第 ${index + 1} 包 ${count} 个样本`,
                )
                .join('；')}
            </Typography.Paragraph>
            <Typography.Title level={5}>本轮样本范围</Typography.Title>
            <Typography.Paragraph type="secondary">
              当前可核对全部选中样本及上方各包数量；服务尚未提供预览中每包的样本明细。
            </Typography.Paragraph>
            <Table
              size="small"
              rowKey="sampleId"
              dataSource={selectedSamples}
              pagination={{
                pageSize: 5,
                showTotal: (total) => `共 ${total} 个样本`,
              }}
              columns={[
                {
                  title: '样本名称',
                  render: (_, sample) =>
                    sample.metadata.displayName || sample.sourcePath,
                },
                {
                  title: '样本 ID',
                  render: (_, sample) => (
                    <Typography.Text
                      copyable={{ text: sample.sampleId }}
                      title={sample.sampleId}
                    >
                      {sample.sampleId.slice(0, 12)}
                    </Typography.Text>
                  ),
                },
              ]}
            />
            <Typography.Title level={5} style={{ marginTop: 20 }}>
              本轮标注规范
            </Typography.Title>
            <Space direction="vertical" style={{ width: '100%' }}>
              <Typography.Text>
                第 {version?.versionNo} 版 · 模板{' '}
                {version?.editorConfig.templateId}（
                {version?.editorConfig.templateVersion}）
              </Typography.Text>
              {version?.instructions && (
                <Typography.Paragraph style={{ whiteSpace: 'pre-wrap' }}>
                  {version.instructions}
                </Typography.Paragraph>
              )}
              {specLabels.length > 0 && (
                <Typography.Text>标签：{specLabels.join('、')}</Typography.Text>
              )}
              <details>
                <summary>查看完整标注配置</summary>
                <pre
                  style={{
                    maxHeight: 260,
                    overflow: 'auto',
                    whiteSpace: 'pre-wrap',
                    overflowWrap: 'anywhere',
                    padding: 12,
                    background: '#f5f7fa',
                    borderRadius: 6,
                  }}
                >
                  {version?.editorConfig.labelConfig || '暂无配置'}
                </pre>
              </details>
            </Space>
            <Space>
              <Button
                type="primary"
                loading={busy}
                disabled={locked}
                onClick={() =>
                  void run(async () => {
                    const published = await post<any>(
                      `/package-plans/${plan.planId}/publish`,
                      { selectionHash: plan.selectionHash },
                      `publish-${plan.planId}`,
                    );
                    setPublishedPlan(published);
                    setPlan(undefined);
                    planAttempt.current = undefined;
                    refresh();
                  })
                }
              >
                确认发布到任务广场
              </Button>
              <Button
                onClick={() => {
                  planAttempt.current = undefined;
                  setPlan(undefined);
                }}
              >
                重新选择
              </Button>
            </Space>
          </Card>
        )}
      </Space>
    </Card>
  );
}
