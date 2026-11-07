import { Alert, Button, Space, Typography } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { labelingJson, operationKey, post } from '@/services/labeling';

type Result = {
  projectId: string;
  sampleId: string;
  actorId: string;
  specVersionId: string;
  revision: number;
  result: { value?: { choices?: string[] } }[];
};
type Session = {
  editorSessionId: string;
  projectId: string;
  sampleId: string;
  actorId: string;
  specVersionId: string;
  editorUrl: string;
};
type Props = {
  projectId: string;
  sampleId: string;
  actorId: string;
  specVersionId: string;
  enabled: boolean;
};

export default function ProjectEditorEntry(props: Props) {
  const [opening, setOpening] = useState(false);
  const [reading, setReading] = useState(false);
  const [error, setError] = useState('');
  const [result, setResult] = useState<Result>();
  const requests = useRef(0);
  const active = useRef(true);
  const windows = useRef(new Map<Window, Session>());
  const path = `/projects/${encodeURIComponent(props.projectId)}/samples/${encodeURIComponent(props.sampleId)}/annotation`;

  async function readResult() {
    const request = ++requests.current;
    setReading(true);
    setError('');
    try {
      const value = await labelingJson<Result>(path);
      if (!active.current || request !== requests.current) return;
      if (
        value.projectId !== props.projectId ||
        value.sampleId !== props.sampleId ||
        value.actorId !== props.actorId ||
        value.specVersionId !== props.specVersionId
      ) {
        throw new Error('结果上下文已变化，请刷新项目规范后重试');
      }
      setResult(value);
    } catch (e) {
      if (active.current && request === requests.current)
        setError(e instanceof Error ? e.message : '读取结果失败');
    } finally {
      if (active.current && request === requests.current) setReading(false);
    }
  }

  async function openEditor() {
    const popup = window.open('about:blank', '_blank');
    if (!popup) {
      setError('请允许浏览器打开标注窗口后重试');
      return;
    }
    setOpening(true);
    setError('');
    try {
      const session = await post<Session>(
        `/projects/${encodeURIComponent(props.projectId)}/editor-sessions`,
        { sampleId: props.sampleId },
        operationKey(),
      );
      const url = new URL(session.editorUrl, window.location.origin);
      if (
        url.origin !== window.location.origin ||
        !url.pathname.startsWith('/api/labeling/v1/project-editor-ui/') ||
        session.projectId !== props.projectId ||
        session.sampleId !== props.sampleId ||
        session.actorId !== props.actorId ||
        session.specVersionId !== props.specVersionId
      ) {
        throw new Error('编辑器上下文已变化，请刷新项目后重试');
      }
      if (!active.current) {
        popup.close();
        return;
      }
      for (const old of windows.current.keys())
        if (old.closed) windows.current.delete(old);
      if (!popup.closed) {
        windows.current.set(popup, session);
        popup.location.replace(url.href);
      }
    } catch (e) {
      popup.close();
      if (active.current)
        setError(e instanceof Error ? e.message : '打开编辑器失败');
    } finally {
      if (active.current) setOpening(false);
    }
  }

  useEffect(() => {
    active.current = true;
    if (props.enabled) void readResult();
    const handler = (event: MessageEvent) => {
      if (event.origin !== window.location.origin || !event.source) return;
      const session = windows.current.get(event.source as Window);
      const value = event.data;
      if (
        !session ||
        !value ||
        value.type !== 'labeling:saved' ||
        value.editorSessionId !== session.editorSessionId ||
        value.projectId !== session.projectId ||
        value.sampleId !== session.sampleId ||
        value.actorId !== session.actorId ||
        value.specVersionId !== session.specVersionId
      )
        return;
      void readResult();
    };
    window.addEventListener('message', handler);
    return () => {
      active.current = false;
      ++requests.current;
      window.removeEventListener('message', handler);
      windows.current.clear();
    };
  }, [
    props.projectId,
    props.sampleId,
    props.actorId,
    props.specVersionId,
    props.enabled,
  ]);

  return (
    <Space direction="vertical" style={{ width: '100%' }}>
      <Space wrap>
        <Button
          type="primary"
          loading={opening}
          disabled={!props.enabled}
          onClick={() => void openEditor()}
        >
          打开 Label Studio 标注
        </Button>
        <Button
          loading={reading}
          disabled={!props.enabled}
          onClick={() => void readResult()}
        >
          读取已保存结果
        </Button>
      </Space>
      <Typography.Text type="secondary">
        请在编辑器中选择类别并保存；返回后可读取平台结果，无需领取任务。
      </Typography.Text>
      {error && <Alert type="error" showIcon message={error} />}
      {result && (
        <Alert
          type={result.revision > 0 ? 'success' : 'info'}
          showIcon
          message={
            result.revision > 0
              ? `本人已保存：${result.result[0]?.value?.choices?.join('、') || '结果为空'}（版本 ${result.revision}）`
              : '当前图片尚无本人保存的分类结果'
          }
        />
      )}
    </Space>
  );
}
