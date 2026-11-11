import { LeftOutlined, RightOutlined } from '@ant-design/icons';
import {
  Alert,
  Button,
  Empty,
  Input,
  Popover,
  Select,
  Table,
  Tag,
  Typography,
} from 'antd';
import { useEffect, useId, useState } from 'react';
import type { ResultRow } from '@/services/labelingWorkflow';
import s from './reviewWorkbench.less';

export const reviewStatusNames: Record<string, string> = {
  PENDING: '待审核',
  UNREVIEWED: '待审核',
  ACCEPTED: '已确认',
  NEEDS_REVISION: '待修正',
};
export const sourceNames: Record<string, string> = {
  AI: 'AI',
  HUMAN: '人工',
  OWNER: '需求方',
  AGGREGATION: '集成',
};
export const confidenceText = (row: ResultRow) =>
  !row.resultId
    ? '—'
    : row.sourceKind !== 'AI'
      ? '不适用'
      : row.confidence === null || row.confidence === undefined
        ? '未知'
        : String(row.confidence);
const sampleName = (id: string) =>
  /^demo-sample-\d+$/.test(id)
    ? `样本 ${id.split('-').at(-1)?.padStart(2, '0')}`
    : id;
const images = Object.freeze(
  Object.fromEntries(
    Array.from({ length: 12 }, (_, i) => [
      `demo-sample-${i + 1}`,
      `/labeling-demo/sample-${String(i + 1).padStart(2, '0')}.svg`,
    ]),
  ),
);
export function ReviewStatus({ row }: { row: ResultRow }) {
  const status =
    row.reviewStatus === 'ACCEPTED' && row.resultId !== row.selectedResultId
      ? 'PENDING'
      : row.reviewStatus;
  return (
    <Tag
      color={
        row.activeProcessing ? 'blue' : status === 'ACCEPTED' ? 'green' : 'gold'
      }
    >
      {row.activeProcessing ? '处理中' : reviewStatusNames[status] || status}
    </Tag>
  );
}
type Props = {
  rows: ResultRow[];
  selectedId: string;
  onSelect: (id: string) => void;
  chosen: React.Key[];
  onChoose: (keys: React.Key[]) => void;
  filter: Record<string, string>;
  onFilter: (key: string, value?: string) => void;
  onClear: () => void;
  busy: boolean;
  loading: boolean;
  total?: number;
  cursor: string | null;
  onMore: () => void;
  reason: string;
  onReason: (value: string) => void;
  onReview: (row: ResultRow, action: 'ACCEPT' | 'NEEDS_REVISION') => void;
  onHistory: (row: ResultRow) => void;
  onRevise: (row: ResultRow) => void;
  labels: readonly { id: string; name: string }[];
};
export default function ReviewWorkbench(props: Props) {
  const { rows, selectedId, busy, loading } = props;
  const index = rows.findIndex((row) => row.sampleId === selectedId),
    row = rows[index];
  const [failedImage, setFailedImage] = useState(false);
  const reasonId = useId();
  useEffect(() => setFailedImage(false), [selectedId]);
  const canReview = Boolean(
    row &&
      !busy &&
      !loading &&
      !row.activeProcessing &&
      row.resultId &&
      row.reviewable &&
      !row.requiresResultSelection,
  );
  const canMark = Boolean(
    row &&
      !busy &&
      !loading &&
      !row.activeProcessing &&
      (!row.resultId || row.reviewable),
  );
  return (
    <section className={s.columns} aria-label="样本审核三栏工作台">
      <section className={s.panel} aria-label="样本列表">
        <div className={s.panelHeading}>
          <h2>样本列表</h2>
          <Typography.Text type="secondary">
            {props.chosen.length ? `已勾选 ${props.chosen.length}` : ''}
          </Typography.Text>
        </div>
        <div className={s.filters}>
          <Select
            aria-label="来源筛选"
            placeholder="结果来源"
            allowClear
            disabled={busy || loading}
            value={props.filter.sourceKind}
            onChange={(value) => props.onFilter('sourceKind', value)}
            options={Object.entries(sourceNames).map(([value, label]) => ({
              value,
              label,
            }))}
          />
          <Select
            aria-label="审核状态筛选"
            placeholder="审核状态"
            allowClear
            disabled={busy || loading}
            value={props.filter.reviewStatus}
            onChange={(value) => props.onFilter('reviewStatus', value)}
            options={['PENDING', 'NEEDS_REVISION', 'ACCEPTED'].map((value) => ({
              value,
              label: reviewStatusNames[value],
            }))}
          />
        </div>
        <div className={s.filterExtra}>
          <Select
            aria-label="答案筛选"
            placeholder="全部候选状态"
            allowClear
            value={props.filter.hasResult}
            disabled={busy || loading}
            onChange={(value) => props.onFilter('hasResult', value)}
            options={[
              { value: 'true', label: '有候选答案' },
              { value: 'false', label: '无候选答案' },
            ]}
          />
          <Button disabled={busy || loading} onClick={props.onClear}>
            清除筛选
          </Button>
        </div>
        {Object.entries(props.filter)
          .filter(
            ([key]) =>
              !['sourceKind', 'reviewStatus', 'hasResult'].includes(key),
          )
          .map(([key, value]) => (
            <Tag key={key}>
              {key}: {value}
            </Tag>
          ))}
        <Table<ResultRow>
          className={s.sampleTable}
          rowKey="sampleId"
          size="small"
          pagination={false}
          loading={loading}
          dataSource={rows}
          scroll={{ y: 380 }}
          rowClassName={(item) =>
            item.sampleId === selectedId ? s.activeRow : ''
          }
          onRow={(item) => ({
            tabIndex: 0,
            'aria-label': `${sampleName(item.sampleId)}，${props.labels.find((l) => l.id === item.result?.labelId)?.name || '无答案'}`,
            onClick: (event) => {
              if (
                !(event.target as HTMLElement).closest(
                  'button,a,input,.ant-checkbox-wrapper',
                )
              )
                props.onSelect(item.sampleId);
            },
            onKeyDown: (event) => {
              if (
                event.target === event.currentTarget &&
                ['Enter', ' '].includes(event.key)
              ) {
                event.preventDefault();
                props.onSelect(item.sampleId);
              }
            },
          })}
          rowSelection={{
            selectedRowKeys: props.chosen,
            onChange: props.onChoose,
            columnWidth: 30,
            getCheckboxProps: (item) => ({
              disabled: busy || loading || item.activeProcessing,
            }),
          }}
          columns={[
            {
              title: '样本',
              width: 82,
              render: (_, item) => (
                <>
                  <span className={s.sampleName}>
                    {sampleName(item.sampleId)}
                  </span>
                  <Popover
                    trigger="click"
                    title={item.sampleId}
                    content={
                      <Typography.Paragraph className={s.path} copyable>
                        {item.sourcePath}
                      </Typography.Paragraph>
                    }
                  >
                    <Button
                      type="link"
                      className={s.pathButton}
                      aria-label={`查看${sampleName(item.sampleId)}原路径`}
                    >
                      原路径
                    </Button>
                  </Popover>
                </>
              ),
            },
            {
              title: '标签',
              render: (_, item) =>
                props.labels.find((l) => l.id === item.result?.labelId)?.name ||
                '—',
            },
            {
              title: '来源',
              width: 57,
              render: (_, item) => sourceNames[item.sourceKind || ''] || '—',
            },
            {
              title: '审核状态',
              width: 80,
              render: (_, item) => <ReviewStatus row={item} />,
            },
          ]}
        />
        <div className={s.listFooter}>
          <span>
            当前范围 {props.total ?? '—'} 个样本 · 已加载 {rows.length} 行
          </span>
          {props.cursor && (
            <Button
              size="small"
              disabled={busy || loading}
              onClick={props.onMore}
            >
              加载更多
            </Button>
          )}
        </div>
      </section>
      <section className={s.panel} aria-label="原图预览">
        <div className={s.panelHeading}>
          <h2>原图预览</h2>
        </div>
        <div className={s.imageMeta}>
          <span>{row ? sampleName(row.sampleId) : '未选择样本'}</span>
          <span>{row ? `${index + 1} / ${rows.length} 已加载` : ''}</span>
        </div>
        <div className={s.imageFrame} data-sample-id={row?.sampleId || ''}>
          {row && images[row.sampleId] && !failedImage ? (
            <img
              key={row.sampleId}
              src={images[row.sampleId]}
              alt={`${sampleName(row.sampleId)}的本地合成图`}
              onError={() => setFailedImage(true)}
            />
          ) : (
            <Empty
              image={Empty.PRESENTED_IMAGE_SIMPLE}
              description={
                row
                  ? '此样本暂无本地合成图'
                  : loading
                    ? '正在读取样本'
                    : '当前筛选无样本'
              }
            />
          )}
        </div>
        <div className={s.imageNav}>
          <Button
            icon={<LeftOutlined aria-hidden="true" />}
            disabled={busy || loading || index <= 0}
            onClick={() => props.onSelect(rows[index - 1].sampleId)}
          >
            上一张
          </Button>
          <Button
            disabled={busy || loading || index < 0 || index >= rows.length - 1}
            onClick={() => props.onSelect(rows[index + 1].sampleId)}
          >
            下一张 <RightOutlined aria-hidden="true" />
          </Button>
        </div>
        <p className={s.imageCaption}>
          按 sampleId 绑定的本地合成图，仅用于交互演示
        </p>
      </section>
      <section className={s.panel} aria-label="审核详情">
        <div className={s.panelHeading}>
          <h2>审核详情</h2>
          {row && <ReviewStatus row={row} />}
        </div>
        {row ? (
          <>
            {row.requiresResultSelection && (
              <Alert
                type="warning"
                showIcon
                message="有多份候选，请从来源历史选择具体版本"
              />
            )}
            <dl className={s.details}>
              {[
                [
                  '候选标签',
                  props.labels.find((l) => l.id === row.result?.labelId)
                    ?.name || '无候选答案',
                ],
                [
                  '结果来源 / 模型',
                  `${sourceNames[row.sourceKind || ''] || '—'} · ${row.modelVersion || '不适用'}`,
                ],
                ['AI置信度', confidenceText(row)],
                ['结果版本', row.resultId || '—'],
              ].map(([label, value]) => (
                <div className={s.detailRow} key={label}>
                  <dt>{label}</dt>
                  <dd>{value}</dd>
                </div>
              ))}
            </dl>
            <Button
              type="link"
              className={s.historyLink}
              disabled={busy || loading}
              onClick={() => props.onHistory(row)}
            >
              查看来源历史
            </Button>
            <label className={s.reason} htmlFor={reasonId}>
              <span>操作原因</span>
              <Input.TextArea
                id={reasonId}
                aria-label="操作原因"
                placeholder="标记待修正时必填"
                rows={4}
                maxLength={500}
                showCount
                value={props.reason}
                onChange={(event) => props.onReason(event.target.value)}
                disabled={busy || loading}
              />
            </label>
            <div className={s.reviewActions}>
              <Button
                type="primary"
                disabled={!canReview}
                onClick={() => props.onReview(row, 'ACCEPT')}
              >
                确认采用
              </Button>
              <Button
                disabled={!canMark}
                onClick={() => props.onReview(row, 'NEEDS_REVISION')}
              >
                标记待修正
              </Button>
              <Button
                type="link"
                disabled={!canReview}
                onClick={() => props.onRevise(row)}
              >
                修订为新候选
              </Button>
            </div>
            <div className={s.smallHint}>
              审核 revision {row.reviewRevision} · {row.specVersionId}
              。确认仅绑定此样本及候选版本，不代表范围成果完成。
              {row.errorCode ? `处理提示：${row.errorCode}` : ''}
            </div>
          </>
        ) : (
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description="选择左侧样本后查看与审核"
          />
        )}
      </section>
    </section>
  );
}
