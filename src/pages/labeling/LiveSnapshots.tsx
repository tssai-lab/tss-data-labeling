import {
  Alert,
  App,
  Button,
  Card,
  Input,
  Space,
  Table,
  Typography,
} from 'antd';
import { useEffect, useState } from 'react';
import {
  describeLabelingError,
  operationKey,
  type SpecVersion,
} from '@/services/labeling';
import {
  type LiveResult,
  type LiveSnapshot,
  ownerApi,
  type SnapshotSummary,
  selectionItem,
} from '@/services/labelingOwner';
import { resultLabel } from '@/services/labelingStructured';
import { canFinalize } from '@/services/labelingWorkspace';
import LiveExport from './LiveExport';
import useOwnerAction from './useOwnerAction';
import ui from './workspaceUi.less';

export default function LiveSnapshots({
  projectId,
  spec,
  selected,
  onBusyChange,
  writeBlocked,
  active = true,
}: {
  active?: boolean;
  projectId: string;
  spec: SpecVersion;
  selected: LiveResult[];
  onBusyChange?: (busy: boolean) => void;
  writeBlocked?: string;
}) {
  const { modal } = App.useApp(),
    action = useOwnerAction(onBusyChange, writeBlocked);
  const [exportIds, setExportIds] = useState<React.Key[]>([]);
  const [name, setName] = useState(''),
    [rows, setRows] = useState<SnapshotSummary[]>([]),
    [cursor, setCursor] = useState<string | null>(null);
  const [snapshot, setSnapshot] = useState<LiveSnapshot>(),
    [known, setKnown] = useState(''),
    [error, setError] = useState(''),
    [loading, setLoading] = useState(false),
    [refresh, setRefresh] = useState(0);
  useEffect(() => {
    const abort = new AbortController();
    setLoading(true);
    setError('');
    void ownerApi
      .snapshots(projectId, null, abort.signal)
      .then((page) => {
        if (!abort.signal.aborted) {
          setRows(page.items);
          setCursor(page.nextCursor);
        }
      })
      .catch((e) => {
        if (!abort.signal.aborted) setError(describeLabelingError(e));
      })
      .finally(() => {
        if (!abort.signal.aborted) setLoading(false);
      });
    return () => abort.abort();
  }, [projectId, refresh]);
  function create() {
    if (!name.trim() || !selected.length) return;
    modal.confirm({
      title: `冻结 ${selected.length} 个已确认样本的成果？`,
      content:
        '只包含当前明确勾选且已审核确认的样本。后续重新发布或审核不会改写本次快照。',
      onOk: async () => {
        const body = {
          name: name.trim(),
          specVersionId: spec.specVersionId,
          sampleIds: selected.map((row) => row.sampleId),
        };
        const result = await action.run(
          'final-snapshot',
          body,
          async (key, prepare) => {
            const selection = await prepare(async () => {
              if (
                selected.some(
                  (row) =>
                    row.reviewStatus !== 'ACCEPTED' ||
                    !row.resultId ||
                    row.resultId !== row.selectedResultId ||
                    row.activeProcessing,
                )
              )
                throw new Error('请选择已确认且绑定当前采用答案的样本');
              return ownerApi.select(
                projectId,
                {
                  specVersionId: spec.specVersionId,
                  purpose: 'FINALIZE',
                  items: selected.map(selectionItem),
                },
                operationKey(),
              );
            });
            return ownerApi.finalizeSelection(
              projectId,
              {
                name: body.name,
                specVersionId: body.specVersionId,
                selectionId: selection.selectionId,
              },
              key,
            );
          },
          '成果快照已冻结保存',
        );
        if (result) {
          setSnapshot(result);
          setName('');
          setRefresh((n) => n + 1);
        }
      },
    });
  }
  async function read(id: string) {
    setLoading(true);
    setError('');
    setSnapshot(undefined);
    try {
      const value = await ownerApi.snapshot(id);
      if (value.projectId !== projectId) throw new Error('快照不属于当前项目');
      setSnapshot(value);
    } catch (e) {
      setError(describeLabelingError(e));
    } finally {
      setLoading(false);
    }
  }
  function download() {
    if (!snapshot) return;
    const url = URL.createObjectURL(
      new Blob([JSON.stringify(snapshot, null, 2)], {
        type: 'application/json',
      }),
    );
    const link = document.createElement('a');
    link.href = url;
    link.download = `final-snapshot-${snapshot.finalSnapshotId}.json`;
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
  return (
    <div className={ui.twoColumns}>
      <Card title="成果快照">
        <Alert
          type="info"
          message="成果快照保存已审核确认的答案，后续修改不会覆盖它"
          description="可以下载服务器已冻结快照的 JSON 原始记录。该文件保留来源与审核版本，不包含原始图片，也不等同于正式成果导出包。"
        />
        {(error || action.error) && (
          <Alert type="error" message={error || action.error} />
        )}
        <div hidden={!selected.length}>
          {!canFinalize(selected) && (
            <p>所选样本须全部确认采用当前答案后，才能保存为成果。</p>
          )}
          <Space wrap style={{ marginTop: 16 }}>
            <Input
              aria-label="成果快照名称"
              placeholder="输入成果快照名称"
              maxLength={120}
              value={name}
              onChange={(e) => setName(e.target.value)}
              disabled={action.busy}
            />
            <Button
              type="primary"
              disabled={
                !name.trim() || !canFinalize(selected) || action.busy || loading
              }
              onClick={create}
            >
              冻结所选 {selected.length} 个样本
            </Button>
            <Button
              disabled={action.busy || loading}
              onClick={() => setRefresh((n) => n + 1)}
            >
              刷新快照列表
            </Button>
          </Space>
        </div>
        <details>
          <summary>按已知成果编号查询</summary>
          <Space wrap style={{ marginTop: 16 }}>
            <Input
              aria-label="已知成果快照编号"
              placeholder="粘贴成果快照编号"
              value={known}
              onChange={(e) => setKnown(e.target.value)}
              disabled={action.busy || loading}
            />
            <Button
              disabled={!known.trim() || action.busy || loading}
              onClick={() => void read(known.trim())}
            >
              读取快照
            </Button>
          </Space>
        </details>
        <Table
          rowSelection={{
            selectedRowKeys: exportIds,
            onChange: setExportIds,
            getCheckboxProps: () => ({ disabled: action.busy || loading }),
          }}
          rowKey="finalSnapshotId"
          dataSource={rows}
          loading={loading}
          pagination={false}
          scroll={{ x: 700 }}
          columns={[
            { title: '名称', dataIndex: 'name' },
            {
              title: '规范',
              render: (_, row) =>
                row.specVersionId === spec.specVersionId
                  ? `当前规范版本 ${spec.versionNo}`
                  : '其他历史规范',
            },
            { title: '创建时间', dataIndex: 'createdAt' },
            {
              title: '操作',
              render: (_, row) => (
                <Button
                  disabled={loading || action.busy}
                  onClick={() => void read(row.finalSnapshotId)}
                >
                  查看冻结成果
                </Button>
              ),
            },
          ]}
        />
        {cursor && (
          <Button
            disabled={loading || action.busy}
            onClick={async () => {
              setLoading(true);
              try {
                const page = await ownerApi.snapshots(projectId, cursor);
                setRows((old) => [...old, ...page.items]);
                setCursor(page.nextCursor);
              } catch (e) {
                setError(describeLabelingError(e));
              } finally {
                setLoading(false);
              }
            }}
          >
            更多快照
          </Button>
        )}
        {snapshot && (
          <>
            <h3>
              {snapshot.name} · {snapshot.sampleCount} 个样本
            </h3>
            <details>
              <summary>查看成果编号与校验信息</summary>
              <Typography.Paragraph copyable>
                {snapshot.finalSnapshotId}
              </Typography.Paragraph>
              <Typography.Paragraph style={{ overflowWrap: 'anywhere' }}>
                范围摘要：{snapshot.scopeHash}
              </Typography.Paragraph>
            </details>
            <Button onClick={download}>下载冻结记录 JSON</Button>
            <Table
              rowKey="sampleId"
              dataSource={snapshot.items}
              pagination={{ pageSize: 20 }}
              scroll={{ x: 700 }}
              columns={[
                { title: '原路径', dataIndex: 'sourcePath' },
                {
                  title: '标签',
                  render: (_, row) => resultLabel(row.result) || '—',
                },
                { title: '来源', dataIndex: 'sourceKind' },
                { title: '审核版本', dataIndex: 'reviewRevision' },
                { title: '文件摘要', dataIndex: 'contentHash', ellipsis: true },
              ]}
            />
          </>
        )}
      </Card>
      <LiveExport
        projectId={projectId}
        snapshotIds={exportIds.map(String)}
        taskType={spec.taskType}
        active={active}
        onBusyChange={onBusyChange}
      />
    </div>
  );
}
