import { Card } from 'antd';
import OperationHistory from './OperationHistory';
import WorkspaceLayout from './WorkspaceLayout';
import ui from './workspaceUi.less';

export default function MyOperationHistory() {
  return (
    <WorkspaceLayout>
      <header className={ui.pageHeader}>
        <div>
          <h1>我的操作记录</h1>
          <p>仅显示本人在标注平台中已保存操作回执的操作。</p>
        </div>
      </header>
      <Card>
        <OperationHistory />
      </Card>
    </WorkspaceLayout>
  );
}
