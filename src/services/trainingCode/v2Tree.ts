/** 完整目录读取及树形数据转换。仅整理职责；权限、版本 CAS、重试及兼容规则沿用原实现。 */
import { type V2CodeTreeFileEntry, type V2CodeTreeNode } from './v2Types';


function unwrapList(payload: unknown, depth = 0, strict = false): V2CodeTreeNode[] {
  if (Array.isArray(payload)) {
    if (strict && payload.some((node) => typeof node !== 'string' && (!node || typeof node !== 'object' || Array.isArray(node)))) {
      throw new Error('代码目录节点响应异常');
    }
    if (
      payload.length > 0 &&
      payload.every((node) => typeof node === 'string')
    ) {
      return (payload as string[]).map((path) => ({
        path,
        fileName: path.split('/').pop() || path,
      }));
    }
    return payload as V2CodeTreeNode[];
  }
  if (!payload || typeof payload !== 'object' || depth > 3) {
    if (strict) throw new Error('代码目录响应格式异常');
    return [];
  }
  const obj = payload as Record<string, unknown>;
  if (strict && (obj.errorCode || obj.success === false || (obj.code !== undefined && obj.code !== 200))) {
    throw new Error('代码目录响应异常');
  }

  if (depth < 3 && obj.data && typeof obj.data === 'object') {
    const nested = unwrapList(obj.data, depth + 1, strict);
    if (strict || nested.length) return nested;
  }

  for (const key of [
    'entries',
    'nodes',
    'items',
    'files',
    'children',
    'tree',
    'data',
  ]) {
    const value = obj[key];
    if (Array.isArray(value)) {
      return unwrapList(value, depth + 1, strict);
    }
  }

  const root = obj.root;
  if (root && typeof root === 'object') {
    const rootNode = root as V2CodeTreeNode;
    if (Array.isArray(rootNode.children) && rootNode.children.length) {
      return rootNode.children;
    }
    return [rootNode];
  }

  if (typeof obj.path === 'string' || typeof obj.fileName === 'string' || typeof obj.name === 'string') {
    return [obj as V2CodeTreeNode];
  }

  if (strict) throw new Error('代码目录响应格式异常');
  return [];
}

function isDirNode(node: V2CodeTreeNode): boolean {
  if (node.directory === true || node.isDirectory === true) return true;
  const t = String(
    (node as Record<string, unknown>).kind ||
      node.type ||
      node.nodeType ||
      '',
  ).toUpperCase();
  if (t === 'FILE' || t === 'FILE_LEAF') return false;
  if (t === 'DIR' || t === 'DIRECTORY' || t === 'FOLDER') return true;
  if (Array.isArray(node.children) && node.children.length > 0) return true;
  return false;
}

function resolveV2TreeNodePath(node: V2CodeTreeNode, parentPrefix = ''): string {
  const name = node.fileName || node.name || '';
  const rawPath = node.path || (parentPrefix ? `${parentPrefix}/${name}` : name);
  return String(rawPath).replace(/^\/+/, '').replace(/\\/g, '/');
}

/** 展平 V2 目录树为可预览文件列表（过滤目录） */
export function flattenV2CodeTree(
  payload: unknown,
  parentPath = '',
): Array<{
  path: string;
  fileName: string;
  sizeBytes?: number;
  languageId?: string;
}> {
  const nodes = unwrapList(payload);
  const out: Array<{
    path: string;
    fileName: string;
    sizeBytes?: number;
    languageId?: string;
  }> = [];

  const walk = (list: V2CodeTreeNode[], prefix: string) => {
    list.forEach((node) => {
      const name = node.fileName || node.name || '';
      const path = resolveV2TreeNodePath(node, prefix) || name;
      if (!path) return;
      if (isDirNode(node)) {
        if (Array.isArray(node.children) && node.children.length) {
          walk(node.children, path);
        }
        return;
      }
      out.push({
        path,
        fileName: name || path.split('/').pop() || path,
        sizeBytes: node.sizeBytes ?? node.size,
        languageId: node.languageId,
      });
      if (Array.isArray(node.children) && node.children.length) {
        walk(node.children, path);
      }
    });
  };

  walk(nodes, parentPath);
  return out.filter((item) => !!item.path);
}

/**
 * 递归拉取完整文件列表。
 * 兼容后端 /tree 仅返回单层、目录节点无 children 的情况（通过 prefix 逐层请求）。
 */
export async function fetchAllV2CodeTreeFiles(
  fetchTree: (prefix?: string) => Promise<unknown>,
  options?: { maxDepth?: number },
): Promise<V2CodeTreeFileEntry[]> {
  const maxDepth = options?.maxDepth ?? 24;
  const visitedPrefixes = new Set<string>();
  const filesByPath = new Map<string, V2CodeTreeFileEntry>();

  const loadPrefix = async (prefix: string, depth: number) => {
    const normPrefix = prefix.replace(/^\/+/, '').replace(/\\/g, '/').replace(/\/+$/, '');
    const visitKey = normPrefix || '__root__';
    if (visitedPrefixes.has(visitKey)) return;
    if (depth > maxDepth) throw new Error('代码目录超过读取深度限制，文件列表不完整');
    visitedPrefixes.add(visitKey);
    // 调用方需要完整列表；任何一层失败都向上传递，不能用成功的半棵树冒充完整结果。
    const payload = await fetchTree(normPrefix || undefined);
    await walk(unwrapList(payload, 0, true), normPrefix, depth);
  };

  const walk = async (nodes: V2CodeTreeNode[], prefix: string, depth: number) => {
    if (depth > maxDepth) throw new Error('代码目录超过读取深度限制，文件列表不完整');
    for (const node of nodes) {
      if (!node || typeof node !== 'object' || Array.isArray(node)) throw new Error('代码目录节点响应异常');
      const path = resolveV2TreeNodePath(node, prefix);
      if (!path || (node.children != null && !Array.isArray(node.children))) throw new Error('代码目录节点响应异常');

      if (isDirNode(node)) {
        if (Array.isArray(node.children) && node.children.length > 0) {
          await walk(node.children, path, depth + 1);
        } else {
          await loadPrefix(path, depth + 1);
        }
        continue;
      }

      const fileName = node.fileName || node.name || path.split('/').pop() || path;
      filesByPath.set(path, {
        path,
        fileName,
        sizeBytes: node.sizeBytes ?? node.size,
        languageId: node.languageId,
      });
    }
  };

  await loadPrefix('', 0);
  return Array.from(filesByPath.values()).sort((a, b) =>
    a.path.localeCompare(b.path, 'zh-CN'),
  );
}
