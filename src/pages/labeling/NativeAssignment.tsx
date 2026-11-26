import {
  LeftOutlined,
  RightOutlined,
  UnorderedListOutlined,
} from '@ant-design/icons';
import { history, Link, useParams } from '@umijs/max';
import {
  Alert,
  Button,
  Checkbox,
  Drawer,
  Empty,
  Input,
  Modal,
  Select,
  Space,
  Spin,
  Tag,
  Typography,
} from 'antd';
import { useEffect, useRef, useState } from 'react';
import {
  describeLabelingError,
  labelingJson,
  operationKey,
  post,
} from '@/services/labeling';
import { formatDisplayDateTime } from '@/utils/formatDateTime';
import styles from './annotationWorkspace.less';
import LegacyAssignment from './assignment';
import NativeEditor from './NativeEditor';
import WorkspaceLayout from './WorkspaceLayout';

export default function NativeAssignment() {
  const { assignmentId = '' } = useParams<{ assignmentId: string }>();
  const [work, setWork] = useState<any>(),
    [sampleId, setSampleId] = useState(''),
    [error, setError] = useState(''),
    [notice, setNotice] = useState('');
  const [reload, setReload] = useState(0),
    [busy, setBusy] = useState(false),
    [useReference, setUseReference] = useState(true),
    [showReference, setShowReference] = useState(false);
  const [listOpen, setListOpen] = useState(false),
    [instructionsOpen, setInstructionsOpen] = useState(false),
    [search, setSearch] = useState('');
  const guard = useRef<() => boolean>(() => true),
    saving = useRef(false),
    intentional = useRef(false),
    releaseText = useRef('');
  const native = work?.specification?.schemaVersion === 'label-studio-v1';
  useEffect(() => {
    const abort = new AbortController();
    setWork(undefined);
    setError('');
    setShowReference(false);
    void (async () => {
      const first = await labelingJson<any>(
        `/assignments/${assignmentId}/items?limit=100`,
        { signal: abort.signal },
      );
      let cursor = first.nextCursor;
      const rows = [...first.items];
      while (cursor) {
        const next = await labelingJson<any>(
          `/assignments/${assignmentId}/items?limit=100&cursor=${encodeURIComponent(cursor)}`,
          { signal: abort.signal },
        );
        rows.push(...next.items);
        cursor = next.nextCursor;
      }
      if (!abort.signal.aborted) {
        setWork({ ...first, items: rows });
        setSampleId((previous) =>
          rows.some((row) => row.sampleId === previous)
            ? previous
            : rows[0]?.sampleId || '',
        );
      }
    })().catch((e) => {
      if (!abort.signal.aborted) setError(describeLabelingError(e));
    });
    return () => abort.abort();
  }, [assignmentId, reload]);
  useEffect(() => {
    if (work && !native) return;
    const unblock = history.block((transition) => {
      if (intentional.current || (!saving.current && guard.current())) {
        unblock();
        transition.retry();
      }
    });
    return unblock;
  }, [assignmentId, native, !!work]);
  if (work && !native) return <LegacyAssignment />;
  const index =
    work?.items.findIndex((item: any) => item.sampleId === sampleId) ?? -1;
  const row = work?.items[index];
  const readOnly = work?.readOnly || work?.status === 'SUBMITTED';
  const category =
    work?.specification?.taskType === 'TIME_SERIES'
      ? 'Time Series Analysis'
      : 'Computer Vision';
  const envelope =
    row?.result ||
    (useReference && row?.referenceResult?.compatible !== false
      ? row?.referenceResult?.result
      : undefined);
  const completed =
    work?.items.filter((item: any) => item.explicitDone).length || 0;
  const displayName = (item: any) =>
    item.displayName ||
    (item.sourcePath?.startsWith('mapped-')
      ? `样本 ${work.items.indexOf(item) + 1}`
      : item.sourcePath) ||
    `样本 ${String(item.sampleId || '').slice(0, 8)}`;
  const matchingItems = (work?.items || []).filter((item: any) =>
    displayName(item)
      .toLocaleLowerCase()
      .includes(search.trim().toLocaleLowerCase()),
  );
  const changeSample = (id: string) => {
    if (id === sampleId) {
      setListOpen(false);
      return;
    }
    if (!saving.current && guard.current()) {
      setSampleId(id);
      setUseReference(true);
      setShowReference(false);
      setNotice('');
      setError('');
      setListOpen(false);
    }
  };
  const perform = async (action: () => Promise<void>) => {
    if (saving.current) return;
    saving.current = true;
    setBusy(true);
    setError('');
    try {
      await action();
    } catch (e) {
      setError(describeLabelingError(e));
    } finally {
      saving.current = false;
      setBusy(false);
    }
  };
  const submit = () =>
    void perform(async () => {
      if (!guard.current()) return;
      const items = work.items.map((item: any) => ({
        sampleId: item.sampleId,
        revision: item.draftRevision,
      }));
      const storageKey = `native-submit-key:${assignmentId}:${JSON.stringify(items)}`;
      const key = sessionStorage.getItem(storageKey) || operationKey();
      sessionStorage.setItem(storageKey, key);
      const submitted = await post<any>(
        `/assignments/${assignmentId}/submissions`,
        { items },
        key,
      );
      setWork((current: any) => ({
        ...current,
        status: 'SUBMITTED',
        readOnly: true,
      }));
      intentional.current = true;
      history.push(`/labeling/submissions/${submitted.submissionId}`);
      intentional.current = false;
    });
  const release = () => {
    if (!guard.current()) return;
    releaseText.current = '';
    Modal.confirm({
      title: '释放任务包？',
      content: (
        <Input.TextArea
          placeholder="释放原因"
          onChange={(e) => {
            releaseText.current = e.target.value;
          }}
        />
      ),
      onOk: () =>
        perform(async () => {
          await post(
            `/assignments/${assignmentId}/release`,
            { baseRevision: work.revision, reason: releaseText.current },
            operationKey(),
          );
          intentional.current = true;
          history.push('/labeling/assignments');
          intentional.current = false;
        }),
    });
  };
  const sampleNavigation = (
    <div className={styles.sampleNavigation}>
      <Button
        icon={<LeftOutlined />}
        aria-label="上一条样本"
        disabled={busy || index <= 0}
        onClick={() => changeSample(work.items[index - 1].sampleId)}
      />
      <Select
        aria-label="当前标注样本"
        className={styles.sampleSelect}
        showSearch
        optionFilterProp="label"
        value={sampleId || undefined}
        disabled={!work || busy}
        onChange={changeSample}
        options={
          work?.items.map((item: any) => ({
            value: item.sampleId,
            label: displayName(item),
          })) || []
        }
      />
      <Button
        icon={<RightOutlined />}
        aria-label="下一条样本"
        disabled={busy || !work || index >= work.items.length - 1}
        onClick={() => changeSample(work.items[index + 1].sampleId)}
      />
      {work && (
        <span className={styles.position}>
          {index + 1} / {work.items.length}
        </span>
      )}
    </div>
  );
  return (
    <WorkspaceLayout
      workbench
      focusMode
      project={
        work ? { projectId: work.projectId, name: work.projectName } : undefined
      }
      beforeAccountAction={() => !saving.current && guard.current()}
    >
      <div className={styles.workspace}>
        <div className={styles.toolbar}>
          <div className={styles.toolbarGroup}>
            <Link className={styles.backLink} to="/labeling/assignments">
              返回我的任务
            </Link>
            <Button
              icon={<UnorderedListOutlined />}
              aria-label="任务包样本"
              disabled={!work || busy}
              onClick={() => setListOpen(true)}
            >
              任务包样本
            </Button>
          </div>
          <div className={styles.toolbarGroup}>
            {work && (
              <Tag>
                {completed} / {work.items.length} 个样本已完成
              </Tag>
            )}
            {row?.savedAt && (
              <Typography.Text type="secondary">
                最近保存：{formatDisplayDateTime(row.savedAt)}（
                {row.explicitDone ? '已完成' : '草稿'}）
              </Typography.Text>
            )}
            {readOnly && <Tag>本包已提交，结果只读。</Tag>}
            <Button
              disabled={!work || busy}
              onClick={() => setInstructionsOpen(true)}
            >
              规范与说明
            </Button>
            <Button
              disabled={busy}
              onClick={() => {
                if (guard.current()) setReload((value) => value + 1);
              }}
            >
              重新读取任务
            </Button>
            {work && !readOnly && (
              <Button danger disabled={busy} onClick={release}>
                释放任务包
              </Button>
            )}
          </div>
        </div>
        {error && (
          <Alert
            className={styles.message}
            type="error"
            showIcon
            message={error}
          />
        )}
        {notice && (
          <Alert
            className={styles.message}
            type="success"
            showIcon
            message={notice}
          />
        )}
        {!work ? (
          !error && (
            <Spin tip="正在读取任务">
              <div style={{ height: 150 }} />
            </Spin>
          )
        ) : (
          <>
            {row?.referenceResult && (
              <Space wrap style={{ marginBottom: 12 }}>
                {row.referenceResult.compatible === false ? (
                  <Typography.Text type="secondary">
                    上一轮规范不同，参考结果只读。
                  </Typography.Text>
                ) : (
                  !row.result && (
                    <Checkbox
                      checked={useReference}
                      onChange={(event) => {
                        if (guard.current())
                          setUseReference(event.target.checked);
                      }}
                    >
                      载入上一轮参考结果（仍需完成并提交）
                    </Checkbox>
                  )
                )}
                <Button
                  onClick={() => {
                    if (guard.current()) setShowReference((value) => !value);
                  }}
                >
                  {showReference ? '返回当前标注' : '查看上一轮结果'}
                </Button>
                {showReference && <Tag>上一轮结果（只读）</Tag>}
              </Space>
            )}
            {row &&
              (showReference ? (
                <NativeEditor
                  key="reference"
                  compact
                  navigation={sampleNavigation}
                  path={`/assignments/${assignmentId}/samples/${sampleId}/reference-task`}
                  sampleId={sampleId}
                  category={category}
                  result={row.referenceResult?.result?.result || []}
                />
              ) : (
                <NativeEditor
                  key="current"
                  refreshKey={`${reload}:${useReference}`}
                  compact
                  navigation={sampleNavigation}
                  path={`/assignments/${assignmentId}/samples/${sampleId}/task`}
                  sampleId={sampleId}
                  instructions={[
                    work.specification.instructions,
                    work.roundInstructions &&
                      `本轮说明：\n${work.roundInstructions}`,
                  ]
                    .filter(Boolean)
                    .join('\n\n')}
                  category={category}
                  result={envelope?.result || []}
                  emptyReason={envelope?.emptyReason}
                  onGuard={(fn) => {
                    guard.current = fn;
                  }}
                  footerActions={
                    !readOnly && (
                      <Button
                        disabled={
                          busy ||
                          !work.items.length ||
                          completed !== work.items.length
                        }
                        loading={busy}
                        onClick={submit}
                      >
                        提交整个任务包
                      </Button>
                    )
                  }
                  onSave={
                    readOnly
                      ? undefined
                      : async (values, done, emptyReason) => {
                          const receipt = await labelingJson<any>(
                            `/assignments/${assignmentId}/drafts/${sampleId}`,
                            {
                              method: 'PUT',
                              headers: { 'Content-Type': 'application/json' },
                              body: JSON.stringify({
                                baseRevision: row.draftRevision || 0,
                                explicitDone: done,
                                resultFormat: 'label-studio-v1',
                                result: {
                                  result: values,
                                  ...(emptyReason ? { emptyReason } : {}),
                                },
                              }),
                            },
                          );
                          setWork((current: any) => ({
                            ...current,
                            status: 'IN_PROGRESS',
                            revision: receipt.assignmentRevision,
                            items: current.items.map((item: any) =>
                              item.sampleId === sampleId
                                ? {
                                    ...item,
                                    draftRevision: receipt.revision,
                                    savedAt: receipt.savedAt,
                                    explicitDone: receipt.explicitDone,
                                    result: receipt.result,
                                    resultFormat: receipt.resultFormat,
                                  }
                                : item,
                            ),
                          }));
                          const next =
                            done &&
                            receipt.explicitDone &&
                            work.items[index + 1];
                          if (next) {
                            setSampleId(next.sampleId);
                            setUseReference(true);
                            setShowReference(false);
                          }
                          setNotice(
                            next
                              ? '样本已完成并保存，已进入下一张。'
                              : done
                                ? '当前样本已完成并保存。'
                                : '草稿已保存。',
                          );
                        }
                  }
                />
              ))}
            {!row && <Alert type="info" message="当前任务包没有样本。" />}
          </>
        )}
        <Drawer
          title="任务包样本"
          placement="left"
          width={360}
          open={listOpen}
          onClose={() => setListOpen(false)}
        >
          <Input.Search
            aria-label="搜索任务包样本"
            placeholder="搜索样本"
            allowClear
            onChange={(event) => setSearch(event.target.value)}
          />
          {matchingItems.length ? (
            <ul className={styles.sampleList}>
              {matchingItems.map((item: any) => (
                <li key={item.sampleId}>
                  <button
                    className={styles.sampleButton}
                    type="button"
                    disabled={busy}
                    aria-current={
                      item.sampleId === sampleId ? 'true' : undefined
                    }
                    onClick={() => changeSample(item.sampleId)}
                  >
                    <span title={displayName(item)}>{displayName(item)}</span>
                    <Tag>
                      {item.explicitDone
                        ? '已完成'
                        : item.draftRevision
                          ? '草稿'
                          : '待标注'}
                    </Tag>
                  </button>
                </li>
              ))}
            </ul>
          ) : (
            <Empty
              image={Empty.PRESENTED_IMAGE_SIMPLE}
              description={
                search.trim() ? '没有找到匹配的样本' : '任务包暂无样本'
              }
            />
          )}
        </Drawer>
        <Drawer
          title="规范与说明"
          width={480}
          open={instructionsOpen}
          onClose={() => setInstructionsOpen(false)}
        >
          <Typography.Title level={5}>{work?.projectName}</Typography.Title>
          <Typography.Paragraph type="secondary">
            任务包按领取时的规范执行。
          </Typography.Paragraph>
          <Typography.Title level={5}>标注规范</Typography.Title>
          <Typography.Paragraph className={styles.instructions}>
            {work?.specification.instructions || '未填写标注说明。'}
          </Typography.Paragraph>
          {!!work?.roundInstructions && (
            <>
              <Typography.Title level={5}>本轮说明</Typography.Title>
              <Typography.Paragraph className={styles.instructions}>
                {work.roundInstructions}
              </Typography.Paragraph>
            </>
          )}
        </Drawer>
      </div>
    </WorkspaceLayout>
  );
}
