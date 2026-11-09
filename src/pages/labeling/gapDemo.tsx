import { Link } from '@umijs/max';
import {
  Alert,
  Button,
  Card,
  Input,
  Select,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd';
import { strToU8, zipSync } from 'fflate';
import { useState } from 'react';
import { operationKey } from '@/services/labeling';
import { createGapMock } from '@/services/labelingGapMock.mjs';
import styles from './index.less';

const enabled =
  process.env.NODE_ENV !== 'production' &&
  process.env.LABELING_GAP_MOCK === 'true';
const demo = createGapMock({
  enabled,
  production: process.env.NODE_ENV === 'production',
});
export function CapabilitiesNotice() {
  return (
    <Card title="后续服务能力">
      <Alert
        type="info"
        showIcon
        message="待后端接入：结果导出、项目暂停/恢复/归档、领取放弃/回收、审计及完整批次历史"
        description="真实模式仅显示当前已知批次与真实结果，不推测全项目进度。上述操作暂不可用。"
      />
      <Space wrap style={{ marginTop: 16 }}>
        {['导出结果', '暂停项目', '归档项目', '全部批次历史'].map((name) => (
          <Button key={name} disabled>
            {name} · 待后端接入
          </Button>
        ))}
        {enabled && <Link to="/labeling/demo">打开独立合成数据演示</Link>}
      </Space>
    </Card>
  );
}
export default function GapDemo() {
  const [state, setState] = useState(() => (enabled ? demo.snapshot() : null));
  const [actor, setActor] = useState('demo-owner');
  const [reason, setReason] = useState('合成数据验收操作');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [scope, setScope] = useState('ALL_SUBMITTED');
  const [selection, setSelection] = useState<React.Key[]>([]);
  const [query, setQuery] = useState('');
  const [management, setManagement] = useState<any[]>([]);
  const [audits, setAudits] = useState<any[]>([]);
  function call(method: string, path: string, body = {}) {
    setError('');
    setNotice('');
    try {
      const response = demo.request(method, `/api/labeling/v1${path}`, {
        actor,
        body,
        key: method === 'POST' ? operationKey() : undefined,
      });
      setState(demo.snapshot());
      setNotice(`演示请求 ${response.requestId}`);
      return response.data;
    } catch (e: any) {
      setError(`${e.message}（${e.status} ${e.code}） · ${e.requestId}`);
    }
  }
  function download(id: string) {
    const files = call('GET', `/exports/${id}/download`);
    if (!files) return;
    const bytes = zipSync(
      Object.fromEntries(
        Object.entries(files).map(([name, text]) => [
          name,
          strToU8(String(text)),
        ]),
      ),
    );
    const url = URL.createObjectURL(
      new Blob([new Uint8Array(bytes)], { type: 'application/zip' }),
    );
    const a = document.createElement('a');
    a.href = url;
    a.download = `${id}.zip`;
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
  return (
    <main className={styles.page}>
      <section className={styles.content}>
        <header className={styles.workspaceHeader}>
          <div className={styles.brand}>
            TSS <span>/</span> <small>LOCAL SYNTHETIC DEMO</small>
          </div>
        </header>
        <nav className={styles.navigation} aria-label="合成演示导航">
          <Link className={styles.navTab} to="/labeling">
            返回真实标注平台
          </Link>
          <Link className={styles.navTab} to="/platforms">
            切换平台
          </Link>
        </nav>
        <h1>缺失服务能力 · 本地合成演示</h1>
        <Alert
          type="warning"
          showIcon
          message="全部为独立合成数据，不连接真实项目、身份、领取或权限"
          description="种子：12 张合成元数据、10+2 分包、N=2；首包两份独立提交（正常/无目标），末包未完成。页面内切换身份保留数据；重载页面或重置按钮恢复种子。没有模拟登录或用户权限变更。"
        />
        {!enabled || !state ? (
          <Alert message="待后端接入。开发演示默认关闭，生产构建不可开启。" />
        ) : (
          <>
            <Card>
              <Space wrap>
                <Select
                  aria-label="合成演示身份"
                  value={actor}
                  onChange={(value) => {
                    setActor(value);
                    setAudits([]);
                    setManagement([]);
                    setError('');
                  }}
                  options={[
                    'demo-owner',
                    'demo-alice',
                    'demo-bob',
                    'demo-admin',
                    'demo-outsider',
                  ].map((value) => ({ value, label: value }))}
                />
                <Button
                  onClick={() => {
                    demo.reset();
                    setState(demo.snapshot());
                    setAudits([]);
                    setManagement([]);
                    setSelection([]);
                    setError('');
                  }}
                >
                  重置种子
                </Button>
                <Tag>仅内存，不持久化令牌或业务数据</Tag>
              </Space>
            </Card>
            {error && <Alert type="error" showIcon message={error} />}
            {notice && <Alert type="info" message={notice} />}
            <Card title="导出已提交结果">
              <Typography.Paragraph>
                两人的结果独立导出。下载含
                results.json、mapping.csv、manifest.json、labels.json；不含原图。任务创建时冻结提交清单；状态通过“查询状态”推进。
              </Typography.Paragraph>
              <Table
                rowKey="submissionId"
                scroll={{ x: 700 }}
                dataSource={state.submissions}
                pagination={false}
                rowSelection={{
                  selectedRowKeys: selection,
                  onChange: setSelection,
                }}
                columns={[
                  { title: '提交', dataIndex: 'submissionId' },
                  { title: '包', dataIndex: 'packageId' },
                  { title: '标注人', dataIndex: 'submittedBy' },
                  { title: '冻结规范', dataIndex: 'specVersionId' },
                ]}
              />
              <Space wrap style={{ marginTop: 16 }}>
                <Select
                  aria-label="导出范围"
                  value={scope}
                  onChange={setScope}
                  options={[
                    { value: 'ALL_SUBMITTED', label: '全部已提交' },
                    { value: 'SELECTED_SUBMISSIONS', label: '选中提交' },
                  ]}
                />
                <Button
                  type="primary"
                  onClick={() =>
                    call('POST', '/projects/demo-project/exports', {
                      format: 'native-json',
                      scope,
                      includeOriginals: false,
                      submissionIds: selection,
                    })
                  }
                >
                  创建演示导出
                </Button>
              </Space>
              <Table
                rowKey="exportId"
                scroll={{ x: 950 }}
                dataSource={state.exports}
                pagination={false}
                columns={[
                  { title: '导出任务', dataIndex: 'exportId' },
                  { title: '状态', dataIndex: 'status' },
                  {
                    title: '错误',
                    render: (_, row: any) => row.error?.message || '—',
                  },
                  {
                    title: '操作',
                    render: (_, row: any) => (
                      <Space wrap>
                        <Button
                          onClick={() =>
                            call('GET', `/exports/${row.exportId}`)
                          }
                        >
                          查询状态
                        </Button>
                        <Button onClick={() => download(row.exportId)}>
                          下载 ZIP
                        </Button>
                        <Button
                          onClick={() => {
                            demo.setExportFault(row.exportId, { failed: true });
                            setState(demo.snapshot());
                          }}
                        >
                          注入失败
                        </Button>
                        <Button
                          onClick={() => {
                            demo.setExportFault(row.exportId, {
                              expired: true,
                            });
                            setState(demo.snapshot());
                          }}
                        >
                          注入过期
                        </Button>
                      </Space>
                    ),
                  },
                ]}
              />
            </Card>
            <Card title="项目状态与领取管理">
              <Space wrap>
                <Tag>
                  {state.project.status} · revision {state.project.revision}
                </Tag>
                <Input
                  aria-label="操作原因"
                  style={{ width: 240, maxWidth: '100%' }}
                  maxLength={500}
                  value={reason}
                  onChange={(e) => setReason(e.target.value)}
                />
                {['pause', 'resume', 'archive'].map((action) => (
                  <Button
                    key={action}
                    onClick={() => {
                      if (window.confirm(`确认在合成项目执行 ${action}？`))
                        call('POST', `/projects/demo-project/${action}`, {
                          baseRevision: state.project.revision,
                          reason,
                        });
                    }}
                  >
                    {
                      (
                        {
                          pause: '暂停',
                          resume: '恢复',
                          archive: '归档',
                        } as Record<string, string>
                      )[action]
                    }
                  </Button>
                ))}
              </Space>
              <Table
                rowKey="assignmentId"
                scroll={{ x: 900 }}
                dataSource={state.assignments}
                pagination={false}
                columns={[
                  { title: '领取', dataIndex: 'assignmentId' },
                  { title: '标注人', dataIndex: 'actorId' },
                  { title: '状态', dataIndex: 'status' },
                  {
                    title: '操作',
                    render: (_, row: any) => (
                      <Space wrap>
                        <Button
                          onClick={() => {
                            if (window.confirm('确认放弃合成领取？'))
                              call(
                                'POST',
                                `/assignments/${row.assignmentId}/release`,
                                { baseRevision: row.revision, reason },
                              );
                          }}
                        >
                          本人放弃
                        </Button>
                        <Button
                          onClick={() => {
                            if (window.confirm('确认回收合成领取？'))
                              call(
                                'POST',
                                `/admin/assignments/${row.assignmentId}/revoke`,
                                { baseRevision: row.revision, reason },
                              );
                          }}
                        >
                          管理员回收
                        </Button>
                        <Button
                          onClick={() => {
                            try {
                              demo.assertWorkAccess(actor, row.assignmentId);
                              setError('');
                              setNotice('合成工作数据访问允许');
                            } catch (e: any) {
                              setError(`${e.message}（${e.status} ${e.code}）`);
                            }
                          }}
                        >
                          检查工作访问
                        </Button>
                      </Space>
                    ),
                  },
                ]}
              />
              <Typography.Paragraph>
                暂停阻止工作访问；释放/回收终止领取访问。历史合成提交保留在上方结果区。
              </Typography.Paragraph>
            </Card>
            <Card title="完整批次历史 · 合成">
              <Button
                onClick={() => {
                  const data = call('GET', '/projects/demo-project/imports');
                  if (data) setManagement(data.items);
                }}
              >
                读取完整历史（需求方）
              </Button>
              <Table
                rowKey={(_, i) => String(i)}
                scroll={{ x: 650 }}
                dataSource={management}
                columns={[
                  {
                    title: '记录',
                    render: (_, row) => (
                      <Typography.Text style={{ overflowWrap: 'anywhere' }}>
                        {JSON.stringify(row)}
                      </Typography.Text>
                    ),
                  },
                ]}
              />
            </Card>
            <Card title="管理员搜索与操作审计 · 合成">
              <Space wrap>
                <Input
                  aria-label="管理搜索"
                  value={query}
                  onChange={(e) => setQuery(e.target.value)}
                  placeholder="搜索合成项目/领取"
                />
                <Button
                  onClick={() => {
                    const data = call(
                      'GET',
                      `/admin/projects?q=${encodeURIComponent(query)}`,
                    );
                    if (data) setManagement(data.items);
                  }}
                >
                  搜索项目
                </Button>
                <Button
                  onClick={() => {
                    const data = call(
                      'GET',
                      `/admin/assignments?q=${encodeURIComponent(query)}`,
                    );
                    if (data) setManagement(data.items);
                  }}
                >
                  搜索领取
                </Button>
                <Button
                  onClick={() => {
                    const data = call('GET', '/admin/audit-events');
                    if (data) setAudits(data.items);
                  }}
                >
                  读取审计
                </Button>
              </Space>
              <Table
                rowKey="eventId"
                dataSource={audits}
                scroll={{ x: 850 }}
                columns={[
                  { title: '操作人', dataIndex: 'actorId' },
                  { title: '操作', dataIndex: 'operation' },
                  { title: '目标', dataIndex: 'target' },
                  { title: '原因', dataIndex: 'reason' },
                  { title: '时间', dataIndex: 'createdAt' },
                ]}
              />
            </Card>
          </>
        )}
      </section>
    </main>
  );
}
