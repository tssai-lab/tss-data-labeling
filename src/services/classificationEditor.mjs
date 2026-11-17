export function initialClassification(draft, reference, labels) {
  const allowed = new Set(labels.map((l) => l.id));
  if (draft.revision > 0)
    return { labelId: draft.result.labelId || '', referenceShown: false };
  const id = reference?.result?.labelId;
  return {
    labelId: id && allowed.has(id) ? id : '',
    referenceShown: Boolean(id && allowed.has(id)),
  };
}

// Fixed classification template conversion seam for D's cropped LS controls.
export function classificationFromChoices(raw, labels) {
  if (!Array.isArray(raw) || raw.length !== 1)
    throw new Error('必须恰好有一条单标签分类');
  const item = raw[0];
  if (
    !item ||
    item.from_name !== 'category' ||
    item.to_name !== 'image' ||
    item.type !== 'choices' ||
    !Array.isArray(item.value?.choices) ||
    item.value.choices.length !== 1
  )
    throw new Error('分类控件或结果结构无效');
  const matches = labels.filter((l) => l.name === item.value.choices[0]);
  if (matches.length !== 1) throw new Error('分类不属于当前规范或名称有歧义');
  return { labelId: matches[0].id };
}
