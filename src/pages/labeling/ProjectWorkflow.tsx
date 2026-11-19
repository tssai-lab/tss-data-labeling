import { history, useParams } from '@umijs/max';
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
          description="在结果工作台审核与修订答案，按固定范围再次发布或交给 AI，再将已确认答案保存为成果并导出 ZIP。"
        />
      )}
      <Space wrap style={{ marginTop: withNotice ? 16 : 0 }}></Space>
    </Card>
  );
}
export default function ProjectWorkflow() {
  const { projectId = '' } = useParams<{ projectId: string }>();
  const [project, setProject] = useState<Project>();
  const [error, setError] = useState('');
  const [retry, setRetry] = useState(0);
  useEffect(() => {
    if (project?.status === 'DRAFT') {
      history.replace(
        `/labeling/projects/${encodeURIComponent(project.projectId)}?panel=publish`,
      );
    }
  }, [project?.projectId, project?.status]);
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
    <WorkspaceLayout workbench>
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
        project.status !== 'DRAFT' &&
        (['IMAGE_CLASSIFICATION', 'TIMESERIES_ANNOTATION'].includes(
          project.taskType,
        ) ? (
          <ProjectReviewWorkbench
            key={project.projectId}
            project={project}
            onProjectChanged={() => setRetry((n) => n + 1)}
          />
        ) : (
          <Alert
            type="info"
            message="该任务模板的结果工作台尚未接入，请从任务发布查看当前项目。"
          />
        ))}
    </WorkspaceLayout>
  );
}
