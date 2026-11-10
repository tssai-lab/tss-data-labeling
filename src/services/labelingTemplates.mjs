// Trusted, built-in declarations. No remote code/schema loading.
export const taskTemplates = Object.freeze([
  Object.freeze({
    id: 'image-classification',
    version: '1.0.0',
    name: '图片单标签分类',
    taskType: 'IMAGE_CLASSIFICATION',
    inputFormat: 'image',
    resultFormat: 'image-classification-v1',
    editorKey: 'image-classification-editor',
    enabled: true,
  }),
  Object.freeze({
    id: 'energy-station-day',
    version: '1.0.0',
    name: '站点日能耗时序（试标）',
    taskType: 'TIMESERIES_ANNOTATION',
    inputFormat: 'energy-station-day-input-v1',
    inputSchemaId: 'energy-station-day-input@1.0.0',
    resultSchemaId: 'energy-station-day-result@1.0.0',
    resultFormat: 'annotation-record-v1',
    editorKey: 'energy-station-day-editor',
    enabled: false,
  }),
]);
export function resolveTaskTemplate(binding = {}, { preview = false } = {}) {
  const legacy = !binding.templateId && !binding.templateVersion;
  const template = taskTemplates.find(
    (t) =>
      t.id === (legacy ? 'image-classification' : binding.templateId) &&
      t.version === (legacy ? '1.0.0' : binding.templateVersion),
  );
  const fail = (code, message) => {
    const error = new Error(message);
    error.code = code;
    throw error;
  };
  if (!template)
    fail(
      'TEMPLATE_UNAVAILABLE',
      '未安装该历史模板版本，请联系管理员恢复对应读取器',
    );
  for (const key of [
    'taskType',
    'editorKey',
    'inputFormat',
    'resultFormat',
    'inputSchemaId',
    'resultSchemaId',
  ])
    if (binding[key] && binding[key] !== template[key])
      fail(
        'TEMPLATE_MISMATCH',
        '任务模板、输入、结果或编辑器版本不匹配，已停止编辑',
      );
  if (!template.enabled && !preview)
    fail(
      'TEMPLATE_UNSUPPORTED',
      '该模板尚未完成后端接入，只能在独立合成演示中试标',
    );
  return template;
}
export function assertClassificationWork(work) {
  const base = resolveTaskTemplate(work.specification || {});
  if (base.id !== 'image-classification')
    throw new Error('TEMPLATE_MISMATCH：当前真实工作台只接入图片分类');
  for (const binding of [work, ...(work.items || [])])
    resolveTaskTemplate({
      templateId: base.id,
      templateVersion: base.version,
      ...binding,
    });
  return base;
}
