import { Link } from '@umijs/max';
import { Card, Table } from 'antd';
import type { MouseEvent } from 'react';
import type { SpecVersion } from '@/services/labeling';
import type { LiveResult } from '@/services/labelingOwner';
import { resultLabel } from '@/services/labelingStructured';
import StatusBadge from './StatusBadge';
import ui from './workspaceUi.less';

/** One read-only representation of the selected scope for publishing and AI. */
export default function ScopeSummary({
  projectId,
  spec,
  selected,
  title = '固定处理范围',
  disabled = false,
}: {
  projectId: string;
  spec: SpecVersion;
  selected: LiveResult[];
  title?: string;
  disabled?: boolean;
}) {
  return (
    <Card className={ui.scopeCard} title={title}>
      <dl className={ui.scopeMeta}>
        <div>
          <dt>规范</dt>
          <dd>版本 {spec.versionNo}</dd>
        </div>
        <div>
          <dt>当前选择</dt>
          <dd>{selected.length} 个样本</dd>
        </div>
        <div>
          <dt>已确认</dt>
          <dd>
            {selected.filter((row) => row.reviewStatus === 'ACCEPTED').length}{' '}
            个；再次处理需重新审核
          </dd>
        </div>
      </dl>
      {selected.length ? (
        <Table<LiveResult>
          rowKey="sampleId"
          size="small"
          dataSource={selected}
          pagination={{ pageSize: 8, hideOnSinglePage: true }}
          columns={[
            {
              title: '样本',
              render: (_, row) => (
                <span title={row.sampleId}>
                  {row.sourcePath?.split('/').pop() ||
                    `${row.sampleId.slice(0, 8)}…`}
                </span>
              ),
              ellipsis: true,
            },
            {
              title: '候选',
              render: (_, row) =>
                spec.labels.find(
                  (label) => label.id === resultLabel(row.result),
                )?.name || '—',
            },
            {
              title: '审核',
              render: (_, row) => <StatusBadge status={row.reviewStatus} />,
            },
          ]}
        />
      ) : (
        <p className={ui.emptyNote}>
          先在结果页勾选需要处理的样本，再配置本次任务。
        </p>
      )}
      <Link
        aria-disabled={disabled}
        onClick={(event: MouseEvent<HTMLAnchorElement>) => {
          if (disabled) event.preventDefault();
        }}
        to={`/labeling/projects/${encodeURIComponent(projectId)}/workbench`}
      >
        返回结果页调整范围
      </Link>
      <p>创建时固定样本、答案与审核版本；历史结果和既有成果保留。</p>
    </Card>
  );
}
