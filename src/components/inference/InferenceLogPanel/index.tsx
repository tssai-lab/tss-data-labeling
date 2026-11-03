import { CopyOutlined, ReloadOutlined } from '@ant-design/icons';
import { FitAddon } from '@xterm/addon-fit';
import { Terminal } from '@xterm/xterm';
import { Button, message, Space, Spin, Typography } from 'antd';
import React, { useEffect, useRef, useState } from 'react';
import { VISUALIZATION_CONFIG } from '@/constants/platform';
import { downloadObject } from '@/services/files';
import { objectNameFromMinioPath } from '@/services/inference';
import '@xterm/xterm/css/xterm.css';

const TERMINAL_HEIGHT = 360;

const RUNNING_STATUSES = new Set(['pending', 'queued', 'scheduled', 'running']);

type UiStatus = 'idle' | 'loading' | 'ready' | 'empty' | 'error';

export type InferenceLogPanelProps = {
  logPath?: string | null;
  status?: string;
  /** 运行中轮询间隔，默认与任务状态轮询一致 */
  pollIntervalMs?: number;
  title?: string;
  description?: string;
};

async function readLogBlob(
  blob: Blob,
): Promise<{ content: string; sizeBytes: number }> {
  if (blob.type?.includes('application/json')) {
    const text = await blob.text();
    let msg = text;
    try {
      const json = JSON.parse(text) as {
        errorMessage?: string;
        message?: string;
      };
      msg = json.errorMessage || json.message || text;
    } catch {
      // 非 JSON 时沿用原文
    }
    throw new Error(msg || '日志加载失败');
  }

  return {
    content: await blob.text(),
    sizeBytes: blob.size,
  };
}

function toTerminalText(content: string) {
  const normalized = content.replace(/\r\n/g, '\n').replace(/\r/g, '\n');
  return normalized.endsWith('\n') ? normalized : `${normalized}\n`;
}

