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
    name: "图像标注",
    taskType: "COMPUTER_VISION",
    inputSchemaId: "computer-vision-input@1.0.0",
  },
  {
    id: "time-series",
    name: "时序标注",
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

export function taskTypeFilterKey(taskType) {
  if (taskType === "COMPUTER_VISION" || taskType === "IMAGE_CLASSIFICATION")
    return "COMPUTER_VISION";
  if (taskType === "TIME_SERIES" || taskType === "TIMESERIES_ANNOTATION")
    return "TIME_SERIES";
  return taskType;
}

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
    const contracts = registered.capabilityContracts;
    const missingCapabilities = Array.isArray(contracts)
      ? contracts
          .filter((capability) => capability.required && capability.status !== "READY")
          .map((capability) => capability.name || capability.id)
      : [];
    const ready = Array.isArray(contracts)
      ? contracts.some((capability) => capability.required) && missingCapabilities.length === 0
      : [
          "importer",
          "inputReader",
          "resultValidator",
          "completion",
          "exporter",
        ].every((key) => registered.capabilities?.[key] === true);
    if (!reason && !registered.capabilities)
      reason = "后端插件目录缺少能力信息，请更新标注后端后重试";
    else if (!reason && (!registered.enabled || !ready))
      reason = missingCapabilities.length
        ? `缺少必要能力：${missingCapabilities.join("、")}`
        : "插件未启用或必要能力未就绪";
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

// 新项目按通用能力创建；细分格式仅用于历史项目，不回退为新项目入口。
export function availableProjectTypes(catalogue) {
  const plugins = availableTaskPlugins(catalogue);
  return nativeTemplates.map((template) => {
    const plugin = plugins.find(
      (item) => item.taskType === template.taskType && item.defaultVersion !== false,
    );
    const enabled = !!plugin?.enabled && plugin.creationAvailable !== false;
    return {
      templateId: template.id,
      templateVersion: template.version,
      taskType: template.taskType,
      key: `${template.id}@${template.version}`,
      installed: false,
      ...plugin,
      name: template.name,
      enabled,
      unavailableReason: enabled ? '' : plugin?.unavailableReason || '服务器尚未开放此任务类型，请检查标注服务版本和启用状态',
      description: template.taskType === 'COMPUTER_VISION'
        ? '创建后选择分类、画框、多边形等标注模板。'
        : '创建后配置曲线、标签以及时间点或区间；能耗、温度等数据共用时序标注。',
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
