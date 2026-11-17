import { Link } from '@umijs/max';
import { Alert, Button, Card, Space, Typography } from 'antd';
import { type MouseEvent, useEffect, useRef, useState } from 'react';
import { configurationApi } from '@/services/labelingConfig';
import { loadLsEditor } from '@/services/loadLsEditor';
import {
  readTemplateDraft,
  saveTemplateDraft,
} from '@/services/lsTemplateWorkspace.mjs';

const scope = 'local-ls-template-workspace';
export default function TemplateWorkspace() {
  const host = useRef<HTMLDivElement>(null);
  const [templateId, setTemplateId] = useState('custom');
  const [templateVersion, setTemplateVersion] = useState('custom-draft');
  const [config, setConfig] = useState('');
  const [instructions, setInstructions] = useState('');
  const [revision, setRevision] = useState(0);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const dirty = useRef(false),
    currentConfig = useRef(config);
  const saveValues = useRef({ templateId, templateVersion, instructions });
  saveValues.current = { templateId, templateVersion, instructions };
  useEffect(() => {
    const listener = (e: BeforeUnloadEvent) => {
      if (dirty.current) {
        e.preventDefault();
        e.returnValue = '';
      }
    };
    window.addEventListener('beforeunload', listener);
    return () => window.removeEventListener('beforeunload', listener);
  }, []);
  useEffect(() => {
    if (process.env.NODE_ENV === 'production') return;
    const requests = new AbortController(),
      urls: string[] = [];
    let active = true,
      view: { destroy: () => void } | undefined;
    void loadLsEditor()
      .then((runtime) => {
        if (!active || !host.current) return;
        const shadow =
          host.current.shadowRoot ||
          host.current.attachShadow({ mode: 'open' });
        shadow.replaceChildren();
        runtime.attachStyles(shadow);
        const node = document.createElement('div');
        shadow.append(node);
        view = runtime.mountNativeTemplateConfigurator(node, {
          config: currentConfig.current,
          task: undefined,
          request: configurationApi(
            {},
            (url) => (active ? urls.push(url) : URL.revokeObjectURL(url)),
            requests.signal,
          ),
          onChange: (xml) => {
            currentConfig.current = xml;
            setConfig(xml);
            dirty.current = true;
            setNotice('');
          },
          onRecipe: (recipe) => {
            saveValues.current.templateId = recipe.id;
            setTemplateId(recipe.id);
            setTemplateVersion(
              recipe.id === 'custom' ? 'custom-draft' : recipe.version,
            );
            saveValues.current.templateVersion =
              recipe.id === 'custom' ? 'custom-draft' : recipe.version;
          },
          onSave: () => save(true),
        });
      })
      .catch((e) => {
        if (active) setError(e.message);
      });
    return () => {
      active = false;
      requests.abort();
      view?.destroy();
      urls.forEach(URL.revokeObjectURL);
    };
  }, [revision]);
  if (process.env.NODE_ENV === 'production')
    return <Alert message="模板工作台仅供本地开发验证" />;
  async function save(throwOnError = false) {
    try {
      const runtime = await loadLsEditor();
      runtime.validateLabelConfig(currentConfig.current);
      saveTemplateDraft(localStorage, scope, {
        templateId: saveValues.current.templateId,
        templateVersion: saveValues.current.templateVersion,
        config: currentConfig.current,
        instructions: saveValues.current.instructions,
      });
      dirty.current = false;
      setError('');
      setNotice('模板草稿已保存到本浏览器，尚未绑定正式项目或发布。');
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      if (throwOnError) throw e;
    }
  }
  async function restore() {
    if (
      dirty.current &&
      !window.confirm('是否放弃未保存的配置并恢复本地草稿？')
    )
      return;
    try {
      const d = readTemplateDraft(localStorage, scope);
      if (!d) throw new Error('没有已保存的模板草稿');
      (await loadLsEditor()).validateLabelConfig(d.config);
      currentConfig.current = d.config;
      setConfig(d.config);
      setTemplateId(d.templateId);
      setTemplateVersion(d.templateVersion);
      setInstructions(d.instructions);
      setError('');
      dirty.current = false;
      setRevision((r) => r + 1);
      setNotice('已恢复模板草稿。');
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }
  return (
    <main style={{ padding: 24 }}>
      <Space>
        <Button onClick={() => void restore()}>恢复本地模板草稿</Button>
        <Link
          to="/labeling"
          onClick={(e: MouseEvent<HTMLAnchorElement>) => {
            if (dirty.current && !window.confirm('配置尚未保存，是否离开？'))
              e.preventDefault();
          }}
        >
          返回标注平台
        </Link>
      </Space>
      <Typography.Title level={2}>标注模板配置</Typography.Title>
      <Alert
        showIcon
        message="模板配置"
        description="模板与示例预览由后端提供。当前草稿仅保存在此浏览器；真实数据请在项目内上传和预览。"
      />
      {error && <Alert type="error" message={error} />}
      {notice && <Alert type="success" message={notice} />}

      <Card title="模板与标注界面配置" style={{ marginTop: 16 }}>
        <div ref={host} />
      </Card>

      <span hidden data-testid="config-length">
        {config.length}
      </span>
    </main>
  );
}
