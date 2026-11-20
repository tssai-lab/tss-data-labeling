export function normalizeWorkspaceProject(project) {
  return {
    ...project,
    specVersions: (project.specVersions || []).map((version) => ({
      ...version,
      taskType: version.taskType || project.taskType,
      labels: version.labels || [],
      instructions: version.instructions || '',
      editorConfig: version.editorConfig || {},
    })),
  };
}

export function isLegacyProjectSpec(version, taskType) {
  return Boolean(
    version &&
      version.schemaVersion !== 'label-studio-v1' &&
      ['IMAGE_CLASSIFICATION', 'TIMESERIES_ANNOTATION'].includes(taskType),
  );
}

export function hasProjectAnswer(result) {
  return Boolean(
    result &&
      (result.labelId ||
        result.annotations?.length ||
        result.result?.length ||
        result.emptyReason),
  );
}

export function projectPanel(pathname, query) {
  if (pathname.endsWith('/workbench')) {
    const view = query.get('view');
    if (['export', 'snapshots', 'outcome'].includes(view)) return 'outcome';
    return ['publish', 'ai'].includes(view) ? view : 'review';
  }
  const requested =
    query.get('step') && query.get('panel') === 'publish'
      ? query.get('step')
      : query.get('panel') || query.get('step') || 'data';
  return (
    { spec: 'settings', import: 'data', results: 'review', export: 'outcome' }[
      requested
    ] || requested
  );
}

export function legacyProjectSpecRequest(
  version,
  taskType,
  labels,
  instructions,
) {
  if (
    !labels.length ||
    labels.some((label) => !label.id || !label.name.trim()) ||
    new Set(labels.map((label) => label.id)).size !== labels.length ||
    new Set(labels.map((label) => label.name.trim())).size !== labels.length
  )
    throw new Error('标签不能为空，编号和名称必须唯一');
  return {
    taskType,
    schemaVersion:
      version?.schemaVersion ||
      (taskType === 'IMAGE_CLASSIFICATION'
        ? 'image-classification-v1'
        : 'annotation-record-v1'),
    labels: labels.map((label) => ({ ...label, name: label.name.trim() })),
    instructions,
    editorConfig: version?.editorConfig || {},
    ...(version?.templateId
      ? {
          templateId: version.templateId,
          templateVersion: version.templateVersion,
        }
      : {}),
  };
}
