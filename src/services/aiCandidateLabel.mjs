export function formatAiCandidateLabel(regions = [], parsedConfig = {}) {
  const names = regions.flatMap(({ from_name, value }) => {
    const attributes = from_name && Object.hasOwn(parsedConfig, from_name)
      ? parsedConfig[from_name]?.labels_attrs
      : undefined;
    return Object.entries(value || {}).flatMap(([key, entries]) => {
      if (!(key === 'choices' || key.endsWith('labels')) || !Array.isArray(entries)) return [];
      return entries.filter((entry) => typeof entry === 'string').map((entry) => {
        const display = attributes && Object.hasOwn(attributes, entry)
          ? attributes[entry]?.value
          : undefined;
        const label = typeof display === 'string' && display ? display : entry;
        if (key === 'timeserieslabels' && typeof value.start === 'number' && typeof value.end === 'number')
          return `${label}（${value.start}–${value.end}）`;
        return label;
      });
    });
  });
  return [...new Set(names)].join('、') || '未生成标注区域';
}
