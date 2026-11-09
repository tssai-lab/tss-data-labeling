import { Alert, Button, Space, Spin, Tag } from 'antd';
import {
  forwardRef,
  useEffect,
  useImperativeHandle,
  useRef,
  useState,
} from 'react';
import {
  describeLabelingError,
  LabelingError,
  operationKey,
} from '@/services/labeling';
import { confirmedDraft } from '@/services/labelingClassification.mjs';
import {
  type EditorContext,
  type EditorSession,
  editor,
  editorLaunch,
  validEditorMessage,
} from '@/services/labelingEditor';
import { type Draft, tasks } from '@/services/labelingTasks';
import { STORAGE_KEYS, storage } from '@/utils/storage';

export type EditorHandle = {
  leave: () => Promise<boolean>;
  canSubmit: () => Promise<boolean>;
};
export default forwardRef<
  EditorHandle,
  {
    context: EditorContext;
    onDraft: (draft: Draft) => void;
    onBlocked: (blocked: boolean) => void;
  }
>(function AssignmentEditor({ context, onDraft, onBlocked }, ref) {
  const holder = useRef<HTMLDivElement>(null);
  const live = useRef<
    { session: EditorSession; frame: HTMLIFrameElement } | undefined
  >(undefined);
  const pending = useRef(
    new Map<
      string,
      {
        type: string;
        resolve: (d: Record<string, any>) => void;
        reject: (e: Error) => void;
        timer: ReturnType<typeof setTimeout>;
      }
    >(),
  );
  const generation = useRef(0);
  const uncertain = useRef(true);
  const reading = useRef(false);
  const callbacks = useRef({ onDraft, onBlocked });
  callbacks.current = { onDraft, onBlocked };
  const [retry, setRetry] = useState(0);
  const [error, setError] = useState('');
  const [ready, setReady] = useState(false);
  const [status, setStatus] = useState('正在打开编辑器');
  const [syncing, setSyncing] = useState(false);

  function request(type: string, reply: string) {
    const current = live.current;
    if (!current)
      return Promise.reject(new Error('编辑器未就绪，请重开或恢复核对'));
    const requestId = operationKey();
    return new Promise<Record<string, any>>((resolve, reject) => {
      const timer = setTimeout(() => {
        pending.current.delete(requestId);
        reject(new Error('编辑器状态确认超时，请等待或恢复核对'));
      }, 6000);
      pending.current.set(requestId, { type: reply, resolve, reject, timer });
      current.frame.contentWindow?.postMessage(
        { type, editorSessionId: current.session.editorSessionId, requestId },
        location.origin,
      );
    });
  }
  async function leave() {
    try {
      if (reading.current || syncing)
        throw new Error('正在核对平台草稿，请稍候');
      if (!live.current) return !uncertain.current;
      const state = await request('labeling:request-state', 'labeling:state');
      if (state.saving) throw new Error('编辑器正在保存，请等待完成');
      if (uncertain.current)
        throw new Error('保存状态尚未核对，请先恢复核对或重新读取');
      if (state.dirty) {
        if (!window.confirm('当前编辑器有未保存修改，确认放弃并离开？'))
          return false;
        await request('labeling:discard', 'labeling:discarded');
      }
      live.current.frame.remove();
      live.current = undefined;
      return true;
    } catch (e) {
      setError(describeLabelingError(e));
      return false;
    }
  }
  async function canSubmit() {
    try {
      const state = await request('labeling:request-state', 'labeling:state');
      if (
        !state.ready ||
        state.saving ||
        state.dirty ||
        reading.current ||
        uncertain.current
      )
        throw new Error('请先完成当前图片草稿保存与平台回读，再提交整包');
      return true;
    } catch (e) {
      setError(describeLabelingError(e));
      return false;
    }
  }
  useImperativeHandle(ref, () => ({ leave, canSubmit }));

  useEffect(() => {
    ++generation.current;
    let disposed = false;
    let frame: HTMLIFrameElement | undefined;
    let session: EditorSession | undefined;
    let initialized = false;
    let timeout: ReturnType<typeof setTimeout>;
    uncertain.current = true;
    callbacks.current.onBlocked(true);
    setReady(false);
    setError('');
    setStatus('正在打开编辑器');
    async function readback(expectedRevision?: number) {
      if (!session || disposed) return;
      reading.current = true;
      callbacks.current.onBlocked(true);
      try {
        const value = confirmedDraft(
          await tasks.draft(context.assignmentId, context.sampleId),
          context.assignmentId,
          context.sampleId,
        );
        if (disposed) return;
        if (
          expectedRevision !== undefined &&
          value.revision !== expectedRevision
        )
          throw new Error('平台 revision 与编辑器回执不一致，请恢复核对');
        callbacks.current.onDraft(value);
        uncertain.current = false;
        setStatus(`平台草稿 revision ${value.revision} · 尚未整包提交`);
      } catch (e) {
        if (!disposed) {
          uncertain.current = true;
          setError(describeLabelingError(e));
        }
      } finally {
        if (!disposed) {
          reading.current = false;
          callbacks.current.onBlocked(uncertain.current);
        }
      }
    }
    const receive = (event: MessageEvent) => {
      if (
        disposed ||
        !session ||
        !frame ||
        event.origin !== location.origin ||
        event.source !== frame.contentWindow ||
        !validEditorMessage(event.data, session)
      )
        return;
      const data = event.data;
      const waiter =
        typeof data.requestId === 'string'
          ? pending.current.get(data.requestId)
          : undefined;
      if (
        typeof data.requestId === 'string' &&
        waiter &&
        waiter.type === data.type
      ) {
        clearTimeout(waiter.timer);
        pending.current.delete(data.requestId);
        waiter.resolve(data);
      }
      if (
        data.type === 'labeling:load-failed' ||
        data.type === 'labeling:save-failed'
      ) {
        uncertain.current = data.type === 'labeling:save-failed';
        callbacks.current.onBlocked(uncertain.current);
        setError(
          `${data.message || '编辑器不可用'}${data.code ? `（${data.code}）` : ''}`,
        );
        setStatus(
          data.type === 'labeling:save-failed'
            ? '保存未确认'
            : '加载失败，可重新打开',
        );
        if (
          data.status === 403 ||
          data.status === 404 ||
          (typeof data.code === 'string' &&
            [
              'PROJECT_PAUSED',
              'PROJECT_ARCHIVED',
              'ASSIGNMENT_CLOSED',
            ].includes(data.code))
        ) {
          frame.remove();
          live.current = undefined;
        }
      }
      if (
        data.type === 'labeling:ready' ||
        (data.type === 'labeling:state' && data.ready === true)
      ) {
        clearTimeout(timeout);
        setReady(true);
        if (!initialized) {
          initialized = true;
          uncertain.current = false;
          callbacks.current.onBlocked(false);
          setStatus('编辑器已就绪 · LS Submit / Update 仅保存当前草稿');
        }
      }
      if (data.type === 'labeling:saved') {
        uncertain.current = true;
        callbacks.current.onBlocked(true);
        if (
          typeof data.revision !== 'number' ||
          !Number.isInteger(data.revision)
        ) {
          setError('无效的保存版本回执，请恢复核对');
          return;
        }
        void readback(data.revision);
      }
    };
    // Listener is installed before either the session is launched or iframe is appended.
    window.addEventListener('message', receive);
    void editor
      .open(context.assignmentId, context.sampleId)
      .then((value) => {
        if (disposed) return;
        const url = editorLaunch(value, context, location.origin);
        session = value;
        frame = document.createElement('iframe');
        frame.title = 'Label Studio 图片分类编辑器';
        frame.style.cssText =
          'width:100%;max-width:100%;height:740px;border:0;display:block;';
        frame.referrerPolicy = 'no-referrer';
        live.current = { session, frame };
        frame.addEventListener('load', () => {
          if (!disposed)
            void request('labeling:request-state', 'labeling:state').catch(
              () => {},
            );
        });
        frame.src = url;
        holder.current?.appendChild(frame);
        timeout = setTimeout(() => {
          if (!disposed) {
            setError(
              '编辑器加载超时，请检查同源网关、Cookie 和 editor-assets 转发',
            );
            setStatus('加载失败');
            frame?.remove();
            live.current = undefined;
            uncertain.current = false;
            callbacks.current.onBlocked(false);
          }
        }, 25000);
      })
      .catch((e) => {
        if (!disposed) {
          uncertain.current = false;
          callbacks.current.onBlocked(false);
          setError(describeLabelingError(e));
          setStatus('打开失败 · 可恢复');
        }
      });
    const token = storage.get<string>(STORAGE_KEYS.TOKEN);
    const accountWatch = setInterval(() => {
      if (storage.get<string>(STORAGE_KEYS.TOKEN) !== token) {
        disposed = true;
        ++generation.current;
        clearInterval(accountWatch);
        frame?.remove();
        live.current = undefined;
        setReady(false);
        setError('登录身份已变化，请从平台重新打开任务');
        callbacks.current.onBlocked(true);
      }
    }, 1000);
    const beforeUnload = (e: BeforeUnloadEvent) => {
      if (live.current) {
        e.preventDefault();
        e.returnValue = '';
      }
    };
    window.addEventListener('beforeunload', beforeUnload);
    return () => {
      disposed = true;
      ++generation.current;
      clearTimeout(timeout);
      clearInterval(accountWatch);
      window.removeEventListener('message', receive);
      window.removeEventListener('beforeunload', beforeUnload);
      frame?.remove();
      live.current = undefined;
      for (const p of pending.current.values()) {
        clearTimeout(p.timer);
        p.reject(new Error('编辑器已关闭'));
      }
      pending.current.clear();
      reading.current = false;
    };
  }, [
    context.assignmentId,
    context.sampleId,
    context.actorId,
    context.specVersionId,
    retry,
  ]);

  async function sync() {
    if (syncing || reading.current) return;
    const current = generation.current;
    setSyncing(true);
    setError('');
    callbacks.current.onBlocked(true);
    try {
      await editor.sync(context.assignmentId, context.sampleId);
      const value = confirmedDraft(
        await tasks.draft(context.assignmentId, context.sampleId),
        context.assignmentId,
        context.sampleId,
      );
      if (current !== generation.current) return;
      callbacks.current.onDraft(value);
      uncertain.current = false;
      // Dispose stale LS state after reconciliation; a fresh session reads the platform revision.
      live.current?.frame.remove();
      live.current = undefined;
      setRetry((n) => n + 1);
    } catch (e) {
      if (current !== generation.current) return;
      uncertain.current = true;
      setError(
        `${describeLabelingError(e)}。${e instanceof LabelingError && e.code === 'REVISION_CONFLICT' ? '请重新读取平台草稿，禁止旧答案覆盖。' : '保存尚未确认，请继续核对；不要重复写入。'}`,
      );
    } finally {
      if (current === generation.current) {
        setSyncing(false);
        callbacks.current.onBlocked(uncertain.current);
      }
    }
  }
  async function reloadPlatform() {
    if (
      syncing ||
      reading.current ||
      !window.confirm(
        '重新读取会关闭当前编辑器并丢弃本地选择。确认以平台草稿为准？',
      )
    )
      return;
    const current = generation.current;
    setSyncing(true);
    setError('');
    callbacks.current.onBlocked(true);
    try {
      if (live.current) {
        const state = await request('labeling:request-state', 'labeling:state');
        if (state.saving) throw new Error('保存仍在进行，请等待');
      }
      const value = confirmedDraft(
        await tasks.draft(context.assignmentId, context.sampleId),
        context.assignmentId,
        context.sampleId,
      );
      if (current !== generation.current) return;
      callbacks.current.onDraft(value);
      uncertain.current = false;
      live.current?.frame.remove();
      live.current = undefined;
      setRetry((n) => n + 1);
    } catch (e) {
      if (current !== generation.current) return;
      uncertain.current = true;
      setError(describeLabelingError(e));
    } finally {
      if (current === generation.current) {
        setSyncing(false);
        callbacks.current.onBlocked(uncertain.current);
      }
    }
  }
  return (
    <div>
      <Space wrap style={{ marginBottom: 16 }}>
        <Tag color={ready ? 'blue' : 'default'}>{status}</Tag>
        <Button loading={syncing} onClick={() => void sync()}>
          恢复核对（editor-sync）
        </Button>
        <Button disabled={syncing} onClick={() => void reloadPlatform()}>
          重新读取平台草稿
        </Button>
        <Button
          disabled={syncing}
          onClick={async () => {
            if (await leave()) setRetry((n) => n + 1);
          }}
        >
          重新打开编辑器
        </Button>
      </Space>
      {error && (
        <Alert
          type="error"
          showIcon
          message={error}
          description="禁用、白名单拒绝、票据过期或服务不可用时可重开。保存异常须先恢复核对，409 须重新读取。"
        />
      )}
      {!ready && !error && <Spin />}
      <div
        ref={holder}
        style={{ minWidth: 0, maxWidth: '100%', overflow: 'hidden' }}
      />
    </div>
  );
});
