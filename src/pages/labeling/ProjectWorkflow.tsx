import { Link, useParams } from '@umijs/max';
import { Alert, Button, Card, Space, Spin } from 'antd';
import { useEffect, useState } from 'react';
import {
  describeLabelingError,
  labelingJson,
  type Project,
} from '@/services/labeling';
import styles from './index.less';
import Navigation from './Navigation';
import ProjectSubmissions from './ProjectSubmissions';

export function CapabilitiesNotice() {
  return (
    <Card title="需求方处理与成果">
      <Alert
        showIcon
        type="info"
        message="人工结果交回后仍待需求方审核，不代表任务完成"
        description="当前 backend 尚无选择快照、AI执行、审核/修订、再次发布、集成、成果及导出接口。真实模式显示待接入；旧版按全部已提交导出已停用。"
      />
      <Space wrap style={{ marginTop: 16 }}>
        {[
          'AI范围执行',
          '需求方审核',
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
      <section className={styles.content}>
        <Navigation />
        <h1>{project?.name || '需求方'} · 结果工作台</h1>
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
            <CapabilitiesNotice />
            <ProjectSubmissions projectId={projectId} />
          </>
        )}
      </section>
    </main>
  );
}
