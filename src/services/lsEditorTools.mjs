// Native LS interfaces for one platform-owned assignment answer, not new votes.
export const timeseriesInterfaces = Object.freeze([
  'topbar',
  'side-column',
  'controls',
  'edit-history',
  'instruction',
  'annotations:view-all',
  'annotations:tabs',
  'annotations:current',
]);
export const draftButtons = Object.freeze({
  _replace: [
    {
      name: 'platform-save-draft',
      title: '保存本地草稿',
      ariaLabel: '保存本地草稿',
      draftOnly: true,
    },
  ],
});
export function saveLocalLsDraft(storage, input, config, result) {
  if (!Array.isArray(result)) throw new Error('草稿结果必须为数组');
  storage.setItem(
    `platform-ls-source-v1:${input.sampleId}`,
    JSON.stringify({
      version: 1,
      sampleId: input.sampleId,
      inputHash: input.inputHash,
      config,
      result,
      savedAt: new Date().toISOString(),
    }),
  );
}
export function readLocalLsDraft(storage, input, config) {
  const raw = storage.getItem(`platform-ls-source-v1:${input.sampleId}`);
  if (!raw) throw new Error('当前样本没有本地草稿');
  const draft = JSON.parse(raw);
  if (
    draft.version !== 1 ||
    draft.sampleId !== input.sampleId ||
    draft.inputHash !== input.inputHash ||
    draft.config !== config ||
    !Array.isArray(draft.result)
  )
    throw new Error('本地草稿与当前样本或规范不匹配，已停止恢复');
  return draft.result;
}
