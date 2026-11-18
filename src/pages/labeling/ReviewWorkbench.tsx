import { LeftOutlined, RightOutlined } from '@ant-design/icons';
import {
  Alert,
  Button,
  Empty,
  Input,
  Popover,
  Select,
  Table,
  Typography,
} from 'antd';
import { useEffect, useId, useState } from 'react';
import { resultLabel } from '@/services/labelingStructured';
import type { ResultRow } from '@/services/labelingWorkflow';
import s from './reviewWorkbench.less';
import StatusBadge from './StatusBadge';

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
const sampleName = (id: string, path?: string) =>
  path?.split('/').pop() ||
  (/^demo-sample-\d+$/.test(id)
    ? `样本 ${id.split('-').at(-1)?.padStart(2, '0')}`
    : id.length > 14
      ? `${id.slice(0, 8)}…`
      : id);
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
    <StatusBadge status={row.activeProcessing ? 'RUNNING' : status}>
      {row.activeProcessing ? '处理中' : reviewStatusNames[status] || status}
    </StatusBadge>
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
  renderPreview?: (row: ResultRow) => React.ReactNode;
  allowRevision?: boolean;
  onScopePreview?: () => void;
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
            options={[
              'UNREVIEWED',
              'PENDING',
              'NEEDS_REVISION',
              'ACCEPTED',
            ].map((value) => ({
              value,
              label: reviewStatusNames[value],
            }))}
          />
        </div>
        {Object.values(props.filter).some(Boolean) && (
          <div className={s.filterExtra}>
            <Button
              size="small"
              type="text"
              disabled={busy || loading}
              onClick={props.onClear}
            >
              清除筛选
            </Button>
            <span>当前筛选范围</span>
          </div>
        )}
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
            'aria-label': `${sampleName(item.sampleId, item.sourcePath)}，${props.labels.find((l) => l.id === resultLabel(item.result))?.name || '无答案'}`,
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
            columnWidth: 28,
            getCheckboxProps: (item) => ({
              disabled: busy || loading || item.activeProcessing,
            }),
          }}
          columns={[
            {
              title: '样本',
              width: 108,
              render: (_, item) => (
                <>
                  <span className={s.sampleName} title={item.sampleId}>
                    {sampleName(item.sampleId, item.sourcePath)}
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
              title: '审核状态',
              width: 80,
              render: (_, item) => <ReviewStatus row={item} />,
            },
          ]}
        />
        <div className={s.listFooter}>
          <span>
            已勾选 {props.chosen.length} 个 · 已加载 {rows.length} 行
          </span>
          {props.onScopePreview && (
            <Button
              size="small"
              disabled={busy || loading || props.total === 0}
              onClick={props.onScopePreview}
            >
              固定范围预览
            </Button>
          )}
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
      <section className={s.panel} aria-label="样本数据预览">
        <div className={s.panelHeading}>
          <h2>样本数据预览</h2>
        </div>
        <div className={s.imageMeta}>
          <span>
            {row ? sampleName(row.sampleId, row.sourcePath) : '未选择样本'}
          </span>
          <span>{row ? `${index + 1} / ${rows.length} 已加载` : ''}</span>
        </div>
        <div className={s.imageFrame} data-sample-id={row?.sampleId || ''}>
          {row && props.renderPreview ? (
            props.renderPreview(row)
          ) : row && images[row.sampleId] && !failedImage ? (
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
          {props.renderPreview
            ? '通过标注服务按样本授权读取数据'
            : '按 sampleId 绑定的本地合成图，仅用于交互演示'}
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
                  props.labels.find((l) => l.id === resultLabel(row.result))
                    ?.name || '无候选答案',
                ],
                [
                  '结果来源 / 模型',
                  `${sourceNames[row.sourceKind || ''] || '—'} · ${row.modelVersion || '不适用'}`,
                ],
                ['AI置信度', confidenceText(row)],
                [
                  '结果版本',
                  row.resultId ? `${row.resultId.slice(0, 8)}…` : '—',
                ],
                ['审核版本', `revision ${row.reviewRevision}`],
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
              <span>待修正原因</span>
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
              {props.allowRevision !== false && (
                <Button
                  type="link"
                  disabled={!canReview}
                  onClick={() => props.onRevise(row)}
                >
                  修订为新候选
                </Button>
              )}
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