const InferenceLogPanel: React.FC<InferenceLogPanelProps> = ({
  logPath,
  status,
  pollIntervalMs = VISUALIZATION_CONFIG.TASK_STATUS_POLL_INTERVAL_MS,
  title = '运行日志',
  description = '终端内展示本任务的完整推理运行日志，可滚动查看；也可使用上方「下载日志」保存文件。',
}) => {
  const hostRef = useRef<HTMLDivElement>(null);
  const termRef = useRef<Terminal | null>(null);
  const fitRef = useRef<FitAddon | null>(null);
  const contentRef = useRef('');
  const followRef = useRef(true);

  const [uiStatus, setUiStatus] = useState<UiStatus>('idle');
  const [emptyMessage, setEmptyMessage] = useState('');
  const [reloadKey, setReloadKey] = useState(0);
  const [live, setLive] = useState(false);
  const [hasContent, setHasContent] = useState(false);

  const isRunning = status ? RUNNING_STATUSES.has(status) : false;

  useEffect(() => {
    const host = hostRef.current;
    if (!host) return;

    const term = new Terminal({
      convertEol: true,
      disableStdin: true,
      cursorBlink: true,
      cursorStyle: 'bar',
      fontSize: 12,
      lineHeight: 1.35,
      fontFamily:
        'ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace',
      theme: {
        background: '#0b1220',
        foreground: '#e5e7eb',
        cursor: '#94a3b8',
        selectionBackground: '#334155',
      },
      // 放大回滚缓冲，尽量容纳完整日志浏览
      scrollback: 100000,
    });
    const fit = new FitAddon();
    term.loadAddon(fit);
    term.open(host);
    fit.fit();
    termRef.current = term;
    fitRef.current = fit;

    term.onScroll(() => {
      const buf = term.buffer.active;
      followRef.current = buf.viewportY >= buf.baseY;
    });

    const ro = new ResizeObserver(() => {
      try {
        fit.fit();
      } catch {
        // 容器尚未可见时 fit 可能抛错
      }
    });
    ro.observe(host);

    return () => {
      ro.disconnect();
      term.dispose();
      termRef.current = null;
      fitRef.current = null;
      contentRef.current = '';
    };
  }, []);

  const applyContent = (next: string) => {
    const term = termRef.current;
    if (!term) return;
    const prev = contentRef.current;

    if (!prev) {
      term.reset();
      term.write(toTerminalText(next));
    } else if (next === prev) {
      return;
    } else if (next.startsWith(prev)) {
      const delta = next.slice(prev.length);
      if (delta) {
        term.write(delta.replace(/\r\n/g, '\n').replace(/\r/g, '\n'));
      }
    } else {
      term.reset();
      term.write(toTerminalText(next));
    }

    contentRef.current = next;
    setHasContent(true);
    if (followRef.current) {
      term.scrollToBottom();
    }
  };

  useEffect(() => {
    let cancelled = false;
    let timer: number | undefined;
    let fetching = false;

    contentRef.current = '';
    termRef.current?.reset();
    setHasContent(false);
    followRef.current = true;

    const fetchOnce = async (opts?: { silent?: boolean }) => {
      const objectName = objectNameFromMinioPath(logPath);
      if (!objectName) {
        contentRef.current = '';
        termRef.current?.reset();
        if (!cancelled) {
          setLive(false);
          setHasContent(false);
          setUiStatus('empty');
          setEmptyMessage(
            isRunning ? '任务进行中，日志生成后可查看' : '暂无日志文件',
          );
        }
        return;
      }

      if (fetching) return;
      fetching = true;
      if (!opts?.silent && !contentRef.current) {
        setUiStatus('loading');
      }

      try {
        const blob = await downloadObject(objectName, {
          skipErrorHandler: true,
        });
        if (cancelled) return;
        if (!(blob instanceof Blob)) {
          throw new Error('日志响应不是文件流');
        }
        const result = await readLogBlob(blob);
        if (cancelled) return;

        if (!result.content.trim()) {
          contentRef.current = '';
          termRef.current?.reset();
          setHasContent(false);
          setUiStatus('empty');
          setEmptyMessage('日志文件为空');
          setLive(isRunning);
          return;
        }

        applyContent(result.content);
        setUiStatus('ready');
        setLive(isRunning);
        try {
          fitRef.current?.fit();
        } catch {
          // ignore
        }
      } catch (err: any) {
        if (cancelled) return;
        if (!contentRef.current) {
          setUiStatus('error');
          setEmptyMessage(err?.message || '日志加载失败');
          setHasContent(false);
        }
        setLive(false);
      } finally {
        fetching = false;
      }
    };

    void fetchOnce();

    if (isRunning) {
      timer = window.setInterval(() => {
        void fetchOnce({ silent: true });
      }, pollIntervalMs);
    }

    return () => {
      cancelled = true;
      if (timer) window.clearInterval(timer);
    };
  }, [logPath, status, pollIntervalMs, isRunning, reloadKey]);

  const handleCopy = async () => {
    const text = contentRef.current;
    if (!text) return;
    try {
      await navigator.clipboard.writeText(text);
      message.success('已复制日志');
    } catch {
      message.error('复制失败');
    }
  };

  return (
    <div>
      <Space
        align="start"
        style={{
          width: '100%',
          justifyContent: 'space-between',
          marginBottom: 8,
        }}
      >
        <div>
          <Space align="center" size={8}>
            <Typography.Title level={5} style={{ margin: 0 }}>
              {title}
            </Typography.Title>
            {live && (
              <Typography.Text type="success" style={{ fontSize: 12 }}>
                ● 实时输出中
              </Typography.Text>
            )}
          </Space>
          <Typography.Paragraph
            type="secondary"
            style={{ margin: '4px 0 0', fontSize: 13 }}
          >
            {description}
          </Typography.Paragraph>
        </div>
        <Space size={4} style={{ flexShrink: 0 }}>
          <Button
            type="text"
            size="small"
            icon={<ReloadOutlined />}
            disabled={!logPath || (uiStatus === 'loading' && !hasContent)}
            onClick={() => setReloadKey((k) => k + 1)}
          >
            刷新
          </Button>
          <Button
            type="text"
            size="small"
            icon={<CopyOutlined />}
            disabled={!hasContent}
            onClick={() => void handleCopy()}
          >
            复制
          </Button>
        </Space>
      </Space>

      {(uiStatus === 'empty' || uiStatus === 'error') && (
        <Typography.Text
          type="secondary"
          style={{ display: 'block', fontSize: 13, marginBottom: 8 }}
        >
          {emptyMessage}
        </Typography.Text>
      )}

      <div
        style={{
          position: 'relative',
          height: TERMINAL_HEIGHT,
          padding: 8,
          background: '#0b1220',
          borderRadius: 6,
          overflow: 'hidden',
          border: '1px solid #1e293b',
        }}
      >
        {uiStatus === 'loading' && !hasContent && (
          <div
            style={{
              position: 'absolute',
              inset: 0,
              zIndex: 1,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              background: 'rgba(11, 18, 32, 0.72)',
            }}
          >
            <Spin size="small" />
          </div>
        )}
        <div ref={hostRef} style={{ width: '100%', height: '100%' }} />
      </div>
    </div>
  );
};

export default InferenceLogPanel;
