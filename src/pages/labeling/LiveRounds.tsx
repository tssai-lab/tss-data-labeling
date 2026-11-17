import {
  Alert,
  App,
  Button,
  Card,
  Input,
  InputNumber,
  Space,
  Table,
} from 'antd';
import { useState } from 'react';
import { describeLabelingError } from '@/services/labeling';
import { ownerApi, type Round } from '@/services/labelingOwner';
import ProjectHistory from './ProjectHistory';
import useOwnerAction from './useOwnerAction';

export default function LiveRounds({
  projectId,
  onChanged,
  onBusyChange,
  writeBlocked,
}: {
  projectId: string;
  onChanged: () => void;
  onBusyChange?: (busy: boolean) => void;
  writeBlocked?: string;
}) {
  const { modal } = App.useApp(),
    action = useOwnerAction(onBusyChange, writeBlocked);
  const [known, setKnown] = useState(''),
    [round, setRound] = useState<Round>(),
    [reason, setReason] = useState('');
  const [instructions, setInstructions] = useState(''),
    [error, setError] = useState(''),
    [loading, setLoading] = useState(false);
  const [assignment, setAssignment] = useState(''),
    [revision, setRevision] = useState<number | null>(null);
  async function read(id = known.trim()) {
    setLoading(true);
    setError('');
    setRound(undefined);
    try {
      const value = await ownerApi.round(id);
      if (value.projectId !== projectId) throw new Error('轮次不属于当前项目');
      setRound(value);
    } catch (e) {
      setError(describeLabelingError(e));
    } finally {
      setLoading(false);
    }
  }
  function change(cancel: boolean) {
    if (!round || (cancel && !reason.trim())) {
      action.setError('请先读取轮次，并填写取消原因');
      return;
    }
    const captured = round,
      body = cancel
        ? { baseRevision: captured.revision, reason: reason.trim() }
        : { instructions };
    modal.confirm({
      title: cancel ? '取消本轮未完成工作？' : '对相同包成员发起新一轮？',
      content: cancel
        ? '撤销尚未提交的领取。已交回结果保留；含已提交结果的轮次将关闭。'
        : '保留已有结果，为相同样本创建新的人工轮次；已确认样本会重新进入待审核流程。',
      onOk: async () => {
        const value = await action.run(
          `${cancel ? 'cancel-round' : 're-round'}:${captured.roundId}`,
          body,
          (key) =>
            cancel
              ? ownerApi.cancelRound(
                  captured.roundId,
                  { baseRevision: captured.revision, reason: reason.trim() },
                  key,
                )
              : captured.packageId
                ? ownerApi.reRound(captured.packageId, instructions, key)
                : Promise.reject(new Error('轮次没有人工包')),
          cancel ? '轮次已取消或关闭' : '新轮次已发布',
        );
        if (value) {
          setRound(value);
          setKnown(value.roundId);
          onChanged();
        }
      },
    });
  }
  function revoke() {
    if (!assignment.trim() || revision === null || !reason.trim()) return;
    const body = { baseRevision: revision, reason: reason.trim() };
    modal.confirm({
      title: '撤销指定领取？',
      content: `领取 ${assignment.trim()}，预期版本 ${revision}。仅该领取所属项目的需求方可撤销；已提交领取不能撤销。`,
      onOk: async () => {
        const value = await action.run(
          `revoke:${assignment.trim()}`,
          body,
          (key) => ownerApi.revoke(assignment.trim(), body, key),
          '领取已撤销',
        );
        if (value) {
          setRevision(value.revision);
          onChanged();
        }
      },
    });
  }
  return (
    <Card title="人工轮次与领取管理" style={{ marginTop: 16 }}>
      <Alert
        type="info"
        message="查看项目人工与 AI 轮次"
        description="从服务器历史选择轮次，再查看详情或管理未完成工作。"
      />
      <ProjectHistory
        projectId={projectId}
        kind="rounds"
        onOpen={(row) => {
          if (row.roundId) {
            setKnown(row.roundId);
            void read(row.roundId);
          }
        }}
      />
      {(error || action.error) && (
        <Alert type="error" message={error || action.error} />
      )}
      <Space wrap style={{ marginTop: 16 }}>
        <Input
          aria-label="已知轮次编号"
          placeholder="输入 roundId"
          value={known}
          onChange={(e) => setKnown(e.target.value)}
          disabled={action.busy || loading}
          style={{ width: 340 }}
        />
        <Button
          disabled={!known.trim() || action.busy || loading}
          onClick={() => void read()}
        >
          读取轮次
        </Button>
      </Space>
      {round && (
        <>
          <Table
            rowKey="roundId"
            dataSource={[round]}
            pagination={false}
            scroll={{ x: 700 }}
            columns={[
              { title: '轮次', dataIndex: 'roundId' },
              { title: '状态', dataIndex: 'status' },
              { title: '模式', dataIndex: 'mode' },
              { title: '样本数', dataIndex: 'sampleCount' },
              { title: '已提交份数', dataIndex: 'submittedCount' },
              { title: '版本', dataIndex: 'revision' },
            ]}
          />
          <Input.TextArea
            aria-label="新轮次补充说明"
            placeholder="新轮次补充说明"
            maxLength={500}
            value={instructions}
            disabled={action.busy}
            onChange={(e) => setInstructions(e.target.value)}
          />
          <Space wrap style={{ marginTop: 12 }}>
            <Button
              disabled={
                action.busy ||
                loading ||
                round.mode !== 'HUMAN_POOL' ||
                !round.packageId ||
                round.status === 'OPEN'
              }
              onClick={() => change(false)}
            >
              相同包发起新轮次
            </Button>
            <Button
              danger
              disabled={
                action.busy ||
                loading ||
                round.mode !== 'HUMAN_POOL' ||
                round.status !== 'OPEN'
              }
              onClick={() => change(true)}
            >
              取消未完成轮次
            </Button>
          </Space>
        </>
      )}
      <Input.TextArea
        aria-label="轮次或撤销领取原因"
        placeholder="取消轮次或撤销领取的原因（必填）"
        maxLength={1000}
        value={reason}
        disabled={action.busy}
        onChange={(e) => setReason(e.target.value)}
        style={{ marginTop: 16 }}
      />
      <h3>撤销指定领取</h3>
      <p>
        填写已知领取编号和服务器返回的当前版本；版本不符时重新查询，不会自动覆盖。
      </p>
      <Space wrap>
        <Input
          aria-label="需求方撤销领取编号"
          placeholder="assignmentId"
          value={assignment}
          disabled={action.busy}
          onChange={(e) => setAssignment(e.target.value)}
        />
        <InputNumber
          aria-label="待撤销领取的当前版本"
          placeholder="当前 revision"
          min={1}
          precision={0}
          value={revision}
          disabled={action.busy}
          onChange={setRevision}
        />
        <Button
          danger
          disabled={
            action.busy ||
            revision === null ||
            !assignment.trim() ||
            !reason.trim()
          }
          onClick={revoke}
        >
          撤销此领取
        </Button>
      </Space>
    </Card>
  );
}
