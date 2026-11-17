const key = (scope) => `platform-ls-template-v1:${scope}`;
export function saveTemplateDraft(
  storage,
  scope,
  { templateId, templateVersion = templateId === 'custom' ? 'custom-draft' : '', config, instructions },
) {
  const validId = templateId === 'custom' || /^ls:[a-z0-9_/-]+$/.test(templateId);
  const validVersion = templateId === 'custom' ? templateVersion === 'custom-draft' : /^1\.23\.2:[a-f0-9]{16}$/.test(templateVersion);
  if (!scope || !validId || !validVersion || !config || instructions.length > 20000)
    throw new Error('模板草稿参数错误');
  const draft = {
    formatVersion: 3,
    scope,
    templateId,
    templateVersion,
    inputSchema: 'label-studio-label-config@1.23.2',
    config,
    instructions,
    savedAt: new Date().toISOString(),
  };
  storage.setItem(key(scope), JSON.stringify(draft));
  return draft;
}
export function readTemplateDraft(storage, scope) {
  const raw = storage.getItem(key(scope));
  if (!raw) return null;
  const d = JSON.parse(raw);
  const validId = d.templateId === 'custom' || /^ls:[a-z0-9_/-]+$/.test(d.templateId);
  const validVersion = d.templateId === 'custom' ? d.templateVersion === 'custom-draft' : /^1\.23\.2:[a-f0-9]{16}$/.test(d.templateVersion);
  if (
    !validId || !validVersion ||
    d.formatVersion !== 3 ||
    d.scope !== scope ||
    d.inputSchema !== 'label-studio-label-config@1.23.2' ||
    typeof d.config !== 'string' ||
    typeof d.instructions !== 'string'
  )
    throw new Error('模板草稿版本不兼容');
  return d;
}
