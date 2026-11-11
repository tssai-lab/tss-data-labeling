import { Link, useParams } from '@umijs/max';
import { Alert, Button, Card, Space, Spin } from 'antd';
import { useEffect, useState } from 'react';
import {
  describeLabelingError,
  labelingJson,
  type Project,
} from '@/services/labeling';
import Navigation from './Navigation';
import ProjectSubmissions from './ProjectSubmissions';
import styles from './reviewWorkbench.less';

export function CapabilitiesNotice({
  withNotice = true,
}: {
  withNotice?: boolean;
} = {}) {
  return (
    <Card title="需求方处理与成果">
      {withNotice && (
        <Alert
          showIcon
          type="info"
          message="人工结果交回后仍待需求方审核，不代表任务完成"
          description="下方展示真实已交回记录。AI、审核、修订与成果导出待后端接入；接口请求失败时显示实际错误。"
        />
      )}
      <Space wrap style={{ marginTop: withNotice ? 16 : 0 }}>
        {[
          'AI范围执行',
          '需求方审核',
          '需求方修订',
          '再次发布',
          '集成建议',
          '成果CSV导出',
        ].map((name) => (
          <Button key={name} disabled>
            {name} · 待后端接入
          </Button>
        ))}
        {process.env.NODE_ENV !== 'production' &&
          process.env.LABELING_GAP_MOCK === 'true' && (
            <Link to="/labeling/demo">独立合成工作台演示</Link>
          )}
      </Space>
    </Card>
  );
}
export default function ProjectWorkflow() {
  const { projectId = '' } = useParams<{ projectId: string }>();
  const [project, setProject] = useState<Project>();
  const [error, setError] = useState('');
  const [retry, setRetry] = useState(0);
  useEffect(() => {
    const abort = new AbortController();
    setProject(undefined);
    setError('');
    void labelingJson<Project>(`/projects/${encodeURIComponent(projectId)}`, {
      signal: abort.signal,
    })
      .then((p) => {
        if (!abort.signal.aborted) setProject(p);
      })
      .catch((e) => {
        if (!abort.signal.aborted) setError(describeLabelingError(e));
      });
    return () => abort.abort();
  }, [projectId, retry]);
  return (
    <main className={styles.page}>
      <div className={styles.topbar}>
        <div
          className={`${styles.content} ${styles.realHeader}`}
          style={{ paddingTop: 16, paddingBottom: 0 }}
        >
          <Navigation />
        </div>
      </div>
      <section className={`${styles.content} ${styles.realBody}`}>
        <div className={styles.breadcrumb}>
          <span>
            <Link to="/labeling">我的项目</Link> / 结果与审核
          </span>
        </div>
        <div className={styles.heading}>
          <h1>需求方审核工作台</h1>
          <span className={styles.projectName}>
            {project?.name || (error ? '项目读取失败' : '正在读取项目')}
          </span>
        </div>
        {error && (
          <Alert
            type="error"
            message={error}
            action={
              <Button onClick={() => setRetry((n) => n + 1)}>重新读取</Button>
            }
          />
        )}
        {!project && !error && <Spin />}
        {project && (
          <>
            <Link to={`/labeling/projects/${encodeURIComponent(projectId)}`}>
              返回规范与数据
            </Link>
            <Alert
              className={styles.notice}
              type="info"
              showIcon
              message="真实项目 · 仅展示已交回人工包与提交记录"
              description="交回不等于需求方确认。审核、修订、AI与导出待后端接入，当前不可写入。"
            />
            <div
              className={styles.tabs}
              role="tablist"
              aria-label="真实工作台功能"
            >
              <Button type="text" role="tab" aria-selected="true">
                结果与审核
              </Button>
              {['范围发布', 'AI进度与分档', '来源与集成', '成果导出'].map(
                (name) => (
                  <Button key={name} disabled role="tab" aria-selected="false">
                    {name} · 待后端接入
                  </Button>
                ),
              )}
            </div>
            <CapabilitiesNotice withNotice={false} />
            <ProjectSubmissions projectId={projectId} />
          </>
        )}
      </section>
    </main>
  );
}
