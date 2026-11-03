import type { DataNode } from 'antd/es/tree';

type CodeFileLike = {
  path: string;
  fileName?: string;
  name?: string;
  sizeBytes?: number;
  size?: number;
};

type MutableTreeNode = DataNode & {
  children?: MutableTreeNode[];
};

function normalizePath(path: string) {
  return path.replace(/\\/g, '/').replace(/^\/+/, '').replace(/\/+$/, '');
}

function formatSize(bytes?: number) {
  if (bytes == null || Number.isNaN(bytes)) return '';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

/**
 * 将扁平文件 path 列表还原为 Ant Design Tree 数据（目录可展开，文件为叶子）。
 */
export function buildCodeFileTreeData(files: CodeFileLike[]): DataNode[] {
  const root: MutableTreeNode[] = [];

  const ensureDir = (
    nodes: MutableTreeNode[],
    dirPath: string,
    label: string,
  ): MutableTreeNode => {
    let node = nodes.find((item) => item.key === dirPath);
    if (!node) {
      node = {
        key: dirPath,
        title: label,
        selectable: false,
        children: [],
      };
      nodes.push(node);
    }
    if (!node.children) node.children = [];
    return node;
  };

  files.forEach((file) => {
    const path = normalizePath(file.path || '');
    if (!path) return;
    const parts = path.split('/').filter(Boolean);
    if (!parts.length) return;

    let cursor = root;
    let prefix = '';
    parts.forEach((part, index) => {
      const isLeaf = index === parts.length - 1;
      prefix = prefix ? `${prefix}/${part}` : part;
      if (isLeaf) {
        const sizeLabel = formatSize(file.sizeBytes ?? file.size);
        cursor.push({
          key: path,
          title: sizeLabel ? `${part}（${sizeLabel}）` : part,
          isLeaf: true,
          selectable: true,
        });
        return;
      }
      const dir = ensureDir(cursor, `dir:${prefix}`, part);
      cursor = dir.children!;
    });
  });

  const sortNodes = (nodes: MutableTreeNode[]) => {
    nodes.sort((a, b) => {
      const aDir = !a.isLeaf;
      const bDir = !b.isLeaf;
      if (aDir !== bDir) return aDir ? -1 : 1;
      return String(a.title).localeCompare(String(b.title), 'zh-CN');
    });
    nodes.forEach((node) => {
      if (node.children?.length) sortNodes(node.children);
    });
  };
  sortNodes(root);
  return root;
}

/** 收集需要默认展开的目录 key（全部目录） */
export function collectCodeFileTreeExpandedKeys(nodes: DataNode[]): string[] {
  const keys: string[] = [];
  const walk = (list: DataNode[]) => {
    list.forEach((node) => {
      if (!node.isLeaf && node.key != null) {
        keys.push(String(node.key));
      }
      if (node.children?.length) walk(node.children);
    });
  };
  walk(nodes);
  return keys;
}
