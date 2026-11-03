import {
  CodeOutlined,
  CopyOutlined,
  DownloadOutlined,
  EditOutlined,
  FileAddOutlined,
  ReloadOutlined,
  SaveOutlined,
  UndoOutlined,
} from '@ant-design/icons';
import { PageContainer } from '@ant-design/pro-components';
import { history, useAccess, useLocation, useParams } from '@umijs/max';
import {
  Alert,
  Button,
  Card,
  Col,
  Descriptions,
  Empty,
  Form,
  Input,
  Modal,
  message,
  Popconfirm,
  Row,
  Space,
  Spin,
  Table,
  Tag,
  Tree,
  Typography,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import React, { useCallback, useEffect, useMemo } from 'react';
import CodeEditor from '@/components/CodeEditor';
import CodePreview from '@/components/CodePreview';
import { isTrainingCodeAutoApproveEnabled } from '@/constants/trainingCode';
import type { CodeVersionListItem } from '@/services/code';
import { getCodeUserDisplayName } from '@/services/code';
import {
  abandonCodeWorkspace,
  archiveCodeVersion,
  createCodeWorkspaceFile,
  deleteCodeAsset,
  deleteCodeWorkspaceFile,
  deprecateCodeVersion,
  downloadCodeVersionSingleFile,
  downloadCodeVersionZip,
  downloadCodeWorkspaceFile,
  moveCodeWorkspaceFile,
  publishCodeWorkspaceDraft,
  saveCodeVersionFileAndPublish,
  updateCodeAssetMeta,
  validateCodeVersionExplicit,
  validateCodeWorkspaceDraft,
} from '@/services/platform';
import { getApiErrorMessage } from '@/utils/apiError';
import {
  buildCodeFileTreeData,
  collectCodeFileTreeExpandedKeys,
} from '@/utils/codeFileTree';
import { showValidationResultModal } from '@/utils/codeValidationUi';
import { beginDownloadProgress } from '@/utils/downloadProgressToast';
import { formatDisplayDateTime } from '@/utils/formatDateTime';
import { upsertPendingCodeVersion } from '@/utils/pendingCodeVersions';
import { approvalTag, formatBytes, statusTag } from './ownerCodePresentation';
import { useOwnerCodeDetailReads } from './useOwnerCodeDetailReads';

const TrainingCodeDetail: React.FC = () => {
  const access = useAccess();
  const { codeVersionId = '' } = useParams<{ codeVersionId: string }>();
  const location = useLocation();
  const locationState = location.state as
    | { record?: CodeVersionListItem; from?: 'pending' | 'list' | 'upload' }
    | undefined;
  const listRecord = locationState?.record;
  const fromPending = locationState?.from === 'pending';
  const fromUpload = locationState?.from === 'upload';
  const adminReviewMode = fromPending && access.isAdmin;
  const backPath = fromPending
    ? '/task/code/pending'
    : fromUpload
      ? '/task/code/upload'
      : '/task/code/list';

  const {
    meta,
    setMeta,
    metaLoading,
    metaLoadFailed,
    filesLoading,
    previewLoading,
    codeFiles,
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
    previewReadError,
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
    versionsLoading,
    workspaceDraftOpen,
    setWorkspaceDraftOpen,
    preferVersionSnapshotRef,
    setVersionsRefreshKey,
    openWorkspaceId,
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
  } = useOwnerCodeDetailReads(codeVersionId, adminReviewMode, listRecord);
  const handleSelectFile = (path: string) => {
    if (!codeVersionId || path === selectedPath) return;
    // 无工作区草稿时读版本快照，避免发布后仍命中残留工作区
    loadPreview(path, !workspaceDraftOpen);
  };

  const handleResetPreviewContent = () => {
    setPreviewContent(originalPreviewContent);
    message.info('已恢复为服务端原始内容');
  };

  const handleCopyPreviewContent = () => {
    if (!previewContent) return;
    navigator.clipboard.writeText(previewContent).then(() => {
      message.success('代码已复制到剪贴板');
    });
  };

  const previewDirty = previewContent !== originalPreviewContent;
  /** 有工作区草稿或未保存内容修改时，才可点「保存并发布 / 放弃工作区」 */
  const hasDraft = workspaceDraftOpen || previewDirty;

  const handleSaveAndPublish = useCallback(
    (contentOverride?: string) => {
      const content =
        typeof contentOverride === 'string' ? contentOverride : previewContent;
      const dirty =
        typeof contentOverride === 'string'
          ? contentOverride !== originalPreviewContent
          : previewDirty;
      const publishFile = Boolean(selectedPath && dirty);
      const publishDraftOnly = !publishFile && workspaceDraftOpen;
      if (!publishFile && !publishDraftOnly) return;
      const assetId = meta?.codeAssetId?.trim();
      if (!assetId) {
        message.error('缺少 codeAssetId，无法保存（请刷新后重试）');
        return;
      }
      Modal.confirm({
        title: '保存并发布新版本？',
        content: (
          <div>
            <p>
              当前代码版本不可变。保存会把工作区修改发布为同一代码资产下的
              <strong>新版本</strong>。
            </p>
            <p style={{ color: '#8c8c8c', marginBottom: 0 }}>
              {publishFile
                ? `文件：${selectedPath}${
                    meta?.version ? `（基于 ${meta.version}）` : ''
                  }`
                : `将发布当前工作区草稿${
                    meta?.version ? `（基于 ${meta.version}）` : ''
                  }`}
            </p>
          </div>
        ),
        okText: '保存并发布',
        cancelText: '取消',
        onOk: async () => {
          setSaving(true);
          try {
            const publishPath = selectedPath?.trim();
            const res = publishFile
              ? await saveCodeVersionFileAndPublish(
                  {
                    codeAssetId: assetId,
                    baseVersionId: codeVersionId,
                    path: publishPath || '',
                    content,
                    currentVersionLabel: meta?.version,
                  },
                  { skipErrorHandler: true },
                )
              : await publishCodeWorkspaceDraft(
                  {
                    codeAssetId: assetId,
                    baseVersionId: codeVersionId,
                    currentVersionLabel: meta?.version,
                  },
                  { skipErrorHandler: true },
                );
            const newId = res.data.publishedVersionId;
            const publishedVersion = res.data.publishedVersion;
            message.success(`已发布新版本 ${publishedVersion || ''}`.trim());
            if (publishFile) {
              setPreviewContent(content);
              setOriginalPreviewContent(content);
            }
            setWorkspaceDraftOpen(false);
            if (!isTrainingCodeAutoApproveEnabled()) {
              upsertPendingCodeVersion({
                codeVersionId: newId,
                codeAssetId: meta?.codeAssetId || assetId,
                codeAssetName: getCodeUserDisplayName(meta ?? undefined),
                fileName: meta?.fileName,
                trainingProfile: meta?.trainingProfile,
                approvalStatus: 'PENDING',
                source: 'publish',
              });
            }
            // 发布后直接展示新版本快照 + 刷新同资产版本列表，无需用户手动刷新
            preferVersionSnapshotRef.current = true;
            setVersionsRefreshKey((key) => key + 1);
            if (meta) {
              setMeta({
                ...meta,
                codeVersionId: newId,
                version: publishedVersion || meta.version,
              });
            }
            history.replace(`/task/code/detail/${encodeURIComponent(newId)}`, {
              record: {
                ...(meta || {}),
                codeVersionId: newId,
                version: publishedVersion || meta?.version || '',
                codeAssetId: meta?.codeAssetId || '',
                codeAssetName: getCodeUserDisplayName(meta ?? undefined),
                fileName: meta?.fileName || '',
                trainingProfile: meta?.trainingProfile || '',
                approvalStatus: meta?.approvalStatus || 'APPROVED',
                status: meta?.status || 'READY',
              },
              ...(locationState?.from ? { from: locationState.from } : {}),
            });
          } catch (error: any) {
            message.error(getApiErrorMessage(error, '保存训练代码失败'));
            throw error;
          } finally {
            setSaving(false);
          }
        },
      });
    },
    [
      codeVersionId,
      locationState?.from,
      meta,
      originalPreviewContent,
      previewContent,
      previewDirty,
      selectedPath,
      workspaceDraftOpen,
    ],
  );

  const handleDownloadZip = useCallback(async () => {
    if (!codeVersionId) return;
    setDownloading(true);
    const progress = beginDownloadProgress();
    try {
      await downloadCodeVersionZip(
        codeVersionId,
        meta?.fileName || `${codeVersionId}.zip`,
        { skipErrorHandler: true, onProgress: progress.update },
      );
      progress.close();
      message.success('已交给浏览器下载');
    } catch (error: any) {
      progress.close();
      const tip = getApiErrorMessage(error, '下载失败');
      if (tip !== '已取消下载') {
        message.error(tip);
      }
    } finally {
      setDownloading(false);
    }
  }, [codeVersionId, meta?.fileName]);

  const handleValidateVersion = useCallback(async () => {
    if (!codeVersionId) return;
    const approved =
      String(meta?.approvalStatus || '').toUpperCase() === 'APPROVED';
    const run = async () => {
      setValidating(true);
      try {
        const res = await validateCodeVersionExplicit(codeVersionId, {
          trainingProfile: meta?.trainingProfile,
          skipErrorHandler: true,
        });
        showValidationResultModal(res.data);
        await loadMeta();
      } catch (error: any) {
        message.error(getApiErrorMessage(error, '版本校验失败'));
      } finally {
        setValidating(false);
      }
    };
    if (approved) {
      Modal.confirm({
        title: '再次校验已批准版本？',
        content: '后端会幂等复用等价证据；制品或策略变化时可能回到待审。',
        okText: '继续校验',
        onOk: run,
      });
      return;
    }
    await run();
  }, [codeVersionId, loadMeta, meta?.approvalStatus, meta?.trainingProfile]);

  const handleValidateWorkspace = useCallback(async () => {
    if (!openWorkspaceId) {
      message.warning('当前无打开的工作区');
      return;
    }
    setValidating(true);
    try {
      const res = await validateCodeWorkspaceDraft(openWorkspaceId, {
        skipErrorHandler: true,
      });
      showValidationResultModal(res.data);
    } catch (error: any) {
      message.error(getApiErrorMessage(error, '工作区校验失败'));
    } finally {
      setValidating(false);
    }
  }, [openWorkspaceId]);

  const handleDownloadCurrentFile = useCallback(async () => {
    if (!selectedPath) return;
    setDownloadingFile(true);
    try {
      if (workspaceDraftOpen && openWorkspaceId) {
        await downloadCodeWorkspaceFile(
          openWorkspaceId,
          selectedPath,
          previewFileName,
          { skipErrorHandler: true },
        );
      } else {
        await downloadCodeVersionSingleFile(
          codeVersionId,
          selectedPath,
          previewFileName,
          { skipErrorHandler: true },
        );
      }
      message.success('已开始下载');
    } catch (error: any) {
      message.error(getApiErrorMessage(error, '下载失败'));
    } finally {
      setDownloadingFile(false);
    }
  }, [
    codeVersionId,
    openWorkspaceId,
    previewFileName,
    selectedPath,
    workspaceDraftOpen,
  ]);

  const handleOpenEditName = useCallback(() => {
    editNameForm.setFieldsValue({
      name: getCodeUserDisplayName(meta ?? undefined),
    });
    setEditNameOpen(true);
  }, [editNameForm, meta]);

  const handleEditNameSubmit = useCallback(async () => {
    if (!codeAssetId) {
      message.error('缺少 codeAssetId，无法修改名称');
      return;
    }
    try {
      const values = await editNameForm.validateFields();
      setEditNameSubmitting(true);
      await updateCodeAssetMeta(
        codeAssetId,
        { name: values.name.trim() },
        { skipErrorHandler: true },
      );
      message.success('代码名称已更新');
      setEditNameOpen(false);
      await loadMeta();
    } catch (error: any) {
      if (error?.errorFields) return;
      message.error(getApiErrorMessage(error, '更新代码名称失败'));
    } finally {
      setEditNameSubmitting(false);
    }
  }, [codeAssetId, editNameForm, loadMeta]);

  const handleDeprecate = useCallback(async () => {
    if (!codeVersionId) return;
    setDeprecating(true);
    try {
      await deprecateCodeVersion(codeVersionId, { skipErrorHandler: true });
      message.success('已弃用该代码版本');
      await loadMeta();
      await loadAssetVersions();
    } catch (error: any) {
      message.error(getApiErrorMessage(error, '弃用失败'));
    } finally {
      setDeprecating(false);
    }
  }, [codeVersionId, loadAssetVersions, loadMeta]);

  const handleArchive = useCallback(async () => {
    if (!codeVersionId) return;
    setArchiving(true);
    try {
      await archiveCodeVersion(codeVersionId, { skipErrorHandler: true });
      message.success('已归档该代码版本');
      await loadMeta();
      await loadAssetVersions();
    } catch (error: any) {
      message.error(getApiErrorMessage(error, '归档失败'));
    } finally {
      setArchiving(false);
    }
  }, [codeVersionId, loadAssetVersions, loadMeta]);

  const handleAbandonWorkspace = useCallback(async () => {
    if (!codeAssetId) {
      message.error('缺少 codeAssetId，无法放弃工作区');
      return;
    }
    setAbandoning(true);
    try {
      if (workspaceDraftOpen) {
        const res = await abandonCodeWorkspace(codeAssetId, {
          skipErrorHandler: true,
        });
        if (res?.data?.abandoned) {
          message.success('已放弃工作区草稿');
        } else {
          message.info('当前无打开的工作区草稿');
        }
        setWorkspaceDraftOpen(false);
        await loadFiles();
        return;
      }
      if (previewContent !== originalPreviewContent) {
        setPreviewContent(originalPreviewContent);
        message.success('已放弃未保存的内容修改');
      }
    } catch (error: any) {
      message.error(getApiErrorMessage(error, '放弃工作区失败'));
    } finally {
      setAbandoning(false);
    }
  }, [
    codeAssetId,
    loadFiles,
    originalPreviewContent,
    previewContent,
    workspaceDraftOpen,
  ]);

  const handleDeleteAsset = useCallback(async () => {
    setDeleting(true);
    try {
      const res = await deleteCodeAsset(meta?.codeAssetId, {
        skipErrorHandler: true,
        codeVersionId,
      });
      if (res?.data?.localOnly) {
        message.success('已从列表移除（本地待审记录，服务端无对应资产）');
      } else {
        message.success('已删除训练代码资产');
      }
      history.push(backPath);
    } catch (error: any) {
      message.error(getApiErrorMessage(error, '删除训练代码失败'));
    } finally {
      setDeleting(false);
    }
  }, [backPath, codeVersionId, meta?.codeAssetId]);

  const handleCreateFile = useCallback(async () => {
    if (!codeAssetId) {
      message.error('缺少 codeAssetId，无法新建文件');
      return;
    }
    try {
      const values = await newFileForm.validateFields();
      const path = values.path.trim();
      setFileOpLoading(true);
      await createCodeWorkspaceFile(
        {
          codeAssetId,
          baseVersionId: codeVersionId,
          path,
          content: values.content,
        },
        { skipErrorHandler: true },
      );
      message.success('文件已写入工作区草稿，需「保存并发布」才会成为新版本');
      setWorkspaceDraftOpen(true);
      setNewFileOpen(false);
      newFileForm.resetFields();
      await loadFiles();
      await loadPreview(path);
    } catch (error: any) {
      if (error?.errorFields) return;
      message.error(getApiErrorMessage(error, '新建文件失败'));
    } finally {
      setFileOpLoading(false);
    }
  }, [codeAssetId, codeVersionId, loadFiles, loadPreview, newFileForm]);

  const handleRenameFile = useCallback(async () => {
    if (!codeAssetId || !selectedPath) {
      message.error('请先选择要重命名的文件');
      return;
    }
    try {
      const values = await renameFileForm.validateFields();
      const targetPath = values.targetPath.trim();
      setFileOpLoading(true);
      await moveCodeWorkspaceFile(
        {
          codeAssetId,
          baseVersionId: codeVersionId,
          sourcePath: selectedPath,
          targetPath,
        },
        { skipErrorHandler: true },
      );
      message.success(
        '文件已重命名（工作区草稿），需「保存并发布」才会成为新版本',
      );
      setWorkspaceDraftOpen(true);
      setRenameFileOpen(false);
      renameFileForm.resetFields();
      setSelectedPath(targetPath);
      await loadFiles();
      await loadPreview(targetPath);
    } catch (error: any) {
      if (error?.errorFields) return;
      message.error(getApiErrorMessage(error, '重命名文件失败'));
    } finally {
      setFileOpLoading(false);
    }
  }, [
    codeAssetId,
    codeVersionId,
    loadFiles,
    loadPreview,
    renameFileForm,
    selectedPath,
  ]);

  const handleDeleteFile = useCallback(async () => {
    if (!codeAssetId || !selectedPath) {
      message.error('请先选择要删除的文件');
      return;
    }
    setFileOpLoading(true);
    try {
      await deleteCodeWorkspaceFile(
        {
          codeAssetId,
          baseVersionId: codeVersionId,
          path: selectedPath,
        },
        { skipErrorHandler: true },
      );
      message.success('文件已从工作区草稿删除，需「保存并发布」才会成为新版本');
      setWorkspaceDraftOpen(true);
      setSelectedPath(undefined);
      setPreviewContent('');
      setPreviewFileName('');
      await loadFiles();
    } catch (error: any) {
      message.error(getApiErrorMessage(error, '删除文件失败'));
    } finally {
      setFileOpLoading(false);
    }
  }, [codeAssetId, codeVersionId, loadFiles, selectedPath]);

  const handleOpenRenameFile = useCallback(() => {
    if (!selectedPath) {
      message.warning('请先选择要重命名的文件');
      return;
    }
    renameFileForm.setFieldsValue({ targetPath: selectedPath });
    setRenameFileOpen(true);
  }, [renameFileForm, selectedPath]);

  const handleNavigateVersion = useCallback(
    (record: CodeVersionListItem) => {
      history.push(
        `/task/code/detail/${encodeURIComponent(record.codeVersionId)}`,
        locationState?.from ? { record, from: locationState.from } : { record },
      );
    },
    [locationState?.from],
  );

  const displayName = useMemo(
    () => getCodeUserDisplayName(meta ?? undefined),
    [meta],
  );

  const title = useMemo(() => {
    if (displayName !== '-') return displayName;
    return '训练代码详情';
  }, [displayName]);

  const breadcrumbItems = useMemo(() => {
    const items: { title: React.ReactNode }[] = [
      {
        title: <a onClick={() => history.push('/task/code/list')}>训练代码</a>,
      },
    ];
    if (fromPending) {
      items.push({
        title: <a onClick={() => history.push('/task/code/pending')}>待审核</a>,
      });
    }
    items.push({ title: title || '训练代码详情' });
    return items;
  }, [fromPending, title]);

  const fileTreeData = useMemo(
    () => buildCodeFileTreeData(codeFiles),
    [codeFiles],
  );
  const fileTreeExpandedKeys = useMemo(
    () => collectCodeFileTreeExpandedKeys(fileTreeData),
    [fileTreeData],
  );

  useEffect(() => {
    setExpandedFileKeys(fileTreeExpandedKeys);
  }, [fileTreeExpandedKeys]);

  const versionColumns: ColumnsType<CodeVersionListItem> = useMemo(
    () => [
      {
        title: '版本',
        dataIndex: 'version',
        key: 'version',
        render: (version: string, record) => (
          <Space size={4}>
            <span>{version || '-'}</span>
            {record.codeVersionId === codeVersionId ? (
              <Tag color="blue">当前</Tag>
            ) : null}
          </Space>
        ),
      },
      {
        title: '就绪状态',
        dataIndex: 'status',
        key: 'status',
        render: (status: string) => statusTag(status),
      },
      {
        title: '审核状态',
        dataIndex: 'approvalStatus',
        key: 'approvalStatus',
        render: (status: string) => approvalTag(status),
      },
      {
        title: '校验状态',
        dataIndex: 'validationStatus',
        key: 'validationStatus',
        render: (status?: string) =>
          status ? (
            <Tag color={status === 'PASSED' ? 'success' : 'default'}>
              {status}
            </Tag>
          ) : (
            '-'
          ),
      },
      {
        title: '上传时间',
        dataIndex: 'createdAt',
        key: 'createdAt',
        render: (value?: string) => formatDisplayDateTime(value),
      },
      {
        title: '操作',
        key: 'action',
        width: 80,
        render: (_, record) => (
          <Button
            type="link"
            size="small"
            style={{ paddingLeft: 0 }}
            disabled={record.codeVersionId === codeVersionId}
            onClick={() => handleNavigateVersion(record)}
          >
            查看
          </Button>
        ),
      },
    ],
    [codeVersionId, handleNavigateVersion],
  );

  if (!codeVersionId) {
    return (
      <PageContainer
        title="训练代码详情"
        onBack={() => history.push(backPath)}
        breadcrumb={{ items: breadcrumbItems }}
      >
        <Empty description="缺少训练代码版本编号" />
      </PageContainer>
    );
  }

  if (metaLoading && !meta) {
    return (
      <PageContainer
        title="训练代码详情"
        onBack={() => history.push(backPath)}
        breadcrumb={{ items: breadcrumbItems }}
      >
        <div style={{ textAlign: 'center', padding: 80 }}>
          <Spin size="large" />
        </div>
      </PageContainer>
    );
  }

  if (!meta || metaLoadFailed) {
    return (
      <PageContainer
        title="训练代码详情"
        onBack={() => history.push(backPath)}
        breadcrumb={{
          items: [
            {
              title: (
                <a onClick={() => history.push('/task/code/list')}>训练代码</a>
              ),
            },
            { title: '训练代码详情' },
          ],
        }}
      >
        <Empty description="未找到训练代码版本或无权访问" />
      </PageContainer>
    );
  }

  return (
    <PageContainer
      title={title}
      subTitle={
        adminReviewMode
          ? '管理员审核预览（跨 owner，只读）'
          : '查看训练代码版本详情、风险/审批证据与 zip 内源码预览'
      }
      onBack={() => history.push(backPath)}
      breadcrumb={{ items: breadcrumbItems }}
      extra={
        codeAssetId || adminReviewMode ? (
          <div style={{ maxWidth: 640 }}>
            <Space wrap style={{ justifyContent: 'flex-end', width: '100%' }}>
              <Button
                loading={validating}
                onClick={() => void handleValidateVersion()}
              >
                版本校验
              </Button>
              {selectedPath ? (
                <Button
                  icon={<DownloadOutlined />}
                  loading={downloadingFile}
                  onClick={() => void handleDownloadCurrentFile()}
                >
                  下载当前文件
                </Button>
              ) : null}
              <Button
                icon={<DownloadOutlined />}
                loading={downloading}
                onClick={handleDownloadZip}
              >
                下载 ZIP
              </Button>
              {canEditAsset ? (
                <>
                  <Button icon={<EditOutlined />} onClick={handleOpenEditName}>
                    编辑名称
                  </Button>
                  <Popconfirm
                    title="弃用该代码版本？"
                    description="弃用后该版本不可再用于新训练，但历史记录仍保留。"
                    okText="弃用"
                    cancelText="取消"
                    okButtonProps={{ danger: true, loading: deprecating }}
                    onConfirm={handleDeprecate}
                  >
                    <Button danger loading={deprecating}>
                      弃用
                    </Button>
                  </Popconfirm>
                  <Popconfirm
                    title="归档该代码版本？"
                    description="归档后版本将从常规列表隐藏，仍可查看历史。"
                    okText="归档"
                    cancelText="取消"
                    okButtonProps={{ loading: archiving }}
                    onConfirm={handleArchive}
                  >
                    <Button loading={archiving}>归档</Button>
                  </Popconfirm>
                  <Popconfirm
                    title="删除训练代码资产？"
                    description="将软删除整个代码资产（含其下版本）。若已被训练引用或存在打开工作区，删除会失败。"
                    okText="删除"
                    cancelText="取消"
                    okButtonProps={{ danger: true, loading: deleting }}
                    onConfirm={handleDeleteAsset}
                  >
                    <Button danger loading={deleting}>
                      删除资产
                    </Button>
                  </Popconfirm>
                </>
              ) : null}
            </Space>
            <div
              style={{
                marginTop: 8,
                fontSize: 12,
                color: '#8c8c8c',
                lineHeight: 1.7,
                textAlign: 'right',
              }}
            >
              <div>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  弃用
                </Typography.Text>
                ：仅针对当前版本，标记后不可再用于新训练，详情与历史仍可查看。
              </div>
              <div>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  归档
                </Typography.Text>
                ：仅针对当前版本，从常规列表中收起，需要时可继续打开历史详情。
              </div>
              <div>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  删除资产
                </Typography.Text>
                ：删除整个代码资产（含其下全部版本）；若已被训练引用或存在打开中的工作区会失败。
              </div>
            </div>
          </div>
        ) : undefined
      }
    >
      {metaLoading && !meta ? (
        <div style={{ textAlign: 'center', padding: 80 }}>
          <Spin size="large" />
        </div>
      ) : (
        <>
          <Card style={{ marginBottom: 16 }}>
            <Descriptions size="small" column={2}>
              <Descriptions.Item label="代码名称">
                {displayName}
              </Descriptions.Item>
              <Descriptions.Item label="zip 文件名">
                {meta?.fileName || '-'}
              </Descriptions.Item>
              <Descriptions.Item label="训练方案">
                <Typography.Text code style={{ fontSize: 12 }}>
                  {meta?.trainingProfile || '-'}
                </Typography.Text>
              </Descriptions.Item>
              {meta?.entryScript && (
                <Descriptions.Item label="入口脚本">
                  <Typography.Text code style={{ fontSize: 12 }}>
                    {meta.entryScript}
                  </Typography.Text>
                </Descriptions.Item>
              )}
              <Descriptions.Item label="审核状态">
                {approvalTag(meta?.approvalStatus)}
              </Descriptions.Item>
              <Descriptions.Item label="上传时间">
                {formatDisplayDateTime(meta?.createdAt || meta?.submittedAt)}
              </Descriptions.Item>
              <Descriptions.Item label="就绪状态">
                {statusTag(meta?.status)}
              </Descriptions.Item>
              <Descriptions.Item label="校验状态">
                {meta?.validationStatus ? (
                  <Tag
                    color={
                      meta.validationStatus === 'PASSED' ? 'success' : 'default'
                    }
                  >
                    {meta.validationStatus}
                  </Tag>
                ) : (
                  '-'
                )}
              </Descriptions.Item>
              <Descriptions.Item label="风险等级">
                {meta?.riskLevel ? (
                  <Tag color={meta.riskLevel === 'LOW' ? 'success' : 'warning'}>
                    {meta.riskLevel}
                  </Tag>
                ) : (
                  '-'
                )}
              </Descriptions.Item>
              {meta?.riskAssessment?.status && (
                <Descriptions.Item label="风险任务状态">
                  <Tag>{meta.riskAssessment.status}</Tag>
                </Descriptions.Item>
              )}
              <Descriptions.Item label="包大小">
                {formatBytes(meta?.sizeBytes)}
              </Descriptions.Item>
              <Descriptions.Item label="版本编号" span={2}>
                <Typography.Text copyable code style={{ fontSize: 12 }}>
                  {codeVersionId}
                </Typography.Text>
              </Descriptions.Item>
              {meta?.artifactSha256 && (
                <Descriptions.Item label="artifactSha256" span={2}>
                  <Typography.Text code style={{ fontSize: 12 }}>
                    {meta.artifactSha256}
                  </Typography.Text>
                </Descriptions.Item>
              )}
              {meta?.consumerManifest?.validationRunId && (
                <Descriptions.Item label="validationRunId" span={2}>
                  <Typography.Text copyable code style={{ fontSize: 12 }}>
                    {meta.consumerManifest.validationRunId}
                  </Typography.Text>
                </Descriptions.Item>
              )}
              {meta?.consumerManifest?.approvalRecordId && (
                <Descriptions.Item label="approvalRecordId" span={2}>
                  <Typography.Text copyable code style={{ fontSize: 12 }}>
                    {meta.consumerManifest.approvalRecordId}
                  </Typography.Text>
                </Descriptions.Item>
              )}
              {meta?.consumerManifest?.approvalSource && (
                <Descriptions.Item label="approvalSource">
                  <Tag>{meta.consumerManifest.approvalSource}</Tag>
                </Descriptions.Item>
              )}
              {meta?.consumerManifest?.validationPolicyVersion && (
                <Descriptions.Item label="validationPolicyVersion">
                  <Typography.Text code style={{ fontSize: 12 }}>
                    {meta.consumerManifest.validationPolicyVersion}
                  </Typography.Text>
                </Descriptions.Item>
              )}
              {meta?.runtime && (
                <Descriptions.Item label="runtime">
                  <Typography.Text code style={{ fontSize: 12 }}>
                    {meta.runtime}
                  </Typography.Text>
                </Descriptions.Item>
              )}
              {meta?.purpose && (
                <Descriptions.Item label="purpose">
                  <Typography.Text code style={{ fontSize: 12 }}>
                    {meta.purpose}
                  </Typography.Text>
                </Descriptions.Item>
              )}
              {meta?.trainingType && (
                <Descriptions.Item label="trainingType">
                  <Typography.Text code style={{ fontSize: 12 }}>
                    {meta.trainingType}
                  </Typography.Text>
                </Descriptions.Item>
              )}
              {meta?.riskAssessment?.findings?.length ? (
                <Descriptions.Item label="风险 Findings" span={2}>
                  <Space wrap>
                    {meta.riskAssessment.findings.slice(0, 8).map((item) => (
                      <Tag
                        key={`${item.ruleId || 'rule'}-${item.filePath || ''}-${item.lineStart ?? ''}-${item.category || ''}-${item.description || ''}`}
                      >
                        {item.ruleId || item.category || 'finding'}
                        {item.filePath ? ` @ ${item.filePath}` : ''}
                      </Tag>
                    ))}
                  </Space>
                </Descriptions.Item>
              ) : null}
              {meta?.riskAssessment?.reasonCode && (
                <Descriptions.Item label="风险原因" span={2}>
                  <Typography.Text code style={{ fontSize: 12 }}>
                    {meta.riskAssessment.reasonCode}
                  </Typography.Text>
                </Descriptions.Item>
              )}
              {meta?.remark && (
                <Descriptions.Item label="备注" span={2}>
                  {meta.remark}
                </Descriptions.Item>
              )}
            </Descriptions>
          </Card>

          {canEditAsset ? (
            <Card title="资产版本" style={{ marginBottom: 16 }}>
              <Table<CodeVersionListItem>
                rowKey="codeVersionId"
                size="small"
                loading={versionsLoading}
                columns={versionColumns}
                dataSource={assetVersions}
                pagination={false}
                rowClassName={(record) =>
                  record.codeVersionId === codeVersionId
                    ? 'ant-table-row-selected'
                    : ''
                }
              />
            </Card>
          ) : null}

          {canEditAsset ? (
            <div style={{ marginBottom: 12 }}>
              <Space wrap>
                <Button
                  type={hasDraft ? 'primary' : 'default'}
                  icon={<SaveOutlined />}
                  loading={saving}
                  disabled={!hasDraft}
                  onClick={() => handleSaveAndPublish()}
                  style={
                    hasDraft
                      ? undefined
                      : { color: 'rgba(0,0,0,0.25)', borderColor: '#d9d9d9' }
                  }
                >
                  保存并发布
                </Button>
                {workspaceDraftOpen ? (
                  <Button
                    loading={validating}
                    onClick={() => void handleValidateWorkspace()}
                  >
                    校验工作区
                  </Button>
                ) : null}
                <Popconfirm
                  title="放弃工作区草稿？"
                  description="将丢弃未发布的文件增删改与内容修改，恢复为当前版本基线。"
                  okText="放弃"
                  cancelText="取消"
                  okButtonProps={{ danger: true, loading: abandoning }}
                  disabled={!hasDraft}
                  onConfirm={handleAbandonWorkspace}
                >
                  {/* span：避免 disabled 按钮被 Popconfirm 包住后样式/点击异常 */}
                  <span>
                    <Button
                      danger={hasDraft}
                      loading={abandoning}
                      disabled={!hasDraft}
                      style={
                        hasDraft
                          ? undefined
                          : {
                              color: 'rgba(0,0,0,0.25)',
                              borderColor: '#d9d9d9',
                              background: '#f5f5f5',
                            }
                      }
                    >
                      放弃工作区
                    </Button>
                  </span>
                </Popconfirm>
              </Space>
              {hasDraft ? (
                <div
                  style={{
                    marginTop: 6,
                    fontSize: 12,
                    color: '#ad6800',
                    lineHeight: 1.5,
                  }}
                >
                  {workspaceDraftOpen
                    ? '当前为未发布的工作区草稿（含增删改与内容修改），正式生效需「保存并发布」。'
                    : '当前文件有未发布的内容修改，正式生效需「保存并发布」。'}
                </div>
              ) : null}
            </div>
          ) : null}

          <Row gutter={16}>
            <Col xs={24} lg={8}>
              <Card
                title="代码目录"
                style={{ marginBottom: 16 }}
                extra={
                  canEditAsset ? (
                    <Space wrap size={4}>
                      <Button
                        size="small"
                        icon={<FileAddOutlined />}
                        disabled={fileOpLoading}
                        onClick={() => {
                          newFileForm.resetFields();
                          setNewFileOpen(true);
                        }}
                      >
                        新建文件
                      </Button>
                      <Button
                        size="small"
                        disabled={!selectedPath || fileOpLoading}
                        onClick={handleOpenRenameFile}
                      >
                        重命名
                      </Button>
                      <Popconfirm
                        title="从工作区草稿删除该文件？"
                        description="删除后需「保存并发布」才会成为新版本；也可通过上方「放弃工作区」恢复。"
                        okText="删除"
                        cancelText="取消"
                        okButtonProps={{ danger: true }}
                        disabled={!selectedPath || fileOpLoading}
                        onConfirm={handleDeleteFile}
                      >
                        <Button
                          size="small"
                          danger
                          disabled={!selectedPath || fileOpLoading}
                        >
                          删除文件
                        </Button>
                      </Popconfirm>
                      <Button
                        size="small"
                        icon={<ReloadOutlined />}
                        loading={filesLoading}
                        onClick={() => loadFiles()}
                      >
                        刷新
                      </Button>
                    </Space>
                  ) : undefined
                }
              >
                {filesLoading ? (
                  <div style={{ textAlign: 'center', padding: 48 }}>
                    <Spin tip="加载目录树…" />
                  </div>
                ) : fileTreeData.length ? (
                  <div
                    style={{
                      maxHeight: 560,
                      overflow: 'auto',
                      paddingRight: 4,
                    }}
                  >
                    <Tree.DirectoryTree
                      treeData={fileTreeData}
                      selectedKeys={selectedPath ? [selectedPath] : []}
                      expandedKeys={expandedFileKeys}
                      onExpand={(keys) => setExpandedFileKeys(keys)}
                      expandAction="click"
                      onSelect={(keys, info) => {
                        if (!info.node.isLeaf) return;
                        const path = String(keys[0] || '');
                        if (path) handleSelectFile(path);
                      }}
                    />
                  </div>
                ) : (
                  <Empty
                    image={Empty.PRESENTED_IMAGE_SIMPLE}
                    description={
                      filesLoadError ||
                      '暂无可预览的代码文件（依赖 V2 目录树接口）'
                    }
                  />
                )}
              </Card>
            </Col>
            <Col xs={24} lg={16}>
              <Card
                title={
                  <Space>
                    <CodeOutlined />
                    代码预览
                    {previewFileName ? ` · ${previewFileName}` : ''}
                  </Space>
                }
              >
                {previewReadError ? (
                  <Alert
                    type="error"
                    showIcon
                    message="文件读取失败"
                    description={previewReadError}
                    action={
                      <Button
                        onClick={() =>
                          selectedPath &&
                          void loadPreview(selectedPath, !workspaceDraftOpen)
                        }
                      >
                        重试文件
                      </Button>
                    }
                  />
                ) : null}
                {!selectedPath && !filesLoading && (
                  <Empty
                    description="请从左侧选择文件"
                    style={{ padding: 48 }}
                  />
                )}
                {selectedPath && previewLoading && (
                  <div style={{ textAlign: 'center', padding: 48 }}>
                    <Spin tip="加载代码内容…" />
                  </div>
                )}
                {selectedPath &&
                  !previewLoading &&
                  !previewReadError &&
                  (adminReviewMode ? (
                    <pre
                      style={{
                        padding: 12,
                        background: '#fafafa',
                        border: '1px solid #f0f0f0',
                        borderRadius: 6,
                        maxHeight: 520,
                        overflow: 'auto',
                        whiteSpace: 'pre-wrap',
                        wordBreak: 'break-word',
                      }}
                    >
                      {previewContent}
                    </pre>
                  ) : (
                    <>
                      <Alert
                        type="info"
                        showIcon
                        message="可在此编辑源码。修改后使用上方「保存并发布」生成新版本；不想保留草稿时用「放弃工作区」。"
                        style={{ marginBottom: 12 }}
                      />
                      <CodeEditor
                        value={previewContent}
                        fileName={previewFileName}
                        onChange={setPreviewContent}
                        readOnly={!canEditAsset}
                        minHeight="360px"
                        maxHeight="520px"
                      />
                      <Space wrap style={{ marginTop: 12 }}>
                        <Button
                          size="small"
                          icon={<CopyOutlined />}
                          onClick={handleCopyPreviewContent}
                        >
                          复制
                        </Button>
                        <Button
                          size="small"
                          icon={<UndoOutlined />}
                          disabled={!previewDirty}
                          onClick={handleResetPreviewContent}
                        >
                          恢复原始
                        </Button>
                        <Button
                          size="small"
                          icon={<CodeOutlined />}
                          onClick={() => setCodePreviewVisible(true)}
                        >
                          弹窗查看
                        </Button>
                      </Space>
                    </>
                  ))}
                {selectedPath && !previewLoading && !previewContent && (
                  <Empty
                    image={Empty.PRESENTED_IMAGE_SIMPLE}
                    description="该文件暂无文本预览内容（可能为二进制文件）"
                  />
                )}
              </Card>
            </Col>
          </Row>
        </>
      )}

      <Modal
        title="编辑代码名称"
        open={editNameOpen}
        confirmLoading={editNameSubmitting}
        okText="保存"
        cancelText="取消"
        onOk={handleEditNameSubmit}
        onCancel={() => setEditNameOpen(false)}
        destroyOnClose
      >
        <Form form={editNameForm} layout="vertical" preserve={false}>
          <Form.Item
            name="name"
            label="代码名称"
            rules={[{ required: true, message: '请输入代码名称' }]}
          >
            <Input placeholder="请输入代码名称" maxLength={128} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="新建文件"
        open={newFileOpen}
        confirmLoading={fileOpLoading}
        okText="创建"
        cancelText="取消"
        onOk={handleCreateFile}
        onCancel={() => setNewFileOpen(false)}
        destroyOnClose
      >
        <Form form={newFileForm} layout="vertical" preserve={false}>
          <Form.Item
            name="path"
            label="文件路径"
            rules={[{ required: true, message: '请输入文件路径' }]}
            extra="相对 zip 根目录的路径，例如 src/train.py"
          >
            <Input placeholder="例如 src/train.py" />
          </Form.Item>
          <Form.Item
            name="content"
            label="代码内容"
            extra="可选。填写该文件的源码正文；留空则创建空文件。"
          >
            <Input.TextArea
              rows={8}
              placeholder="在此粘贴或输入文件源码，例如 Python / Shell 脚本内容"
              style={{ fontFamily: 'monospace', fontSize: 13 }}
            />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="重命名文件"
        open={renameFileOpen}
        confirmLoading={fileOpLoading}
        okText="确定"
        cancelText="取消"
        onOk={handleRenameFile}
        onCancel={() => setRenameFileOpen(false)}
        destroyOnClose
      >
        <Form form={renameFileForm} layout="vertical" preserve={false}>
          <Form.Item label="原路径">
            <Typography.Text code>{selectedPath || '-'}</Typography.Text>
          </Form.Item>
          <Form.Item
            name="targetPath"
            label="新路径"
            rules={[{ required: true, message: '请输入新路径' }]}
          >
            <Input placeholder="请输入新文件路径" />
          </Form.Item>
        </Form>
      </Modal>

      {codePreviewVisible && previewContent && (
        <CodePreview
          visible={codePreviewVisible}
          codeText={previewContent}
          originalCodeText={originalPreviewContent}
          fileName={previewFileName}
          onContentChange={setPreviewContent}
          onSave={handleSaveAndPublish}
          saving={saving}
          onClose={() => setCodePreviewVisible(false)}
        />
      )}
    </PageContainer>
  );
};

export default TrainingCodeDetail;
