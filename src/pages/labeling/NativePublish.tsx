import {
  Alert,
  Button,
  Card,
  Checkbox,
  Input,
  InputNumber,
  Radio,
  Space,
  Table,
  Typography,
} from 'antd';
import { useEffect, useId, useState } from 'react';
import { operationKey, post } from '@/services/labeling';
import type {
  NativeProject,
  NativeSample,
  NativeSpec,
} from '@/services/nativeLabeling';
export type ReworkScope = {
  ids: string[];
  references: Record<string, string>;
  specVersionId: string;
};

export default function NativePublish({
  project,
  samples,
  version,
  reviews,
  rework,
  run,
  refresh,
  busy,
  initialSelection,
}: {
  initialSelection?: string[];
  project: NativeProject;
  samples: NativeSample[];
  version?: NativeSpec;
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
    [plan, setPlan] = useState<any>();
  const [reopen, setReopen] = useState(false);
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
  useEffect(() => {
    setSelected(rework?.ids || initialSelection || []);
    setPlan(undefined);
  }, [project.projectId, rework, version?.specVersionId, initialSelection]);
  const locked = !['DRAFT', 'OPEN'].includes(project.status);
  return (
    <Card title="选择样本并发布">
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        {!version && <Alert type="warning" message="请先保存标注设置。" />}
        {rework && (
          <Alert message="重新标注：上一轮结果作为参考，仍需明确完成并提交。" />
        )}
        <Typography.Text>
          当前规范：{version ? `第 ${version.versionNo} 版` : '尚未保存'}
        </Typography.Text>
        <Radio.Group value="HUMAN">
          <Radio value="HUMAN">人工标注</Radio>
          <Radio value="AI" disabled>
            AI 标注（暂未开放）
          </Radio>
        </Radio.Group>
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
            disabled={locked || visible.length > 10000}
            onClick={() => {
              setSelected(
                visible
                  .filter((sample) => reopen || !accepted.has(sample.sampleId))
                  .map((sample) => sample.sampleId),
              );
              setPlan(undefined);
            }}
          >
            选择筛选结果
          </Button>
          <Button
            onClick={() => {
              setSelected([]);
              setPlan(undefined);
            }}
          >
            清空选择
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
        </Space>
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
              const result = await post(
                `/projects/${project.projectId}/package-plans`,
                {
                  specVersionId: version!.specVersionId,
                  packageSize: size,
                  annotatorsPerSample: people,
                  samples: selected.map((id) => ({
                    sampleId: id,
                    ...(rework?.references[String(id)]
                      ? { referenceResultId: rework.references[String(id)] }
                      : {}),
                  })),
                },
                operationKey(),
              );
              setPlan(result);
            })
          }
        >
          生成发布计划
        </Button>
        {plan && (
          <Card size="small" title="确认发布范围">
            <p>
              {plan.sampleCount} 个样本，{plan.packageSampleCounts.length}{' '}
              个任务包，共 {plan.totalWorkload} 份标注工作。
            </p>
            <Space>
              <Button
                type="primary"
                loading={busy}
                disabled={locked}
                onClick={() =>
                  void run(async () => {
                    await post(
                      `/package-plans/${plan.planId}/publish`,
                      { selectionHash: plan.selectionHash },
                      `publish-${plan.planId}`,
                    );
                    setPlan(undefined);
                    refresh();
                  })
                }
              >
                确认发布到任务广场
              </Button>
              <Button onClick={() => setPlan(undefined)}>重新选择</Button>
            </Space>
          </Card>
        )}
      </Space>
    </Card>
  );
}
