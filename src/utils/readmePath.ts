/** 在 zip / 文件清单中定位 README.md（大小写不敏感，优先根目录） */

export function isReadmeFileName(name?: string | null): boolean {
  if (!name) return false;
  const base = name.split(/[/\\]/).pop() || name;
  return /^readme\.md$/i.test(base.trim());
}

export function findReadmePath(
  paths: Array<string | null | undefined>,
): string | undefined {
  const normalized = paths
    .map((p) => (typeof p === 'string' ? p.trim() : ''))
    .filter(Boolean);
  if (!normalized.length) return undefined;

  const rootHit = normalized.find((p) => {
    const parts = p.replace(/\\/g, '/').split('/').filter(Boolean);
    return parts.length === 1 && isReadmeFileName(parts[0]);
  });
  if (rootHit) return rootHit;

  return normalized.find((p) => isReadmeFileName(p));
}

export type NamedFileRef = {
  fileName?: string | null;
  path?: string | null;
  metadata?: Record<string, unknown> | null;
};

/** 从样本 data / 文件列表项中找出 README.md（优先更短路径） */
export function findReadmeNamedFile<T extends NamedFileRef>(
  files: T[],
): T | undefined {
  const labeled = files.map((file) => {
    const meta = file.metadata ?? undefined;
    const metaPath = typeof meta?.path === 'string' ? meta.path : '';
    const metaName = typeof meta?.fileName === 'string' ? meta.fileName : '';
    const path = (
      file.path ||
      file.fileName ||
      metaPath ||
      metaName ||
      ''
    ).trim();
    return { file, path };
  });
  const hitPath = findReadmePath(labeled.map((item) => item.path));
  if (!hitPath) return undefined;
  return labeled.find((item) => item.path === hitPath)?.file;
}
