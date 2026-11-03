export function listTextClassificationRows(result) {
  const source = Array.isArray(result?.predictionsPreview)
    ? result.predictionsPreview
    : Array.isArray(result?.samples)
      ? result.samples
      : [];
  return source.filter((row) => {
    if (!row || typeof row !== 'object') return false;
    if (typeof row.text === 'string' && row.text.trim().length > 0) {
      return true;
    }
    const preview = row.inputPreview;
    if (!preview || typeof preview !== 'object') return false;
    const kind = String(preview.kind || preview.type || '').toLowerCase();
    return (
      kind === 'text' &&
      [preview.text, preview.summary, preview.path].some(
        (value) => typeof value === 'string' && value.trim().length > 0,
      )
    );
  });
}

export function confidencePercent(value) {
  if (typeof value !== 'number' || !Number.isFinite(value)) return null;
  return Math.min(100, Math.max(0, Number((value * 100).toFixed(1))));
}
