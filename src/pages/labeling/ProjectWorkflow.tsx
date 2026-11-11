import { Link, useParams } from '@umijs/max';
import { Alert, Button, Card, Space, Spin } from 'antd';
import { useEffect, useState } from 'react';
import {
  describeLabelingError,
  labelingJson,
  type Project,
} from '@/services/labeling';
import ProjectReviewWorkbench from './ProjectReviewWorkbench';
import WorkspaceLayout from './WorkspaceLayout';

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
      .then((value) => {
        if (!abort.signal.aborted) setProject(value);
      })
      .catch((e) => {
        if (!abort.signal.aborted) setError(describeLabelingError(e));
      });
    return () => abort.abort();
  }, [projectId, retry]);
  return (
    <WorkspaceLayout project={project} workbench>
      {error && (
        <div style={{ padding: 24 }}>
          <Alert
            type="error"
            showIcon
            message={error}
            action={
              <Button onClick={() => setRetry((n) => n + 1)}>重新读取</Button>
            }
          />
        </div>
      )}
      {!project && !error && (
        <div style={{ padding: 24 }}>
          <Spin tip="正在读取项目">
            <div style={{ height: 120 }} />
          </Spin>
        </div>
      )}
      {project &&
        (project.taskType === 'IMAGE_CLASSIFICATION' ? (
          <ProjectReviewWorkbench key={project.projectId} project={project} />
        ) : (
          <Alert
            type="info"
            message="此工作台仅支持图片单标签分类，请从项目数据查看当前项目。"
          />
        ))}
    </WorkspaceLayout>
  );
}
