export const INLINE_TEXT_LIMIT = 160;

function isPlainObject(value) {
  return !!value && typeof value === 'object' && !Array.isArray(value);
}

function nonEmptyString(value) {
  return typeof value === 'string' && value.trim().length > 0
    ? value.trim()
    : '';
}

export function safeRelativePreviewPath(value) {
  const path = nonEmptyString(value);
  const hasControlCharacter = [...path].some((character) => {
    const code = character.charCodeAt(0);
    return code <= 31 || code === 127;
  });
  if (!path || path.includes('\\') || hasControlCharacter) {
    return '';
  }
  if (
    path.startsWith('/') ||
    path.startsWith('workspace/') ||
    path.startsWith('users/') ||
    path.startsWith('minio://') ||
    /^[a-z][a-z0-9+.-]*:/i.test(path)
  ) {
    return '';
  }
  const segments = path.split('/');
  if (
    segments.some((segment) => !segment || segment === '.' || segment === '..')
  ) {
    return '';
  }
  return path;
}

function textPreview(explicit, row) {
  const fullText =
    nonEmptyString(explicit?.text) || nonEmptyString(row?.text) || '';
  const suppliedSummary = nonEmptyString(explicit?.summary);
  const summary =
    suppliedSummary ||
    (fullText.length > INLINE_TEXT_LIMIT
      ? `${fullText.slice(0, INLINE_TEXT_LIMIT)}…`
      : fullText);
  const path = safeRelativePreviewPath(explicit?.path);
  if (!summary && !path) return null;
  return {
    kind: 'text',
    name: nonEmptyString(explicit?.name) || '原始文本',
    summary: summary || '点击查看原始文本',
    text: fullText,
    path,
    truncated:
      explicit?.truncated === true ||
      Boolean(path) ||
      fullText.length > INLINE_TEXT_LIMIT,
    contentTruncated: explicit?.contentTruncated === true,
  };
}

export function resolveRowInputPreview(row) {
  if (!isPlainObject(row)) return null;
  const explicit = isPlainObject(row.inputPreview) ? row.inputPreview : null;
  const requestedKind = nonEmptyString(
    explicit?.kind || explicit?.type,
  ).toLowerCase();

  if (requestedKind === 'text') {
    return textPreview(explicit, row);
  }

  if (requestedKind === 'image' || requestedKind === 'file') {
    const path = safeRelativePreviewPath(explicit?.path);
    if (!path) return null;
    return {
      kind: requestedKind,
      path,
      name:
        nonEmptyString(explicit?.name) ||
        path.split('/').pop() ||
        (requestedKind === 'image' ? '原始图片' : '原始文件'),
    };
  }

  const media = isPlainObject(row.media) ? row.media : null;
  const legacyImagePath = safeRelativePreviewPath(media?.path);
  if (legacyImagePath) {
    return {
      kind: 'image',
      path: legacyImagePath,
      name:
        nonEmptyString(media?.name) ||
        legacyImagePath.split('/').pop() ||
        '原始图片',
    };
  }

  return textPreview(null, row);
}
