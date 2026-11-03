import { Form, message } from 'antd';
import React, { useCallback, useEffect, useRef, useState } from 'react';
import type { CodeVersionDetail, CodeVersionListItem } from '@/services/code';
import {
  fetchCodeEditablePreview,
  getAdminCodeReviewDetail,
  getCodeVersionDetail,
  listAdminCodeReviewFiles,
  listCodeAssetVersions,
  previewAdminCodeReviewFile,
  previewCodeEditableFile,
} from '@/services/platform';
import { getApiErrorMessage } from '@/utils/apiError';
import type {
  EditNameFormValues,
  NewFileFormValues,
  RenameFileFormValues,
} from './ownerCodePresentation';
/** 详情、目录、预览与版本读取；写操作仍由页面按原权限和顺序执行。 */
export function useOwnerCodeDetailReads(
  codeVersionId: string,
  adminReviewMode: boolean,
  listRecord?: CodeVersionListItem,
) {
  const [meta, setMeta] = useState<CodeVersionDetail | null>(
    listRecord ? { ...listRecord } : null,
  );
  const [metaLoading, setMetaLoading] = useState(!listRecord);
  /** 详情加载失败（含 404 / 无权限），用于展示统一拒绝页，避免空壳泄露 ID */
  const [metaLoadFailed, setMetaLoadFailed] = useState(false);
  const [filesLoading, setFilesLoading] = useState(true);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [codeFiles, setCodeFiles] = useState<API.ModelCodeFile[]>([]);
  const [selectedPath, setSelectedPath] = useState<string>();
  const [expandedFileKeys, setExpandedFileKeys] = useState<React.Key[]>([]);
  const [previewContent, setPreviewContent] = useState('');
  const [originalPreviewContent, setOriginalPreviewContent] = useState('');
  const [previewFileName, setPreviewFileName] = useState('');
  const [codePreviewVisible, setCodePreviewVisible] = useState(false);
  const [filesLoadError, setFilesLoadError] = useState<string>();
  const [previewReadError, setPreviewReadError] = useState<string>();
  const previewSequence = useRef(0);
  const filesSequence = useRef(0);
  const metaSequence = useRef(0);
  const versionsSequence = useRef(0);
  const [saving, setSaving] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [downloading, setDownloading] = useState(false);
  const [deprecating, setDeprecating] = useState(false);
  const [archiving, setArchiving] = useState(false);
  const [abandoning, setAbandoning] = useState(false);
  const [fileOpLoading, setFileOpLoading] = useState(false);
  const [editNameOpen, setEditNameOpen] = useState(false);
  const [editNameSubmitting, setEditNameSubmitting] = useState(false);
  const [newFileOpen, setNewFileOpen] = useState(false);
  const [renameFileOpen, setRenameFileOpen] = useState(false);
  const [assetVersions, setAssetVersions] = useState<CodeVersionListItem[]>([]);
  const [versionsLoading, setVersionsLoading] = useState(false);
  /** 是否存在打开中的工作区草稿（含文件增删改） */
  const [workspaceDraftOpen, setWorkspaceDraftOpen] = useState(false);
  /** 发布后强制读版本快照，避免仍命中未关闭的工作区草稿 */
  const preferVersionSnapshotRef = useRef(false);
  /** 同资产发布新版本后刷新版本列表（codeAssetId 不变时也要重拉） */
  const [versionsRefreshKey, setVersionsRefreshKey] = useState(0);
  const [openWorkspaceId, setOpenWorkspaceId] = useState<string>();
  const [validating, setValidating] = useState(false);
  const [downloadingFile, setDownloadingFile] = useState(false);

  const [editNameForm] = Form.useForm<EditNameFormValues>();
  const [newFileForm] = Form.useForm<NewFileFormValues>();
  const [renameFileForm] = Form.useForm<RenameFileFormValues>();

  const codeAssetId = meta?.codeAssetId?.trim();
  const canEditAsset = Boolean(
    codeAssetId &&
      !adminReviewMode &&
      !metaLoadFailed &&
      !metaLoading &&
      !previewLoading &&
      !filesLoading &&
      !previewReadError &&
      !filesLoadError,
  );

  useEffect(
    () => () => {
      // 版本/视角切换及卸载后，旧详情、目录和文件请求全部失效。
      previewSequence.current += 1;
      filesSequence.current += 1;
      metaSequence.current += 1;
      versionsSequence.current += 1;
    },
    [codeVersionId, adminReviewMode],
  );

  const loadMeta = useCallback(async () => {
    if (!codeVersionId) return;
    const sequence = ++metaSequence.current;
    const isCurrent = () => sequence === metaSequence.current;
    setMetaLoading(true);
    setMetaLoadFailed(false);
    try {
      const res = adminReviewMode
        ? await getAdminCodeReviewDetail(codeVersionId, {
            skipErrorHandler: true,
          })
        : await getCodeVersionDetail(codeVersionId, {
            skipErrorHandler: true,
          });
      if (!isCurrent()) return;
      if (res?.success === false) {
        if (!listRecord) {
          throw new Error(
            (res as { errorMessage?: string })?.errorMessage ||
              '训练代码详情加载失败',
          );
        }
        return;
      }
      if (res?.data) {
        setMetaLoadFailed(false);
        setMeta((prev) => {
          if (prev?.codeVersionId && prev.codeVersionId !== codeVersionId) {
            return { ...res.data };
          }
          return { ...prev, ...res.data };
        });
      }
    } catch (error: any) {
      if (!isCurrent()) return;
      if (!listRecord) {
        setMeta(null);
        setMetaLoadFailed(true);
        const status = error?.response?.status;
        if (status === 404 || status === 403) {
          message.error('未找到训练代码版本或无权访问');
        } else {
          message.error(getApiErrorMessage(error, '训练代码详情加载失败'));
        }
      }
    } finally {
      if (isCurrent()) setMetaLoading(false);
    }
  }, [adminReviewMode, codeVersionId, listRecord]);

  const loadAssetVersions = useCallback(async () => {
    const sequence = ++versionsSequence.current;
    const isCurrent = () => sequence === versionsSequence.current;
    if (!codeAssetId) {
      setAssetVersions([]);
      return;
    }
    setVersionsLoading(true);
    try {
      const res = await listCodeAssetVersions(codeAssetId, {
        skipErrorHandler: true,
      });
      if (!isCurrent()) return;
      setAssetVersions(Array.isArray(res?.data) ? res.data : []);
    } catch (error: any) {
      if (!isCurrent()) return;
      message.error(getApiErrorMessage(error, '版本列表加载失败'));
      setAssetVersions([]);
    } finally {
      if (isCurrent()) setVersionsLoading(false);
    }
  }, [codeAssetId, versionsRefreshKey]);

  const loadPreview = useCallback(
    async (path: string, preferVersionSnapshot = false) => {
      if (!codeVersionId || !path) return;
      const sequence = ++previewSequence.current;
      const isCurrent = () => sequence === previewSequence.current;
      setPreviewReadError(undefined);
      setOriginalPreviewContent('');
      setSelectedPath(path);
      setPreviewLoading(true);
      setPreviewContent('');
      setPreviewFileName(path);
      try {
        const res = adminReviewMode
          ? await previewAdminCodeReviewFile(codeVersionId, path, {
              skipErrorHandler: true,
            })
          : await previewCodeEditableFile(
              {
                codeVersionId,
                codeAssetId: meta?.codeAssetId,
                path,
                preferVersionSnapshot,
              },
              { skipErrorHandler: true },
            );
        if (!isCurrent()) return;
        const data = res?.data;
        if (res?.success === false || !data || typeof data.content !== 'string')
          throw new Error('代码文件内容响应异常');
        const content = data?.content || '';
        setPreviewContent(content);
        setOriginalPreviewContent(content);
        setPreviewFileName(data?.fileName || data?.path || path);
      } catch (error: any) {
        if (isCurrent())
          setPreviewReadError(getApiErrorMessage(error, '代码预览加载失败'));
      } finally {
        if (isCurrent()) setPreviewLoading(false);
      }
    },
    [adminReviewMode, codeVersionId, meta?.codeAssetId],
  );

  const loadFiles = useCallback(async () => {
    if (!codeVersionId || metaLoadFailed) return;
    const sequence = ++filesSequence.current;
    const isCurrent = () => sequence === filesSequence.current;
    previewSequence.current += 1;
    setCodeFiles([]);
    setSelectedPath(undefined);
    setPreviewContent('');
    setOriginalPreviewContent('');
    setPreviewReadError(undefined);
    setPreviewLoading(false);
    setFilesLoading(true);
    setFilesLoadError(undefined);
    const preferVersionSnapshot = preferVersionSnapshotRef.current;
    preferVersionSnapshotRef.current = false;
    try {
      if (adminReviewMode) {
        const res = await listAdminCodeReviewFiles(codeVersionId, {
          skipErrorHandler: true,
        });
        if (!isCurrent()) return;
        const files = res.data ?? [];
        setCodeFiles(files as API.ModelCodeFile[]);
        setWorkspaceDraftOpen(false);
        setOpenWorkspaceId(undefined);
        if (files[0]?.path) {
          await loadPreview(files[0].path, true);
        } else {
          setSelectedPath(undefined);
          setPreviewContent('');
          setPreviewFileName('');
        }
      } else {
        const res = await fetchCodeEditablePreview(
          {
            codeVersionId,
            codeAssetId: meta?.codeAssetId,
            preferVersionSnapshot,
          },
          { skipErrorHandler: true },
        );
        if (!isCurrent()) return;
        const files = res?.data?.codeFiles ?? [];
        setCodeFiles(files);
        setFilesLoadError(res?.data?.loadError);
        setWorkspaceDraftOpen(
          Boolean((res?.data as { fromWorkspace?: boolean })?.fromWorkspace),
        );
        setOpenWorkspaceId(
          (res?.data as { workspaceId?: string })?.workspaceId,
        );
        if (res?.data?.codeFilePath && res?.data?.codeContent !== undefined) {
          setSelectedPath(res.data.codeFilePath);
          setPreviewFileName(res.data.codeFileName || res.data.codeFilePath);
          setPreviewContent(res.data.codeContent);
          setOriginalPreviewContent(res.data.codeContent);
        } else if (files[0]?.path) {
          await loadPreview(files[0].path, preferVersionSnapshot);
        } else {
          setSelectedPath(undefined);
          setPreviewContent('');
          setPreviewFileName('');
        }
      }
    } catch (error: any) {
      if (!isCurrent()) return;
      setCodeFiles([]);
      setSelectedPath(undefined);
      setPreviewContent('');
      setWorkspaceDraftOpen(false);
      setFilesLoadError(getApiErrorMessage(error, '代码文件列表加载失败'));
    } finally {
      if (isCurrent()) setFilesLoading(false);
    }
  }, [
    adminReviewMode,
    codeVersionId,
    loadPreview,
    meta?.codeAssetId,
    metaLoadFailed,
  ]);

  useEffect(() => {
    void loadMeta();
  }, [loadMeta]);

  useEffect(() => {
    // 无权限/不存在时不继续拉目录树，避免多余 404 与空壳目录
    if (!meta?.codeVersionId || metaLoadFailed) return;
    void loadFiles();
  }, [loadFiles, meta?.codeVersionId, meta?.codeAssetId, metaLoadFailed]);

  useEffect(() => {
    loadAssetVersions();
  }, [loadAssetVersions]);

  return {
    meta,
    setMeta,
    metaLoading,
    setMetaLoading,
    metaLoadFailed,
    setMetaLoadFailed,
    filesLoading,
    setFilesLoading,
    previewLoading,
    setPreviewLoading,
    codeFiles,
    setCodeFiles,
    selectedPath,
    setSelectedPath,
    expandedFileKeys,
    setExpandedFileKeys,
    previewContent,
    setPreviewContent,
    originalPreviewContent,
    setOriginalPreviewContent,
    previewFileName,
    setPreviewFileName,
    codePreviewVisible,
    setCodePreviewVisible,
    filesLoadError,
    setFilesLoadError,
    previewReadError,
    setPreviewReadError,
    previewSequence,
    filesSequence,
    metaSequence,
    versionsSequence,
    saving,
    setSaving,
    deleting,
    setDeleting,
    downloading,
    setDownloading,
    deprecating,
    setDeprecating,
    archiving,
    setArchiving,
    abandoning,
    setAbandoning,
    fileOpLoading,
    setFileOpLoading,
    editNameOpen,
    setEditNameOpen,
    editNameSubmitting,
    setEditNameSubmitting,
    newFileOpen,
    setNewFileOpen,
    renameFileOpen,
    setRenameFileOpen,
    assetVersions,
    setAssetVersions,
    versionsLoading,
    setVersionsLoading,
    workspaceDraftOpen,
    setWorkspaceDraftOpen,
    preferVersionSnapshotRef,
    versionsRefreshKey,
    setVersionsRefreshKey,
    openWorkspaceId,
    setOpenWorkspaceId,
    validating,
    setValidating,
    downloadingFile,
    setDownloadingFile,
    editNameForm,
    newFileForm,
    renameFileForm,
    codeAssetId,
    canEditAsset,
    loadMeta,
    loadAssetVersions,
    loadPreview,
    loadFiles,
  };
}
