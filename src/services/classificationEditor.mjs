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

export function classificationLabelConfig(labels) {
  if (!labels.length || new Set(labels.map((label) => label.name)).size !== labels.length)
    throw new Error('分类标签不能为空或包含重名标签');
  const escape = (value) => String(value).replace(/[&<>"']/g, (char) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&apos;',
  })[char]);
  return `<View><Image name="image" value="$image" zoom="true" zoomControl="true" rotateControl="true"/><Choices name="category" toName="image" choice="single-radio" layout="inline" required="true">${labels.map((label) => `<Choice value="${escape(label.name)}"/>`).join('')}</Choices></View>`;
}

export function classificationToChoices(labelId, labels) {
  if (!labelId) return [];
  const label = labels.find((item) => item.id === labelId);
  if (!label) throw new Error('草稿分类不属于当前冻结规范');
  return [{ from_name: 'category', to_name: 'image', type: 'choices', value: { choices: [label.name] } }];
}

export function classificationSaveInput(raw, labels, baseRevision, done) {
  const result = Array.isArray(raw) && !raw.length && !done
    ? {} : classificationFromChoices(raw, labels);
  return { baseRevision, explicitDone: done, resultFormat: 'image-classification-v1', result };
}
