export const classificationFormat = 'image-classification-v1';

export function submissionManifest(items, labels) {
  if (!items.length) throw new Error('任务包没有样本，不能提交');
  const seen = new Set();
  const allowed = new Set(labels.map((label) => label.id));
  return items
    .map((item) => {
      if (seen.has(item.sampleId))
        throw new Error('任务包含重复样本，不能提交');
      seen.add(item.sampleId);
      if (
        !Number.isSafeInteger(item.draftRevision) ||
        item.draftRevision < 1 ||
        item.explicitDone !== true ||
        item.resultFormat !== classificationFormat ||
        !allowed.has(item.result?.labelId)
      ) {
        throw new Error('请先为包内每张图片保存有效分类');
      }
      return { sampleId: item.sampleId, revision: item.draftRevision };
    })
    .sort((a, b) => a.sampleId.localeCompare(b.sampleId));
}

export function confirmedDraft(draft, assignmentId, sampleId) {
  if (
    draft?.assignmentId !== assignmentId ||
    draft?.sampleId !== sampleId ||
    !Number.isSafeInteger(draft.revision) ||
    draft.revision < 0 ||
    draft.resultFormat !== classificationFormat ||
    !draft.result ||
    typeof draft.explicitDone !== 'boolean'
  ) {
    throw new Error('草稿回执不匹配，请重新读取，禁止继续覆盖');
  }
  return draft;
}

export function parsePendingSubmission(raw, actorId, assignmentId) {
  if (!raw) return undefined;
  const value = JSON.parse(raw);
  if (
    value.actorId !== actorId ||
    value.assignmentId !== assignmentId ||
    typeof value.key !== 'string' ||
    !value.key ||
    value.key.length > 200 ||
    !Array.isArray(value.items) ||
    !value.items.length
  )
    throw new Error('本地待确认提交记录无效，请联系负责人核对');
  const ids = new Set();
  for (const item of value.items) {
    if (
      typeof item.sampleId !== 'string' ||
      !item.sampleId ||
      ids.has(item.sampleId) ||
      !Number.isSafeInteger(item.revision) ||
      item.revision < 1
    )
      throw new Error('待确认提交的版本清单无效');
    ids.add(item.sampleId);
  }
  return value;
}
