import { history, Link, useLocation } from '@umijs/max';
import {
  Alert,
  Button,
  Card,
  Collapse,
  Empty,
  Select,
  Space,
  Tabs,
  Tag,
} from 'antd';
import { useEffect, useState } from 'react';
import type { Project } from '@/services/labeling';
import type { LiveResult, ResultFilter } from '@/services/labelingOwner';
import {
  canFinalize,
  selectedForSpec,
  type WorkbenchView,
  workbenchView,
  workbenchViews,
} from '@/services/labelingWorkspace';
import LiveAggregation from './LiveAggregation';
import LiveAi from './LiveAi';
import LivePublish from './LivePublish';
import LiveResults from './LiveResults';
import LiveRounds from './LiveRounds';
import LiveSnapshots from './LiveSnapshots';
import ProjectBreadcrumbs from './ProjectBreadcrumbs';
import ProjectManagement from './ProjectManagement';
import SubmissionReview from './SubmissionReview';

export default function ProjectReviewWorkbench({
  project,
  onProjectChanged,
}: {
  project: Project;
  onProjectChanged?: () => void;
}) {
  const location = useLocation();
  const active = workbenchView(
    new URLSearchParams(location.search).get('view'),
  );
  const specs = project.specVersions || [];
  const [specId, setSpecId] = useState(
    () =>
      [...specs].sort((a, b) => b.versionNo - a.versionNo)[0]?.specVersionId,
  );
  const spec = specs.find((item) => item.specVersionId === specId);
  const [selected, setSelected] = useState<LiveResult[]>([]),
    [refresh, setRefresh] = useState(0);
  const [visited, setVisited] = useState<Set<WorkbenchView>>(
    () => new Set([active]),
  );
  useEffect(() => {
    setVisited((old) => (old.has(active) ? old : new Set([...old, active])));
  }, [active]);
  const [busy, setBusy] = useState(false);
  const [filterRequest, setFilterRequest] = useState<{
    filter: ResultFilter;
    nonce: number;
  }>();
  const root = `/labeling/projects/${encodeURIComponent(project.projectId)}`;
  const changeView = (view: string) => {
    if (busy) return;
    const next = workbenchView(view);
    setVisited((old) => new Set([...old, next]));
    history.push(
      `${root}/workbench${next === 'review' ? '' : `?view=${next}`}`,
    );
  };
  const changed = () => setRefresh((n) => n + 1);
  if (!spec)
    return (
      <Alert
        type="warning"
        message="项目尚无规范版本"
        description={<Link to={`${root}?step=spec`}>先设置规范并发布任务</Link>}
      />
    );
  const scope = selectedForSpec(selected, spec.specVersionId);
  const common = {
    projectId: project.projectId,
    spec: { ...spec, taskType: project.taskType },
    selected: scope,
    onChanged: changed,
    onBusyChange: setBusy,
    writeBlocked:
      project.status === 'ARCHIVED'
        ? '项目已归档，结果只读；可以导出既有成果'
        : undefined,
  };
  const scopeActions = (
    <Space wrap>
      <Button disabled={busy} onClick={() => changeView('review')}>
        选择需要处理的样本
      </Button>
      <Link to={`${root}?panel=publish`}>导入新数据并发布任务</Link>
    </Space>
  );
  return (
    <>
      <ProjectBreadcrumbs projectId={project.projectId} current="结果工作台" />
      <Card size="small" style={{ marginBottom: 16 }}>
        <Space wrap style={{ width: '100%', justifyContent: 'space-between' }}>
          <div>
            <h1 style={{ margin: 0 }}>{project.name} · 结果工作台</h1>
            <p style={{ marginBottom: 0 }}>
              处理已交回的答案。准备规范、导入新数据请到
              <Link to={`${root}?panel=publish`}>任务发布</Link>。
            </p>
          </div>
          <Select
            aria-label="结果工作台规范版本"
            value={specId}
            disabled={busy}
            onChange={(value) => {
              setSpecId(value);
              setSelected([]);
              setFilterRequest(undefined);
            }}
            options={specs.map((item) => ({
              value: item.specVersionId,
              label: `规范版本 ${item.versionNo}`,
            }))}
          />
        </Space>
      </Card>
      <details style={{ marginBottom: 12 }}>
        <summary>项目状态管理</summary>
        <ProjectManagement
          project={project}
          onChanged={() => onProjectChanged?.()}
        />
      </details>
      <Tabs
        activeKey={active}
        onChange={changeView}
        items={workbenchViews.map((view) => ({
          key: view.key,
          label: view.label,
          disabled: busy,
        }))}
      />
      <p>{workbenchViews.find((view) => view.key === active)?.description}</p>
      {active !== 'sources' && (
        <Space wrap style={{ marginBottom: 12 }}>
          <Tag>已选择 {scope.length} 个样本</Tag>
          {scope.length > 0 && (
            <>
              <Button
                disabled={busy || active === 'publish'}
                onClick={() => changeView('publish')}
              >
                重新标注
              </Button>
              <Button
                disabled={busy || active === 'ai'}
                onClick={() => changeView('ai')}
              >
                交给 AI 处理
              </Button>
              <Button
                disabled={busy || active === 'export' || !canFinalize(scope)}
                onClick={() => changeView('export')}
              >
                保存为成果
              </Button>
              <Button disabled={busy} onClick={() => setSelected([])}>
                清空选择
              </Button>
            </>
          )}
        </Space>
      )}
      {(visited.has('review') || active === 'review') && (
        <div hidden={active !== 'review'}>
          <LiveResults
            {...common}
            refresh={refresh}
            onSelected={setSelected}
            filterRequest={filterRequest}
          />
        </div>
      )}
      {(visited.has('publish') || active === 'publish') && (
        <div hidden={active !== 'publish'}>
          {!scope.length && (
            <Card>
              <Empty
                description="先选择需要重新标注的样本"
                image={Empty.PRESENTED_IMAGE_SIMPLE}
              >
                {scopeActions}
              </Empty>
            </Card>
          )}
          <LivePublish {...common} />
          <Collapse
            style={{ marginTop: 16 }}
            items={[
              {
                key: 'rounds',
                label: '高级管理：轮次与撤销领取',
                children: (
                  <LiveRounds
                    projectId={project.projectId}
                    onChanged={changed}
                    onBusyChange={setBusy}
                    writeBlocked={common.writeBlocked}
                  />
                ),
              },
            ]}
          />
        </div>
      )}
      {(visited.has('ai') || active === 'ai') && (
        <div hidden={active !== 'ai'}>
          {!scope.length && (
            <Alert
              type="info"
              message="创建 AI 任务前，请先选择样本；已有任务可在下方查询。"
              action={
                <Button disabled={busy} onClick={() => changeView('review')}>
                  去选择样本
                </Button>
              }
            />
          )}
          <LiveAi
            {...common}
            active={active === 'ai'}
            initialExecutionId={
              new URLSearchParams(location.search).get('executionId') ||
              undefined
            }
            onExecution={(id) => {
              if (active === 'ai')
                history.replace(
                  `${root}/workbench?view=ai&executionId=${encodeURIComponent(id)}`,
                );
            }}
            onFilter={(
              executionId,
              confidencePolicyId,
              band,
              executionSpec,
            ) => {
              setSpecId(executionSpec);
              setSelected([]);
              setFilterRequest({
                filter: { executionId, confidencePolicyId, band },
                nonce: Date.now(),
              });
              changeView('review');
            }}
          />
        </div>
      )}
      {(visited.has('sources') || active === 'sources') && (
        <div hidden={active !== 'sources'}>
          <LiveAggregation {...common} />
          <SubmissionReview
            projectId={project.projectId}
            specs={specs.map((s) => ({ ...s, taskType: project.taskType }))}
            onChanged={changed}
            onBusyChange={setBusy}
            writeBlocked={common.writeBlocked}
          />
        </div>
      )}
      {(visited.has('export') || active === 'export') && (
        <div hidden={active !== 'export'}>
          <LiveSnapshots {...common} active={active === 'export'} />
          {!scope.length && (
            <div style={{ marginTop: 12 }}>生成新成果前，{scopeActions}</div>
          )}
        </div>
      )}
    </>
  );
}
