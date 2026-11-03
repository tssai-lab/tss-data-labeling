function isReady(item, statusField) {
  return String(item?.[statusField] || '').toUpperCase() === 'READY';
}

function filterCandidates(items, input, statusField) {
  if (!input) return [];
  const acceptedSpecIds = input.acceptedSpecIds;
  if (Array.isArray(acceptedSpecIds)) {
    if (!acceptedSpecIds.length) return [];
    return items.filter(
      (item) =>
        isReady(item, statusField) &&
        typeof item.artifactSpecId === 'string' &&
        acceptedSpecIds.includes(item.artifactSpecId),
    );
  }
  const taskTypes = Array.isArray(input.taskTypes) ? input.taskTypes : [];
  return items.filter(
    (item) =>
      isReady(item, statusField) &&
      (!taskTypes.length || taskTypes.includes(item.type)),
  );
}

export function isSpecDrivenInput(input) {
  return Array.isArray(input?.acceptedSpecIds);
}

export function filterModelCandidates(items, input) {
  return filterCandidates(items, input, 'status');
}

export function filterDatasetCandidates(items, input) {
  return filterCandidates(items, input, 'versionStatus').filter((item) =>
    Boolean(item.versionId),
  );
}
