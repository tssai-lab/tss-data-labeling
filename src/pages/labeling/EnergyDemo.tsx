import { history, Link } from '@umijs/max';
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Input,
  InputNumber,
  Progress,
  Select,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd';
import { strToU8, zipSync } from 'fflate';
import { useEffect, useRef, useState } from 'react';
import type { EnergyDraft } from '@/services/labelingEnergy.mjs';
import {
  createEnergyDemo,
  type EnergyDemoState,
  type EnergyWorkspace,
} from '@/services/labelingEnergyDemo.mjs';
import {
  cohenKappa,
  fixedQualityPlan,
  type QualityPlan,
} from '@/services/labelingQuality.mjs';
import styles from './index.less';
import { EnergyTaskEditor } from './TaskEditorHost';

const enabled =
  process.env.NODE_ENV !== 'production' &&
  process.env.LABELING_GAP_MOCK === 'true';
const storageKey = 'labeling:synthetic-energy-trial-v1';
const pairs = ['normal', 'spike', 'normal', 'idle_zero'].map((label, i) => ({
  sampleId: `synthetic-pair-${i}`,
  specVersionId: 'synthetic-energy-spec-v1',
  left: { actorId: 'synthetic-human-a', labelId: label, sourceKind: 'HUMAN' },
  right: {
    actorId: 'synthetic-human-b',
    labelId: i === 2 ? 'spike' : label,
    sourceKind: 'HUMAN',
  },
}));
export default function EnergyDemo() {
  const [workspace, setWorkspace] = useState<EnergyWorkspace>();
  const [state, setState] = useState<EnergyDemoState>();
  const [selected, setSelected] = useState('synthetic-energy-1');
  const [mode, setMode] = useState('human');
  const [editorState, setEditorState] = useState({
    dirty: false,
    saving: false,
    valid: false,
  });
  const editorStateRef = useRef(editorState);
  editorStateRef.current = editorState;
  const [nonce, setNonce] = useState(0);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [reason, setReason] = useState('');
  const [seed, setSeed] = useState('synthetic-qc-1');
  const [goldCount, setGoldCount] = useState(2);
  const [quality, setQuality] = useState<QualityPlan>();
  const [init, setInit] = useState(0);
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    if (!enabled) return;
    let mounted = true;
    let serialized: string | undefined;
    try {
      serialized = sessionStorage.getItem(storageKey) || undefined;
    } catch {
      /* Memory-only mode is still useful. */
    }
    void createEnergyDemo({ enabled: true, production: false, serialized })
      .then((next) => {
        if (mounted) {
          setWorkspace(next);
          setState(next.snapshot());
          setError('');
        }
      })
      .catch((e) => {
        if (mounted) setError(String(e));
      });
    return () => {
      mounted = false;
    };
  }, [init]);
  useEffect(() => {
    if (!editorState.dirty && !editorState.saving && !busy) return;
    const warn = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = '';
    };
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [editorState.dirty, editorState.saving, busy]);
  function canLeave() {
    return (
      !editorStateRef.current.saving &&
      !busy &&
      (!editorStateRef.current.dirty ||
        window.confirm('当前试标尚未保存，确认放弃？'))
    );
  }
  const leaveRef = useRef(canLeave);
  leaveRef.current = canLeave;
  useEffect(() => {
    if (!enabled || (!editorState.dirty && !editorState.saving && !busy))
      return;
    const unblock = history.block((tx) => {
      if (leaveRef.current()) {
        unblock();
        tx.retry();
      }
    });
    return unblock;
  }, [editorState.dirty, editorState.saving, busy]);
  function refresh() {
    if (!workspace) return;
    setState(workspace.snapshot());
    try {
      sessionStorage.setItem(storageKey, workspace.serialize());
    } catch {
      setNotice('当前仅保存在内存；浏览器存储不可用，刷新后不能恢复。');
    }
  }
  async function act(run: () => void | Promise<void>) {
    if (busy || editorStateRef.current.saving) return;
    setBusy(true);
    setError('');
    try {
      await run();
      refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : '操作失败');
    } finally {
      setBusy(false);
    }
  }
  const input = workspace?.inputs.find((i) => i.sampleId === selected);
  const candidate = state?.results.find(
    (r) => r.resultId === state.current[selected],
  );
  const ownerDraft: EnergyDraft | undefined =
    candidate && state
      ? {
          revision: state.reviewRevisions[selected],
          explicitDone: true,
          resultFormat: 'annotation-record-v1',
          result: candidate.result,
        }
      : undefined;
  const draft =
    workspace && (mode === 'owner' ? ownerDraft : workspace.draft(selected));
  const completed = state
    ? Object.values(state.drafts).filter((d) => d.explicitDone).length
    : 0;
  return (
    <main className={styles.page}>
      <section className={styles.content}>
        <header className={styles.workspaceHeader}>
          <div className={styles.brand}>
            TSS <span>/</span> <small>ENERGY TRIAL</small>
          </div>
        </header>
        <nav className={styles.navigation}>
          <Space wrap>
            <Link to="/labeling/demo">图片分类合成演示</Link>
            <Link to="/labeling">真实标注平台</Link>
          </Space>
        </nav>
        <h1>站点日能耗 · 合成试标与质检预览</h1>
        {!enabled ? (
          <Alert message="开发演示默认关闭，生产构建不可开启。待后端接入。" />
        ) : (
          <>
            <Alert
              type="warning"
              showIcon
              message="仅合成数据与试标规范，不是受限能耗数据或正式验收"
              description="模板、导入、授权切片、真实规则Agent及质检API仍待接入；标签与质量目标待专家确认。此页不请求后端、共享CSV或模型，不产生真实金标准。区间是本平台试标格式，不宣称LS原生兼容。"
            />
            {error && <Alert type="error" message={error} />}
            {notice && <Alert type="info" message={notice} />}
            <Space wrap style={{ margin: '16px 0' }}>
              <Select
                aria-label="合成模板"
                value="energy-station-day@1.0.0"
                options={[
                  {
                    value: 'energy-station-day@1.0.0',
                    label: 'energy-station-day@1.0.0 · 试标',
                  },
                ]}
                style={{ width: 320, maxWidth: '100%' }}
              />
              <Button
                disabled={busy || editorState.saving}
                onClick={() => {
                  if (
                    !canLeave() ||
                    !window.confirm('重置仅清除本页合成试标缓存，是否继续？')
                  )
                    return;
                  sessionStorage.removeItem(storageKey);
                  setWorkspace(undefined);
                  setState(undefined);
                  setQuality(undefined);
                  setEditorState({ dirty: false, saving: false, valid: false });
                  setSelected('synthetic-energy-1');
                  setMode('human');
                  setInit((v) => v + 1);
                  setNonce((v) => v + 1);
                }}
              >
                重置能耗合成数据
              </Button>
            </Space>
            {workspace && state && (
              <>
                <Card title="当前站点日包 · 10个样本（不是240个小时任务）">
                  <Progress percent={completed * 10} />
                  <Space wrap>
                    <Tag>{completed}/10 草稿完成</Tag>
                    <Tag>
                      {state.submitted
                        ? '人工包已交回，仍待需求方确认'
                        : '人工试标中'}
                    </Tag>
                    <Select
                      aria-label="能耗合成视角"
                      value={mode}
                      disabled={busy || editorState.saving}
                      options={[
                        { value: 'human', label: '人工领取视角（合成）' },
                        { value: 'owner', label: '需求方视角（合成）' },
                      ]}
                      onChange={(next) => {
                        if (canLeave()) {
                          setMode(next);
                          setNonce((v) => v + 1);
                        }
                      }}
                    />
                    <Button
                      type="primary"
                      disabled={
                        state.submitted ||
                        completed !== 10 ||
                        editorState.dirty ||
                        editorState.saving ||
                        busy
                      }
                      onClick={() => {
                        if (
                          window.confirm(
                            '确认交回当前10项完整包？这不会自动审核。',
                          )
                        )
                          void act(() => {
                            workspace.submit(
                              workspace.inputs.map((i) => ({
                                sampleId: i.sampleId,
                                revision: workspace.draft(i.sampleId).revision,
                              })),
                            );
                            setNotice('本包已独立交回，需求方仍需明确确认。');
                          });
                      }}
                    >
                      交回当前能耗包
                    </Button>
                    <Button
                      disabled={busy || editorState.saving}
                      onClick={() => {
                        if (canLeave()) {
                          setNonce((v) => v + 1);
                          setEditorState({
                            dirty: false,
                            saving: false,
                            valid: false,
                          });
                        }
                      }}
                    >
                      重新读取能耗草稿
                    </Button>
                  </Space>
                </Card>
                <Card title="站点日任务列表">
                  <Table
                    scroll={{ x: 650 }}
                    rowKey="sampleId"
                    pagination={{ pageSize: 10 }}
                    dataSource={workspace.inputs}
                    columns={[
                      { title: '样本', dataIndex: 'sampleId' },
                      { title: '站点', render: (_, i) => i.payload.stationKey },
                      {
                        title: '日期 / 时区',
                        render: (_, i) => `${i.payload.dateLocal} / UTC+8`,
                      },
                      {
                        title: '状态',
                        render: (_, i) =>
                          state.accepted[i.sampleId] ===
                            state.current[i.sampleId] &&
                          state.current[i.sampleId]
                            ? '需求方已确认'
                            : workspace.draft(i.sampleId).explicitDone
                              ? '已保存完成草稿'
                              : '未完成',
                      },
                      {
                        title: '操作',
                        render: (_, i) => (
                          <Button
                            disabled={busy || editorState.saving}
                            onClick={() => {
                              if (canLeave()) {
                                setSelected(i.sampleId);
                                setNonce((v) => v + 1);
                              }
                            }}
                          >
                            打开 {i.sampleId.split('-').at(-1)}
                          </Button>
                        ),
                      },
                    ]}
                  />
                </Card>
                {input && draft ? (
                  <EnergyTaskEditor
                    key={`${mode}:${selected}:${input.inputHash}:${nonce}`}
                    input={input}
                    draft={draft}
                    readOnly={busy || (mode === 'human' && state.submitted)}
                    allowIncomplete={mode === 'human'}
                    onStateChange={setEditorState}
                    onSave={async (body) => {
                      const identity = input.sampleId;
                      await new Promise((resolve) => setTimeout(resolve, 80));
                      if (mode === 'owner') {
                        const r = workspace.revise(
                          identity,
                          body.baseRevision,
                          body.result,
                          reason,
                        );
                        refresh();
                        setNotice(
                          `已生成需求方新修订 ${r.resultId}，旧答案保留，仍待明确确认。`,
                        );
                        return {
                          revision:
                            workspace.snapshot().reviewRevisions[identity],
                          explicitDone: true,
                          resultFormat: 'annotation-record-v1',
                          result: r.result,
                        };
                      }
                      workspace.save(identity, input.inputHash, body);
                      const saved = workspace.draft(identity);
                      refresh();
                      setNotice(
                        `已保存并读回 ${identity} · revision ${saved.revision}；尚未交包或审核。`,
                      );
                      return saved;
                    }}
                  />
                ) : (
                  <Alert
                    type="info"
                    message="尚无已交回候选，需求方不能借用人工草稿接口修订。"
                  />
                )}
                {mode === 'owner' && (
                  <Card title="需求方确认与固定成果">
                    <Input
                      aria-label="能耗需求方修订原因"
                      placeholder="修订原因（修订不会自动确认）"
                      value={reason}
                      onChange={(event) => setReason(event.target.value)}
                      maxLength={500}
                    />
                    <Space wrap style={{ marginTop: 12 }}>
                      <Button
                        disabled={
                          !state.submitted ||
                          editorState.dirty ||
                          editorState.saving ||
                          busy
                        }
                        onClick={() => {
                          const items = workspace.inputs.map((i) => ({
                            sampleId: i.sampleId,
                            resultId: state.current[i.sampleId],
                            reviewRevision: state.reviewRevisions[i.sampleId],
                          }));
                          if (
                            window.confirm(
                              '已固定当前10份候选及审核版本，明确确认采用？',
                            )
                          )
                            void act(() => {
                              workspace.review(items);
                              setNonce((v) => v + 1);
                              setNotice(
                                '当前范围已明确确认；尚未形成最终成果。',
                              );
                            });
                        }}
                      >
                        明确确认当前10份能耗结果
                      </Button>
                      <Button
                        disabled={
                          busy || editorState.dirty || editorState.saving
                        }
                        onClick={() =>
                          void act(() => {
                            const final = workspace.finalize();
                            setNotice(
                              `已固定成果 ${final.finalSnapshotId}，后续修订不改变旧快照。`,
                            );
                          })
                        }
                      >
                        固定已确认能耗成果
                      </Button>
                      {state.finals.map((final) => (
                        <Button
                          key={final.finalSnapshotId}
                          disabled={busy}
                          onClick={() =>
                            void act(async () => {
                              const files = await workspace.export(
                                final.finalSnapshotId,
                              );
                              const bytes = zipSync(
                                Object.fromEntries(
                                  Object.entries(files).map(
                                    ([name, content]) => [
                                      name,
                                      strToU8(content),
                                    ],
                                  ),
                                ),
                              );
                              const url = URL.createObjectURL(
                                new Blob([new Uint8Array(bytes)], {
                                  type: 'application/zip',
                                }),
                              );
                              const a = document.createElement('a');
                              a.href = url;
                              a.download = `synthetic-energy-${final.finalSnapshotId}.zip`;
                              a.click();
                              setTimeout(() => URL.revokeObjectURL(url), 1000);
                            })
                          }
                        >
                          下载 {final.finalSnapshotId}
                        </Button>
                      ))}
                    </Space>
                    <Typography.Paragraph>
                      JSONL保留完整点/区间与来源，辅助CSV保留结构化明细；人工自评与模型confidence分列。下载不含原始数据资产。
                    </Typography.Paragraph>
                  </Card>
                )}
                {mode === 'owner' && (
                  <Card title="通用质检 · 固定抽样与主标签一致性（合成预览）">
                    <Alert
                      type="info"
                      message="指南建议2000条中500金标准、其余至少300抽检、至少200双标；尚非正式验收指标。此处只有10条合成母体，计划不等于已执行。"
                    />
                    <Space wrap style={{ marginTop: 12 }}>
                      <Input
                        aria-label="质检抽样种子"
                        value={seed}
                        onChange={(e) => setSeed(e.target.value)}
                        style={{ width: 190 }}
                      />
                      <span>拟建金标准数量</span>
                      <InputNumber
                        aria-label="拟建金标准数量"
                        min={0}
                        max={10}
                        value={goldCount}
                        onChange={(v) => v !== null && setGoldCount(v)}
                      />
                      <Button
                        onClick={() =>
                          void act(() => {
                            setQuality(
                              fixedQualityPlan(
                                workspace.inputs.map((i) => i.sampleId),
                                {
                                  seed,
                                  goldCount,
                                  inspectionRate: 0.2,
                                  doubleRate: 0.1,
                                },
                              ),
                            );
                          })
                        }
                      >
                        固定质检预览清单
                      </Button>
                      <Button disabled>发布双人盲标包 · 待质检权限API</Button>
                    </Space>
                    {quality && (
                      <Descriptions
                        bordered
                        column={1}
                        items={[
                          {
                            key: 'population',
                            label: '固定母体 / 种子',
                            children: `${quality.population.length}条 / ${quality.seed}`,
                          },
                          {
                            key: 'gold',
                            label: '拟建金标准（专家未确认）',
                            children: quality.gold.join(', ') || '无',
                          },
                          {
                            key: 'inspection',
                            label: '非金标准范围20%抽检清单',
                            children: quality.inspection.join(', ') || '无',
                          },
                          {
                            key: 'double',
                            label: '全部母体10%双标清单',
                            children: quality.double.join(', ') || '无',
                          },
                          {
                            key: 'coverage',
                            label: '真实执行覆盖',
                            children:
                              '双标0，已确认金标准0；不向盲标人员展示答案',
                          },
                        ]}
                      />
                    )}
                    <Typography.Paragraph>
                      以下仅用4组独立合成主标签配对演示统计，不比较点/区间，不报告模型准确率。0.70仅为指南建议。
                    </Typography.Paragraph>
                    <Descriptions
                      bordered
                      column={1}
                      items={Object.entries(cohenKappa(pairs)).map(
                        ([label, value]) => ({
                          key: label,
                          label,
                          children:
                            value === null
                              ? '不可计算 / 不适用'
                              : String(value),
                        }),
                      )}
                    />
                  </Card>
                )}
              </>
            )}
          </>
        )}
      </section>
    </main>
  );
}
