/** 管理员代码目录/文件的读取状态；不执行创建、保存、发布或删除。 */

import React, { useEffect, useMemo, useRef, useState } from 'react';
import {
  extractV2FileText,
  fetchAllV2CodeTreeFiles,
  getAdminCodeVersionFileContent,
  getAdminCodeVersionTree,
  getAdminCodeWorkspaceFileContent,
  getAdminCodeWorkspaceFileMetadata,
  getAdminCodeWorkspaceTree,
} from '@/services/platform';
import { getApiErrorMessage } from '@/utils/apiError';
import {
  buildCodeFileTreeData,
  collectCodeFileTreeExpandedKeys,
} from '@/utils/codeFileTree';
import type { BrowseState } from './presentation';
export function useAdminCodeBrowser() {
  const browseRef = useRef<BrowseState | null>(null);

  const browseLoadSequence = useRef(0);

  const fileLoadSequence = useRef(0);

  const [browse, setBrowse] = useState<BrowseState | null>(null);

  const [browseLoading, setBrowseLoading] = useState(false);

  const [browseReadError, setBrowseReadError] = useState<string>();

  const [fileReadError, setFileReadError] = useState<string>();

  const [browseFiles, setBrowseFiles] = useState<
    Array<{ path: string; fileName?: string; sizeBytes?: number }>
  >([]);

  const [selectedPath, setSelectedPath] = useState<string>();

  const [previewLoading, setPreviewLoading] = useState(false);

  const [previewContent, setPreviewContent] = useState('');

  const [originalPreviewContent, setOriginalPreviewContent] = useState('');

  const [expandedKeys, setExpandedKeys] = useState<React.Key[]>([]);

  useEffect(() => {
    browseRef.current = browse;
  }, [browse]);

  const treeData = useMemo(
    () => buildCodeFileTreeData(browseFiles),
    [browseFiles],
  );

  const previewDirty = previewContent !== originalPreviewContent;

  // 空工作区仍可新增文件/放弃草稿；文件是否可编辑与工作区是否可操作分开判断。
  const workspaceWritable =
    browse?.mode === 'workspace' &&
    !browse.workspaceReadOnly &&
    !browseLoading &&
    !browseReadError;

  const workspaceEditable =
    browse?.mode === 'workspace' &&
    !browse.workspaceReadOnly &&
    browse.fileEditable === true &&
    !previewLoading &&
    !browseLoading &&
    !fileReadError;

  const closeBrowse = () => {
    browseLoadSequence.current += 1;
    fileLoadSequence.current += 1;
    browseRef.current = null;
    setBrowseLoading(false);
    setPreviewLoading(false);
    setBrowseReadError(undefined);
    setFileReadError(undefined);
    setBrowse(null);
    setBrowseFiles([]);
    setPreviewContent('');
    setOriginalPreviewContent('');
    setSelectedPath(undefined);
  };

  const patchBrowse = (patch: Partial<BrowseState>) => {
    const next = browseRef.current ? { ...browseRef.current, ...patch } : null;
    browseRef.current = next;
    setBrowse(next);
  };

  const loadBrowseTree = async (state: BrowseState) => {
    browseRef.current = state;
    const sequence = ++browseLoadSequence.current;
    fileLoadSequence.current += 1;
    const isCurrent = () =>
      sequence === browseLoadSequence.current &&
      browseRef.current?.targetId === state.targetId &&
      browseRef.current?.mode === state.mode;
    patchBrowse({ fileEditable: false, contentHash: undefined });
    setBrowseReadError(undefined);
    setFileReadError(undefined);
    setPreviewLoading(false);
    setBrowseLoading(true);
    setBrowseFiles([]);
    setSelectedPath(undefined);
    setPreviewContent('');
    setOriginalPreviewContent('');
    try {
      const files = await fetchAllV2CodeTreeFiles(
        (prefix) =>
          state.mode === 'workspace'
            ? getAdminCodeWorkspaceTree(state.targetId, prefix, {
                skipErrorHandler: true,
              })
            : getAdminCodeVersionTree(state.targetId, prefix, {
                skipErrorHandler: true,
              }),
        { maxDepth: 8 },
      );
      if (!isCurrent()) return;
      setBrowseFiles(files);
      const data = buildCodeFileTreeData(files);
      setExpandedKeys(collectCodeFileTreeExpandedKeys(data));
      if (files[0]?.path) {
        await loadBrowseFile(state, files[0].path);
      }
    } catch (e: unknown) {
      if (isCurrent())
        setBrowseReadError(getApiErrorMessage(e, '加载目录失败'));
    } finally {
      if (isCurrent()) setBrowseLoading(false);
    }
  };

  const loadBrowseFile = async (state: BrowseState, path: string) => {
    const sequence = ++fileLoadSequence.current;
    const isCurrent = () =>
      sequence === fileLoadSequence.current &&
      browseRef.current?.targetId === state.targetId &&
      browseRef.current?.mode === state.mode;
    setSelectedPath(path);
    setPreviewLoading(true);
    setFileReadError(undefined);
    setPreviewContent('');
    setOriginalPreviewContent('');
    patchBrowse({ fileEditable: false, contentHash: undefined });
    try {
      if (state.mode === 'workspace') {
        const [contentRes, metadata] = await Promise.all([
          getAdminCodeWorkspaceFileContent(state.targetId, path, {
            skipErrorHandler: true,
          }),
          getAdminCodeWorkspaceFileMetadata(state.targetId, path, {
            skipErrorHandler: true,
          }),
        ]);
        if (!isCurrent()) return;
        // 后端 DTO 明确提供文件身份和可编辑标志；未知不能被当作允许编辑。
        if (
          !metadata ||
          metadata.path !== path ||
          typeof metadata.editable !== 'boolean' ||
          typeof metadata.readOnly !== 'boolean' ||
          !Number.isSafeInteger(metadata.workspaceRevision) ||
          Number(metadata.workspaceRevision) < 0
        ) {
          throw new Error('文件元数据响应异常，不能确认编辑状态，请重试');
        }
        const text = extractV2FileText(contentRes as any) || '';
        setPreviewContent(text);
        setOriginalPreviewContent(text);
        patchBrowse({
          workspaceRevision:
            metadata?.workspaceRevision ?? state.workspaceRevision,
          fileEditable:
            metadata.editable === true && metadata.readOnly === false,
          contentHash: metadata?.contentHash,
        });
      } else {
        const res = await getAdminCodeVersionFileContent(state.targetId, path, {
          skipErrorHandler: true,
        });
        if (!isCurrent()) return;
        const text = extractV2FileText(res as any) || '';
        setPreviewContent(text);
        setOriginalPreviewContent(text);
      }
    } catch (e: unknown) {
      if (!isCurrent()) return;
      setPreviewContent('');
      setOriginalPreviewContent('');
      setFileReadError(getApiErrorMessage(e, '读取文件失败'));
    } finally {
      if (isCurrent()) setPreviewLoading(false);
    }
  };
  useEffect(
    () => () => {
      browseLoadSequence.current += 1;
      fileLoadSequence.current += 1;
      browseRef.current = null;
    },
    [],
  );
  return {
    browseRef,
    browse,
    setBrowse,
    browseLoading,
    browseReadError,
    fileReadError,
    browseFiles,
    selectedPath,
    previewLoading,
    previewContent,
    setPreviewContent,
    originalPreviewContent,
    setOriginalPreviewContent,
    expandedKeys,
    setExpandedKeys,
    treeData,
    previewDirty,
    workspaceWritable,
    workspaceEditable,
    closeBrowse,
    patchBrowse,
    loadBrowseTree,
    loadBrowseFile,
  };
}
