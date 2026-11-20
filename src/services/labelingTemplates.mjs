// 随平台发布的可信声明，不加载远程代码或 Schema。
const builtInTemplates = [
  Object.freeze({
    id: "image-classification",
    version: "1.0.0",
    name: "图片单标签分类",
    taskType: "IMAGE_CLASSIFICATION",
    inputFormat: "image",
    inputSchemaId: "image-classification-input@1.0.0",
    resultSchemaId: "image-classification-result@1.0.0",
    resultFormat: "image-classification-v1",
    editorKey: "image-classification-editor",
    enabled: true,
  }),
  Object.freeze({
    id: "energy-station-day",
    version: "1.0.0",
    name: "站点日能耗标注",
    taskType: "TIMESERIES_ANNOTATION",
    inputFormat: "energy-station-day-input-v1",
    inputSchemaId: "energy-station-day-input@1.0.0",
    resultSchemaId: "energy-station-day-result@1.0.0",
    resultFormat: "annotation-record-v1",
    editorKey: "energy-station-day-editor",
    enabled: true,
  }),
];
export function createTaskPluginRegistry(definitions) {
  const keys = new Set();
  const templates = Object.freeze(
    definitions.map((definition) => {
      for (const key of [
        "id",
        "version",
        "name",
        "taskType",
        "editorKey",
        "inputFormat",
        "resultFormat",
        "inputSchemaId",
        "resultSchemaId",
      ]) {
        if (typeof definition[key] !== "string" || !definition[key])
          throw new Error(`插件缺少字段：${key}`);
      }
      const key = `${definition.id}@${definition.version}`;
      if (keys.has(key)) throw new Error(`插件重复注册：${key}`);
      keys.add(key);
      return Object.freeze({ ...definition });
    })
  );

  function resolve(binding = {}, { preview = false } = {}) {
    const legacy = !binding.templateId && !binding.templateVersion;
    const template = templates.find(
      (t) =>
        t.id === (legacy ? "image-classification" : binding.templateId) &&
        t.version === (legacy ? "1.0.0" : binding.templateVersion)
    );
    const fail = (code, message) => {
      const error = new Error(message);
      error.code = code;
      throw error;
    };
    if (!template)
      fail(
        "TEMPLATE_UNAVAILABLE",
        "未安装该历史模板版本，请联系管理员恢复对应读取器"
      );
    for (const key of [
      "taskType",
      "editorKey",
      "inputFormat",
      "resultFormat",
      "inputSchemaId",
      "resultSchemaId",
    ])
      if (binding[key] && binding[key] !== template[key])
        fail(
          "TEMPLATE_MISMATCH",
          "任务模板、输入、结果或编辑器版本不匹配，已停止编辑"
        );
    if (!template.enabled && !preview)
      fail("TEMPLATE_UNSUPPORTED", "该模板当前未启用");
    return template;
  }
  return Object.freeze({ templates, resolve });
}
const nativeTemplates = [
  {
    id: "computer-vision",
    name: "通用图像标注",
    taskType: "COMPUTER_VISION",
    inputSchemaId: "computer-vision-input@1.0.0",
  },
  {
    id: "time-series",
    name: "通用时序标注",
    taskType: "TIME_SERIES",
    inputSchemaId: "time-series-input@1.0.0",
  },
].map((item) => ({
  ...item,
  version: "1.0.0",
  editorKey: "label-studio-editor",
  inputFormat: "label-studio-task-v1",
  resultFormat: "label-studio-v1",
  resultSchemaId: "label-studio-result@1.0.0",
  enabled: true,
}));
const registry = createTaskPluginRegistry([
  ...builtInTemplates,
  ...nativeTemplates,
]);
export const taskTemplates = registry.templates;
export const resolveTaskTemplate = registry.resolve;

export function availableTaskPlugins(catalogue) {
  return catalogue.map((registered) => {
    let installed = false;
    let reason = "";
    try {
      resolveTaskTemplate(registered);
      installed = true;
    } catch (error) {
      reason = error.message;
    }
    const ready = [
      "importer",
      "inputReader",
      "resultValidator",
      "completion",
      "exporter",
    ].every((key) => registered.capabilities?.[key] === true);
    if (!reason && (!registered.enabled || !ready))
      reason = "插件未启用或必要能力未就绪";
    return {
      ...registered,
      key: `${registered.templateId}@${registered.templateVersion}`,
      name: registered.name || registered.templateId,
      installed,
      enabled: registered.enabled === true && ready && installed,
      unavailableReason: reason,
    };
  });
}

export function assertClassificationWork(work) {
  const base = resolveTaskTemplate(work.specification || {});
  if (base.id !== "image-classification")
    throw new Error("TEMPLATE_MISMATCH：当前真实工作台只接入图片分类");
  for (const binding of [work, ...(work.items || [])])
    resolveTaskTemplate({
      templateId: base.id,
      templateVersion: base.version,
      ...binding,
    });
  return base;
}
