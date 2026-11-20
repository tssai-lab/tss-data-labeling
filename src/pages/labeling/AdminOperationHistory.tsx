import { Alert, Card, Spin } from 'antd';
import { useEffect, useState } from 'react';
import { describeLabelingError } from '@/services/labeling';
import { tasks } from '@/services/labelingTasks';
import OperationHistory from './OperationHistory';
import WorkspaceLayout from './WorkspaceLayout';
import ui from './workspaceUi.less';

export default function AdminOperationHistory() {
  const [authorized, setAuthorized] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    const abort = new AbortController();
    void tasks
      .me(abort.signal)
      .then((identity) => {
        if (abort.signal.aborted) return;
        if (!identity.labelingAdmin) {
          setError('仅标注管理员可查看全平台操作记录。');
          return;
        }
        setAuthorized(true);
      })
      .catch((e) => {
        if (!abort.signal.aborted) setError(describeLabelingError(e));
      })
      .finally(() => {
        if (!abort.signal.aborted) setLoading(false);
      });
    return () => abort.abort();
  }, []);

  return (
    <WorkspaceLayout>
      <header className={ui.pageHeader}>
        <div>
          <h1>全平台操作记录</h1>
          <p>查看标注平台已持久保存操作回执及操作人信息。</p>
        </div>
      </header>
      {error && <Alert type="error" showIcon message={error} />}
      {loading ? (
        <Spin />
      ) : (
        authorized && (
          <Card>
            <OperationHistory global />
          </Card>
        )
      )}
    </WorkspaceLayout>
  );
}
