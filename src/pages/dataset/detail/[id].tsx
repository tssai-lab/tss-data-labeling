import { EditOutlined, PlusOutlined } from '@ant-design/icons';
import { PageContainer } from '@ant-design/pro-components';
import { history, useParams, useSearchParams } from '@umijs/max';
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Empty,
  Form,
  Input,
  Modal,
  message,
  Popconfirm,
  Space,
  Spin,
  Table,
  Tabs,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import ZipReadmePanel from '@/components/ZipReadmePanel';
import PointCloudPreviewPanel, {
  type PointCloudPreviewPanelRef,
} from '@/pages/dataset/components/point-cloud/PointCloudPreviewPanel';
import { resolveDatasetVersionId } from '@/services/dataset';
import {
  abandonDatasetWorkspace,
  createDatasetVersion,
  createOrOpenDatasetWorkspace,
  deleteDataset,
  deleteDatasetVersion,
  downloadDatasetVersion,
  downloadObjectWithBrowser,
  extractActiveImportJobId,
  fetchDatasetDetail,
  getDatasetVersionAllocation,
  getDatasetWorkspace,
  switchDatasetCurrentVersion,
  updateDatasetVersion,
  updateDatasetVersionStatus,
  type V2DatasetWorkspace,
} from '@/services/platform';
import { getApiErrorMessage } from '@/utils/apiError';
import {
  DATASET_VERSION_DESC_PLACEHOLDER,
  DATASET_VERSION_FORMAT_HINT,
  datasetVersionDescFormRules,
  datasetVersionFormRules,
  suggestNextDatasetVersion,
} from '@/utils/datasetVersion';
import {
  isZipBackedDatasetVersion,
  supportsDatasetWorkspaceEdit,
  type WorkspaceEditableDatasetType,
} from '@/utils/datasetWorkspace';
import { beginDownloadProgress } from '@/utils/downloadProgressToast';
import { requireMatchingWorkspace, workspaceReadFailure } from '@/utils/datasetWorkspaceRead';
import { formatDisplayDateTime } from '@/utils/formatDateTime';
import { loadImportJobId, saveImportJobId } from '@/utils/importJobStorage';
import DatasetPreviewPanel from '../components/DatasetPreviewPanel';
import MultimodalImportBanner from '../components/MultimodalImportBanner';
import MultimodalPreviewPanel from '../components/MultimodalPreviewPanel';
import MultimodalWorkspacePanel from '../components/MultimodalWorkspacePanel';
import TableEllipsisCell from '../components/TableEllipsisCell';
import {
  buildDraftContext,
  buildVersionLabelCandidates,
  DATASET_TYPE_COLOR,
  DATASET_TYPE_LABEL,
  isImportDraftVersion,
  isWorkspaceDraftVersion,
  normalizeDatasetVersionInput,
  resolveActiveDraftId,
} from './datasetVersionPresentation';

const DatasetDetail: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const [searchParams] = useSearchParams();
  const previewSectionRef = useRef<HTMLDivElement>(null);
  const [datasetInfo, setDatasetInfo] = useState<
    | (API.DatasetDetail & {
        defaultVersionId?: string;
        latestDraftVersionId?: string | null;
        importJobId?: string | null;
        importStatus?: string | null;
        importProgress?: number | null;
        importErrorMessage?: string | null;
        currentVersionId?: string;
        editSessionId?: string | null;
        workspaceId?: string | null;
        workspaceRevision?: number | null;
        hasDraft?: boolean;
        displayStatus?: string | null;
      })
    | null
  >(null);
  const [loading, setLoading] = useState(true);
  const [detailError, setDetailError] = useState('');
  const [workspaceReadError, setWorkspaceReadError] = useState('');
  const detailReadSequence = useRef(0);
  const detailReadActive = useRef(true);
  const detailRouteRef = useRef(id);
  detailRouteRef.current = id;
  const detailQueryRef = useRef(searchParams.toString());
  detailQueryRef.current = searchParams.toString();
  const [previewVersionId, setPreviewVersionId] = useState<string>();
  /** Hugging Face 风格详情 Tabs */
  const [detailTab, setDetailTab] = useState<string>('readme');

  const [versionModalOpen, setVersionModalOpen] = useState(false);
  const [versionModalMode, setVersionModalMode] = useState<
    'create' | 'editRemark' | 'editWorkspace'
  >('create');
  const [versionModalLoading, setVersionModalLoading] = useState(false);
  const [editingVersion, setEditingVersion] =
    useState<API.DatasetVersionDetail | null>(null);
  /** 进入工作区前的 READY 父版本 ID，用于放弃后恢复预览 */
  const [workspaceEditSourceVersionId, setWorkspaceEditSourceVersionId] =
    useState<string>();
  /** V2 活动版本工作区（取代 edit-session / draftVersionId 面板） */
  const [activeWorkspace, setActiveWorkspace] = useState<{
    datasetId: string;
    workspaceId: string;
    workspaceRevision: number;
    targetVersionId?: string;
    targetVersionLabel?: string;
    baseVersionLabel?: string;
  } | null>(null);
  const activeWorkspaceRef = useRef(activeWorkspace);
  activeWorkspaceRef.current = activeWorkspace;
  const [versionForm] = Form.useForm();

  const applyWorkspaceState = useCallback((ws: V2DatasetWorkspace) => {
    setActiveWorkspace({
      datasetId: ws.datasetId,
      workspaceId: ws.workspaceId,
      workspaceRevision: ws.workspaceRevision,
      targetVersionId: ws.targetVersion?.versionId,
      targetVersionLabel: ws.targetVersion?.versionLabel,
      baseVersionLabel: ws.baseVersion?.versionLabel,
    });
    if (ws.targetVersion?.versionId) {
      setPreviewVersionId(ws.targetVersion.versionId);
    }
    const jobId = extractActiveImportJobId(ws);
    if (jobId && ws.datasetId) {
      saveImportJobId(ws.datasetId, jobId);
      setDatasetInfo((prev) =>
        prev
          ? {
              ...prev,
              importJobId: prev.importJobId || jobId,
              importStatus:
                prev.importStatus ||
                (ws.activeOperation?.status as string | undefined) ||
                'RUNNING',
            }
          : prev,
      );
    }
  }, []);

  const existingVersionNames = useMemo(
    () => datasetInfo?.versions.map((v) => v.version).filter(Boolean) ?? [],
    [datasetInfo],
  );

  const loadDetail = useCallback(async () => {
    const query = searchParams.toString();
    if (!id || !detailReadActive.current || detailRouteRef.current !== id ||
        detailQueryRef.current !== query) return;
    const request = ++detailReadSequence.current;
    const isCurrent = () => detailReadActive.current &&
      detailRouteRef.current === id && detailQueryRef.current === query &&
      request === detailReadSequence.current;
    setLoading(true);
    try {
      const res = await fetchDatasetDetail(id, { skipErrorHandler: true });
      if (!isCurrent()) return;
      const detail =
        (res?.data as
          | (API.DatasetDetail & {
              defaultVersionId?: string;
              workspaceId?: string | null;
              editSessionId?: string | null;
            })
          | undefined) ?? null;
      if (!detail || detail.id !== id || !Array.isArray(detail.versions) ||
          ('success' in res && res.success === false) || ('errorCode' in res && res.errorCode)) {
        throw new Error('数据集详情回执不完整或资产不匹配，请重试');
      }
      setDetailError('');
      setDatasetInfo(
        detail
          ? {
              ...detail,
              importJobId:
                detail.importJobId ||
                (detail.id ? loadImportJobId(detail.id) : null) ||
                null,
            }
          : null,
      );
      const assetId = detail?.id;
      const queryVersionId = searchParams.get('versionId') ?? undefined;
      const importDraftId = detail?.latestDraftVersionId ?? undefined;
      const defaultVersionId =
        (queryVersionId && queryVersionId !== assetId
          ? queryVersionId
          : undefined) ??
        detail?.defaultVersionId ??
        resolveDatasetVersionId(detail?.latestVersion, assetId) ??
        detail?.versions
          .map((v) => resolveDatasetVersionId(v, assetId))
          .find(Boolean) ??
        (detail?.type === 'MULTIMODAL' &&
        importDraftId &&
        ['PENDING', 'RUNNING', 'FAILED'].includes(detail?.importStatus ?? '')
          ? importDraftId
          : undefined);

      const workspaceId =
        detail.workspaceId || detail.editSessionId ||
        // 列表补全暂缺编号时仍核实同资产的已知工作区，不直接解锁旧编辑状态。
        (activeWorkspaceRef.current?.datasetId === id
          ? activeWorkspaceRef.current.workspaceId : undefined);
      if (
        workspaceId &&
        detail?.type &&
        supportsDatasetWorkspaceEdit(detail.type)
      ) {
        try {
          const ws = await getDatasetWorkspace(workspaceId, {
            skipErrorHandler: true,
          });
          if (!isCurrent()) return;
          requireMatchingWorkspace(ws, workspaceId, id);
          applyWorkspaceState(ws);
          setWorkspaceReadError('');
        } catch (error) {
          if (!isCurrent()) return;
          const failure = workspaceReadFailure(error);
          setWorkspaceReadError(failure.message);
          // 网络失败不代表草稿已失效；仅收回明确不可访问的本地编辑引用。
          // 失败时由错误页阻止旧状态继续编辑，不执行服务器放弃或删除。
          if (failure.clearLocal) {
            setActiveWorkspace(null);
            setPreviewVersionId(defaultVersionId);
          }
        }
      } else {
        setWorkspaceReadError('');
        // 列表尚未带回 workspaceId 时不冲掉刚创建的本地工作区
        setPreviewVersionId((prev) => {
          if (
            prev &&
            detail?.versions.some(
              (v) => (resolveDatasetVersionId(v, assetId) ?? v.id) === prev,
            )
          ) {
            return prev;
          }
          return defaultVersionId;
        });
      }
    } catch (error: unknown) {
      if (isCurrent()) setDetailError(getApiErrorMessage(error, '数据集详情读取失败，请重试。'));
    } finally {
      if (isCurrent()) setLoading(false);
    }
  }, [id, searchParams, applyWorkspaceState]);

  const previewPanelRef = useRef<PointCloudPreviewPanelRef>(null);

  useEffect(() => {
    setDatasetInfo(null);
    setPreviewVersionId(undefined);
    setActiveWorkspace(null);
    setDetailError('');
    setWorkspaceReadError('');
    setVersionModalOpen(false);
    setEditingVersion(null);
    setWorkspaceEditSourceVersionId(undefined);
  }, [id]);

  useEffect(() => {
    detailReadActive.current = true;
    void loadDetail();
    return () => {
      detailReadActive.current = false;
      ++detailReadSequence.current;
    };
  }, [loadDetail]);

  const handleDelete = async () => {
    if (!id) {
      return;
    }
    try {
      await deleteDataset(id);
      message.success('删除成功');
      history.push('/dataset/list');
    } catch (error: any) {
      message.error(error?.info?.message || error?.message || '删除失败');
    }
  };

  const handleDownload = async (
    versionId?: string,
    storagePath?: string,
    fileName?: string,
  ) => {
    if (versionId) {
      const progress = beginDownloadProgress();
      try {
        await downloadDatasetVersion(versionId, fileName, {
          skipErrorHandler: true,
          onProgress: progress.update,
        });
        progress.close();
        message.success('已交给浏览器下载');
      } catch (error: unknown) {
        progress.close();
        const tip = getApiErrorMessage(error, '下载失败');
        if (tip === '已取消下载') return;
        message.error(tip);
      }
      return;
    }
    if (!storagePath) {
      message.warning('当前版本没有可下载文件');
      return;
    }
    try {
      await downloadObjectWithBrowser(storagePath, fileName);
      message.success('已交给浏览器下载');
    } catch (error: unknown) {
      message.error(getApiErrorMessage(error, '下载失败'));
    }
  };

  const handleUploadNewVersion = () => {
    if (!datasetInfo || !id) return;
    const params = new URLSearchParams({
      assetId: id,
      datasetName: datasetInfo.name,
      type: datasetInfo.type,
    });
    history.push(`/dataset/upload?${params.toString()}`);
  };

  const openCreateVersion = () => {
    setVersionModalMode('create');
    setEditingVersion(null);
    versionForm.setFieldsValue({
      version: suggestNextDatasetVersion(existingVersionNames),
      remark: '',
    });
    setVersionModalOpen(true);
  };

  const openEditRemark = (record: API.DatasetVersionDetail) => {
    setVersionModalMode('editRemark');
    setEditingVersion(record);
    versionForm.setFieldsValue({
      remark: record.remark ?? '',
    });
    setVersionModalOpen(true);
  };

  const scrollToWorkspace = () => {
    setDetailTab('files');
    requestAnimationFrame(() => {
      previewSectionRef.current?.scrollIntoView({
        behavior: 'smooth',
        block: 'start',
      });
    });
  };

  const draftContext = useMemo(
    () => buildDraftContext(datasetInfo),
    [datasetInfo],
  );

  const openEditWorkspaceModal = (record: API.DatasetVersionDetail) => {
    const sourceId =
      resolveDatasetVersionId(record, datasetInfo?.id) ?? record.id;
    setWorkspaceEditSourceVersionId(sourceId);
    setVersionModalMode('editWorkspace');
    setEditingVersion(record);
    versionForm.setFieldsValue({
      version: suggestNextDatasetVersion(existingVersionNames),
      remark: '',
    });
    setVersionModalOpen(true);
  };

  const handleEditCurrentVersion = async (record: API.DatasetVersionDetail) => {
    const versionId =
      resolveDatasetVersionId(record, datasetInfo?.id) ?? record.id;
    if (!versionId || !datasetInfo) return;

    if (!supportsDatasetWorkspaceEdit(datasetInfo.type)) {
      Modal.info({
        title: '创建/继续版本工作区',
        content:
          '当前数据集类型暂不支持在版本内增删。如需更新数据，请使用「上传新版本」替换整包。',
      });
      return;
    }

    if (
      !isWorkspaceDraftVersion(record, draftContext) &&
      record.status === 'READY' &&
      !isZipBackedDatasetVersion(record)
    ) {
      Modal.info({
        title: '创建/继续版本工作区',
        content:
          '当前版本为单文件数据集，无法创建版本工作区。请先通过「上传新版本」上传为 zip 格式后再进行增删编辑。',
      });
      return;
    }

    const openExistingWorkspace = async (sourceId?: string) => {
      if (sourceId) {
        setWorkspaceEditSourceVersionId(sourceId);
      }
      const knownId =
        datasetInfo.workspaceId || datasetInfo.editSessionId || undefined;
      try {
        const ws = knownId
          ? await getDatasetWorkspace(knownId, { skipErrorHandler: true })
          : await createOrOpenDatasetWorkspace(
              datasetInfo.id,
              sourceId ? { baseVersionId: sourceId } : null,
              { skipErrorHandler: true },
            );
        const baseId = ws.baseVersion?.versionId;
        if (sourceId && baseId && baseId !== sourceId) {
          message.warning(
            `当前活动工作区基线不是所选版本（基线 ${ws.baseVersion?.versionLabel || baseId}）。请先发布或「放弃工作区」后，再基于所选版本重新创建。`,
          );
          applyWorkspaceState(ws);
          scrollToWorkspace();
          return;
        }
        applyWorkspaceState(ws);
        scrollToWorkspace();
      } catch (error: any) {
        message.error(getApiErrorMessage(error, '打开版本工作区失败'));
      }
    };

    if (isWorkspaceDraftVersion(record, draftContext) || activeWorkspace) {
      const parentId = record.parentVersionId
        ? (resolveDatasetVersionId(
            datasetInfo.versions.find(
              (v) =>
                v.id === record.parentVersionId ||
                resolveDatasetVersionId(v, datasetInfo.id) ===
                  record.parentVersionId,
            ),
            datasetInfo.id,
          ) ?? record.parentVersionId)
        : workspaceEditSourceVersionId;
      message.info('已切入现有版本工作区，可继续编辑或「放弃工作区」后重开。');
      await openExistingWorkspace(parentId);
      return;
    }

    if (record.status && record.status !== 'READY') {
      message.warning('仅准备完成的正式版本可在线编辑');
      return;
    }

    const existingWorkspaceDraft = datasetInfo.versions.find((item) =>
      isWorkspaceDraftVersion(item, draftContext),
    );
    const hasActiveWorkspace =
      !!datasetInfo.workspaceId ||
      !!datasetInfo.editSessionId ||
      !!datasetInfo.hasDraft ||
      !!existingWorkspaceDraft ||
      !!activeWorkspace;

    if (hasActiveWorkspace) {
      const parentId = existingWorkspaceDraft?.parentVersionId
        ? (resolveDatasetVersionId(
            datasetInfo.versions.find(
              (v) =>
                v.id === existingWorkspaceDraft.parentVersionId ||
                resolveDatasetVersionId(v, datasetInfo.id) ===
                  existingWorkspaceDraft.parentVersionId,
            ),
            datasetInfo.id,
          ) ?? existingWorkspaceDraft.parentVersionId)
        : versionId;
      message.info(
        '该数据集已有未发布的版本工作区（同一资产只能有一个），已为您切入；可继续编辑或「放弃工作区」后重开。',
      );
      await openExistingWorkspace(parentId);
      return;
    }

    if (
      datasetInfo.type === 'MULTIMODAL' &&
      datasetInfo.latestDraftVersionId &&
      isImportDraftVersion(
        datasetInfo.versions.find(
          (item) => item.id === datasetInfo.latestDraftVersionId,
        ),
        draftContext,
      )
    ) {
      message.warning('当前有版本正在导入，请等待导入完成后再创建工作区');
      return;
    }

    openEditWorkspaceModal(record);
  };

  const handleCancelWorkspaceEdit = async () => {
    if (!activeWorkspace || !datasetInfo) return;
    try {
      await abandonDatasetWorkspace(
        activeWorkspace.workspaceId,
        activeWorkspace.workspaceRevision,
        { skipErrorHandler: true },
      );
      message.success('已放弃版本工作区');
      const restoreId = workspaceEditSourceVersionId;
      setWorkspaceEditSourceVersionId(undefined);
      setActiveWorkspace(null);
      await loadDetail();
      if (restoreId) {
        setPreviewVersionId(restoreId);
      }
    } catch (error: any) {
      message.error(getApiErrorMessage(error, '放弃工作区失败'));
    }
  };

  const submitVersionModal = async () => {
    if (!id) return;
    try {
      const values = await versionForm.validateFields();
      setVersionModalLoading(true);
      const remark = values.remark?.trim();
      if (versionModalMode === 'create') {
        const version = values.version.trim();
        await createDatasetVersion(
          { assetId: id, version, remark },
          { skipErrorHandler: true },
        );
        message.success('版本记录已创建，请通过「上传新版本」绑定数据文件');
      } else if (versionModalMode === 'editWorkspace') {
        const version = normalizeDatasetVersionInput(values.version.trim());
        const sourceId = workspaceEditSourceVersionId;
        if (!sourceId) {
          message.error('缺少源版本信息，请重新点击「创建/继续版本工作区」');
          return;
        }
        if (!datasetInfo?.id) {
          message.error('数据集信息缺失');
          return;
        }
        const assetId = datasetInfo.id;
        // 已有活动工作区：继续，不二次改名
        if (
          datasetInfo.workspaceId ||
          datasetInfo.editSessionId ||
          datasetInfo.hasDraft ||
          resolveActiveDraftId(datasetInfo, draftContext)
        ) {
          const knownId =
            datasetInfo.workspaceId || datasetInfo.editSessionId || undefined;
          const ws = knownId
            ? await getDatasetWorkspace(knownId, { skipErrorHandler: true })
            : await createOrOpenDatasetWorkspace(
                assetId,
                { baseVersionId: sourceId },
                { skipErrorHandler: true },
              );
          const baseId = ws.baseVersion?.versionId;
          if (baseId && baseId !== sourceId) {
            message.warning(
              `当前活动工作区基线不是所选版本（基线 ${ws.baseVersion?.versionLabel || baseId}）。请先发布或「放弃工作区」后，再基于所选版本重新创建。`,
            );
            applyWorkspaceState(ws);
            setWorkspaceEditSourceVersionId(sourceId);
            setVersionModalOpen(false);
            scrollToWorkspace();
            return;
          }
          applyWorkspaceState(ws);
          setWorkspaceEditSourceVersionId(sourceId);
          setVersionModalOpen(false);
          message.success(
            `已切入现有版本工作区${
              ws.targetVersion?.versionLabel
                ? `（${ws.targetVersion.versionLabel}）`
                : ''
            }`,
          );
          scrollToWorkspace();
          return;
        }

        const candidates = buildVersionLabelCandidates(
          version,
          existingVersionNames,
        );
        let ws: V2DatasetWorkspace | undefined;
        let applied = version;
        let fallbackFrom: string | undefined;
        let lastError: unknown;
        for (let i = 0; i < candidates.length; i += 1) {
          const label = candidates[i];
          try {
            // 契约推荐：创建前用 version-allocation 预检标签
            try {
              const alloc = await getDatasetVersionAllocation(assetId, label, {
                skipErrorHandler: true,
              });
              if (
                alloc?.requestedVersionLabelAvailable === false &&
                alloc.defaultVersionLabel
              ) {
                // 预检占用时跳到默认或下一候选
                if (i === 0 && alloc.defaultVersionLabel !== label) {
                  message.info(
                    `「${label}」不可用（${alloc.unavailableReason || '占用'}），将尝试 ${alloc.defaultVersionLabel}`,
                  );
                }
              }
            } catch {
              // 预检失败不阻断创建
            }
            ws = await createOrOpenDatasetWorkspace(
              assetId,
              { versionLabel: label, baseVersionId: sourceId },
              { skipErrorHandler: true },
            );
            applied = ws.targetVersion?.versionLabel || label;
            fallbackFrom =
              i === 0 ? undefined : normalizeDatasetVersionInput(version);
            break;
          } catch (error) {
            lastError = error;
          }
        }
        if (!ws) {
          throw lastError instanceof Error
            ? lastError
            : new Error(
                getApiErrorMessage(
                  lastError,
                  `版本号「${version}」及候选均不可用，请换更大号后重试`,
                ),
              );
        }
        applyWorkspaceState(ws);
        setWorkspaceEditSourceVersionId(sourceId);
        setVersionModalOpen(false);
        if (fallbackFrom) {
          message.warning(
            `「${fallbackFrom}」已被占用。已改用 ${applied} 创建版本工作区。`,
          );
        } else {
          message.success(
            `已创建版本工作区 ${
              ws.targetVersion?.versionLabel || applied
            }，可在下方增删文件或样本`,
          );
        }
        await loadDetail();
        scrollToWorkspace();
        return;
      } else if (editingVersion) {
        const remark = values.remark?.trim();
        await updateDatasetVersion(
          editingVersion.id,
          {
            version: editingVersion.version,
            versionLabel: editingVersion.version,
            remark,
            description: remark,
          },
          { skipErrorHandler: true },
        );
        message.success('版本描述已更新');
      }
      setVersionModalOpen(false);
      await loadDetail();
    } catch (error: any) {
      if (error?.errorFields) return;
      message.error(getApiErrorMessage(error, '操作失败'));
    } finally {
      setVersionModalLoading(false);
    }
  };

  const handleDeleteVersion = async (versionId: string) => {
    try {
      await deleteDatasetVersion(versionId);
      message.success('版本已删除');
      if (previewVersionId === versionId) {
        setPreviewVersionId(undefined);
      }
      await loadDetail();
    } catch (error: any) {
      message.error(error?.info?.message || error?.message || '删除失败');
    }
  };

  const activeCurrentVersionId =
    datasetInfo?.currentVersionId ?? datasetInfo?.defaultVersionId;

  const handleSetCurrentVersion = async (record: API.DatasetVersionDetail) => {
    if (!id || !datasetInfo) return;
    const versionId =
      resolveDatasetVersionId(record, datasetInfo.id) ?? record.id;
    if (!versionId) return;
    try {
      await switchDatasetCurrentVersion(id, versionId, {
        skipErrorHandler: true,
      });
      message.success('已设为当前版本');
      await loadDetail();
    } catch (error: any) {
      message.error(
        error?.info?.message || error?.message || '切换当前版本失败',
      );
    }
  };

  const handleVersionStatusChange = async (
    record: API.DatasetVersionDetail,
    status: 'DEPRECATED' | 'ARCHIVED',
  ) => {
    const versionId =
      resolveDatasetVersionId(record, datasetInfo?.id) ?? record.id;
    if (!versionId) return;
    try {
      await updateDatasetVersionStatus(versionId, status, {
        skipErrorHandler: true,
      });
      message.success(
        status === 'DEPRECATED' ? '已标记为废弃版本' : '已归档该版本',
      );
      await loadDetail();
    } catch (error: any) {
      message.error(
        error?.info?.message || error?.message || '更新版本状态失败',
      );
    }
  };

  const handleSelectPreview = async (
    record: API.DatasetVersionDetail,
    scrollToPreview = true,
  ) => {
    const versionId =
      resolveDatasetVersionId(record, datasetInfo?.id) ?? record.id;
    if (!versionId || versionId === datasetInfo?.id) {
      message.warning('无法识别数据集版本 ID，请确认后端返回的版本 id 字段');
      return;
    }
    setPreviewVersionId(versionId);

    if (!scrollToPreview) return;

    setDetailTab('files');

    if (datasetInfo?.type === 'POINT_CLOUD') {
      document
        .getElementById('point-cloud-preview')
        ?.scrollIntoView({ behavior: 'smooth', block: 'start' });
      await previewPanelRef.current?.loadVersion(record);
      return;
    }

    if (
      datasetInfo?.type === 'CV' ||
      datasetInfo?.type === 'NLP' ||
      datasetInfo?.type === 'MULTIMODAL'
    ) {
      requestAnimationFrame(() => {
        previewSectionRef.current?.scrollIntoView({
          behavior: 'smooth',
          block: 'start',
        });
      });
    }
  };

  const previewVersion = datasetInfo?.versions.find(
    (v) =>
      (resolveDatasetVersionId(v, datasetInfo.id) ?? v.id) === previewVersionId,
  );
  const isPointCloud = datasetInfo?.type === 'POINT_CLOUD';
  const isMultimodal = datasetInfo?.type === 'MULTIMODAL';
  const supportsWorkspaceEdit = supportsDatasetWorkspaceEdit(datasetInfo?.type);
  const workspaceDatasetType = supportsWorkspaceEdit
    ? (datasetInfo.type as WorkspaceEditableDatasetType)
    : undefined;
  const supportsInlinePreview =
    datasetInfo?.type === 'CV' ||
    datasetInfo?.type === 'NLP' ||
    datasetInfo?.type === 'ROBOT' ||
    datasetInfo?.type === 'LEROBOT';
  /** 有活动 V2 工作区即展示面板（不依赖预览是否切到 target DRAFT 行） */
  const showWorkspacePanel = !!activeWorkspace && !!workspaceDatasetType;
  const previewIsImportDraft = isImportDraftVersion(
    previewVersion,
    draftContext,
  );
  const workspaceParentVersion = previewVersion?.parentVersionId
    ? datasetInfo?.versions.find(
        (item) =>
          item.id === previewVersion.parentVersionId ||
          resolveDatasetVersionId(item, datasetInfo?.id) ===
            previewVersion.parentVersionId,
      )
    : undefined;
  const previewVersionReady =
    isMultimodal &&
    !showWorkspacePanel &&
    !previewIsImportDraft &&
    previewVersionId != null &&
    (previewVersion?.status === 'READY' || !previewVersion?.status);

  const importDraftVersionId = datasetInfo?.latestDraftVersionId ?? undefined;
  const resolvedImportJobId =
    datasetInfo?.importJobId ||
    (datasetInfo?.id ? loadImportJobId(datasetInfo.id) : null) ||
    undefined;
  const importAttention =
    ['IMPORTING', 'IMPORT_FAILED', 'IMPORT_PARTIAL'].includes(
      String(datasetInfo?.displayStatus ?? ''),
    ) ||
    ['PENDING', 'RUNNING', 'FAILED', 'PARTIAL'].includes(
      String(datasetInfo?.importStatus ?? ''),
    );
  const showImportBanner =
    isMultimodal &&
    !!resolvedImportJobId &&
    (importAttention ||
      previewIsImportDraft ||
      (!!importDraftVersionId && previewVersionId === importDraftVersionId));

  const hasBackgroundImport =
    isMultimodal &&
    !!resolvedImportJobId &&
    !!importDraftVersionId &&
    previewVersionId !== importDraftVersionId &&
    ['PENDING', 'RUNNING', 'FAILED', 'PARTIAL'].includes(
      datasetInfo?.importStatus ?? '',
    );

  const versionFormRules = useMemo(
    () => datasetVersionFormRules(existingVersionNames),
    [existingVersionNames],
  );

  if (loading || (datasetInfo && datasetInfo.id !== id)) {
    return (
      <PageContainer
        title="数据集详情"
        onBack={() => history.push('/dataset/list')}
      >
        <div style={{ textAlign: 'center', padding: 80 }}>
          <Spin size="large" />
        </div>
      </PageContainer>
    );
  }

  if (detailError || workspaceReadError) {
    return <PageContainer title="数据集详情" onBack={() => history.push('/dataset/list')}>
      <Alert type="error" showIcon
        message={detailError ? '数据集详情读取失败' : '工作区读取未完成'}
        description={detailError || `${workspaceReadError} 本次读取未执行发布、放弃或删除。`}
        action={<Button onClick={() => void loadDetail()}>重试详情</Button>} />
    </PageContainer>;
  }

  if (!datasetInfo) {
    return (
      <PageContainer
        title="数据集详情"
        onBack={() => history.push('/dataset/list')}
      >
        <Empty description="未找到数据集详情" />
      </PageContainer>
    );
  }

  return (
    <PageContainer
      title="数据集详情"
      subTitle="数据集资产与版本管理；版本号可用 vN 或 vX.Y.Z，版本描述记录更新原因与内容"
      onBack={() => history.push('/dataset/list')}
      extra={
        <Space>
          <Button type="primary" onClick={handleUploadNewVersion}>
            上传新版本
          </Button>
          <Button
            onClick={() =>
              handleDownload(
                datasetInfo.latestVersion
                  ? resolveDatasetVersionId(
                      datasetInfo.latestVersion,
                      datasetInfo.id,
                    )
                  : undefined,
                datasetInfo.latestVersion?.storagePath,
                datasetInfo.latestVersion?.fileName,
              )
            }
          >
            下载最新版本
          </Button>
          <Popconfirm
            title="确认删除该数据集？删除后无法恢复。"
            onConfirm={handleDelete}
          >
            <Button danger>删除数据集</Button>
          </Popconfirm>
          <Button onClick={() => history.push('/dataset/list')}>
            返回列表
          </Button>
        </Space>
      }
    >
      <Card title="资产信息" style={{ marginBottom: 16 }}>
        <Descriptions column={2}>
          <Descriptions.Item label="数据集名称">
            <strong>{datasetInfo.name}</strong>
          </Descriptions.Item>
          <Descriptions.Item label="类型">
            <Tag color={DATASET_TYPE_COLOR[datasetInfo.type] ?? 'green'}>
              {DATASET_TYPE_LABEL[datasetInfo.type] ?? datasetInfo.type}
            </Tag>
          </Descriptions.Item>
          <Descriptions.Item label="最近上传时间">
            {formatDisplayDateTime(datasetInfo.uploadTime)}
          </Descriptions.Item>
          <Descriptions.Item label="版本数量">
            {datasetInfo.versions.length}
          </Descriptions.Item>
          <Descriptions.Item label="资产备注" span={2}>
            {datasetInfo.remark || '-'}
          </Descriptions.Item>
        </Descriptions>
      </Card>

      {supportsWorkspaceEdit && (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          message="版本维护说明"
          description={
            <>
              <strong>上传新版本</strong>：替换整包数据，适合大批量更换。
              <br />
              <strong>创建/继续在线编辑</strong>：基于所选正式版本删除或恢复
              {isMultimodal ? '样本' : '文件'}、追加 zip
              {isMultimodal ? '新增样本' : '新增文件'}
              ，完成后发布为新版本。同一数据集同时只能有一个未发布的编辑版本。
            </>
          }
        />
      )}

      {hasBackgroundImport && (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          message="另有版本正在导入"
          description={
            <Space>
              <span>当前选中的是其他版本；最新 DRAFT 仍在后台导入样本。</span>
              <Button
                type="link"
                size="small"
                style={{ padding: 0 }}
                onClick={() => {
                  if (importDraftVersionId) {
                    setPreviewVersionId(importDraftVersionId);
                    setDetailTab('files');
                    previewSectionRef.current?.scrollIntoView({
                      behavior: 'smooth',
                      block: 'start',
                    });
                  }
                }}
              >
                查看导入中的版本
              </Button>
            </Space>
          }
        />
      )}

      {showImportBanner && (
        <MultimodalImportBanner
          importJobId={resolvedImportJobId}
          datasetId={datasetInfo.id}
          workspaceId={
            activeWorkspace?.workspaceId ||
            datasetInfo.workspaceId ||
            datasetInfo.editSessionId
          }
          workspaceRevision={
            activeWorkspace?.workspaceRevision ?? datasetInfo.workspaceRevision
          }
          initialStatus={datasetInfo.importStatus}
          initialProgress={datasetInfo.importProgress}
          initialErrorMessage={datasetInfo.importErrorMessage}
          onImportFinished={loadDetail}
          onWorkspaceRevisionChange={(revision) => {
            setActiveWorkspace((prev) =>
              prev ? { ...prev, workspaceRevision: revision } : prev,
            );
          }}
        />
      )}

      <Card styles={{ body: { paddingTop: 8 } }}>
        <Tabs
          activeKey={detailTab}
          onChange={setDetailTab}
          tabBarExtraContent={
            detailTab === 'versions' && !isMultimodal ? (
              <Button
                type="dashed"
                icon={<PlusOutlined />}
                onClick={openCreateVersion}
              >
                新建版本记录
              </Button>
            ) : detailTab === 'files' && previewVersion ? (
              <Space>
                <Typography.Text
                  type="secondary"
                  ellipsis={{ tooltip: previewVersion.fileName }}
                  style={{ maxWidth: 360 }}
                >
                  当前版本：{previewVersion.version}
                  {previewVersion.fileName
                    ? ` · ${previewVersion.fileName}`
                    : ''}
                </Typography.Text>
                {datasetInfo.type === 'LEROBOT' && previewVersionId ? (
                  <Button
                    type="primary"
                    size="small"
                    onClick={() =>
                      history.push(
                        `/dataset/lerobot-timeline/${encodeURIComponent(previewVersionId)}?assetId=${encodeURIComponent(datasetInfo.id)}`,
                      )
                    }
                  >
                    按时序查看
                  </Button>
                ) : null}
              </Space>
            ) : undefined
          }
          items={[
            {
              key: 'readme',
              label: 'README',
              children: (
                <ZipReadmePanel
                  source="dataset"
                  versionId={previewVersionId}
                  datasetType={datasetInfo?.type}
                />
              ),
            },
            {
              key: 'files',
              label: showWorkspacePanel
                ? '文件预览 · 版本工作区'
                : isMultimodal
                  ? '文件预览 · 多模态样本'
                  : '文件预览',
              children: (
                <div ref={previewSectionRef}>
                  {!isPointCloud && (
                    <>
                      {showWorkspacePanel &&
                      activeWorkspace &&
                      workspaceDatasetType ? (
                        <MultimodalWorkspacePanel
                          key={activeWorkspace.workspaceId}
                          workspaceId={activeWorkspace.workspaceId}
                          workspaceRevision={activeWorkspace.workspaceRevision}
                          onWorkspaceRevisionChange={(revision) => {
                            setActiveWorkspace((prev) =>
                              prev
                                ? { ...prev, workspaceRevision: revision }
                                : prev,
                            );
                          }}
                          datasetId={datasetInfo.id}
                          datasetType={workspaceDatasetType}
                          draftVersionLabel={
                            activeWorkspace.targetVersionLabel ||
                            previewVersion?.version
                          }
                          parentVersionLabel={
                            activeWorkspace.baseVersionLabel ||
                            workspaceParentVersion?.version
                          }
                          onImportJobDiscovered={(jobId) => {
                            saveImportJobId(datasetInfo.id, jobId);
                            setDatasetInfo((prev) =>
                              prev ? { ...prev, importJobId: jobId } : prev,
                            );
                          }}
                          onPublished={async (publishedVersionId) => {
                            setWorkspaceEditSourceVersionId(undefined);
                            setActiveWorkspace(null);
                            await loadDetail();
                            if (publishedVersionId) {
                              setPreviewVersionId(publishedVersionId);
                            }
                          }}
                          onRefresh={loadDetail}
                          onCancelEdit={handleCancelWorkspaceEdit}
                        />
                      ) : isMultimodal ? (
                        previewVersionReady ? (
                          <MultimodalPreviewPanel
                            key={previewVersionId}
                            versionId={previewVersionId}
                            compact
                          />
                        ) : (
                          <Empty description="该版本正在后台导入样本。导入完成后可浏览；导入期间无法编辑或删除。" />
                        )
                      ) : supportsInlinePreview ? (
                        <>
                          {previewVersion?.parentVersionId ? (
                            <Alert
                              type="info"
                              showIcon
                              style={{ marginBottom: 12 }}
                              message="本版本由在线编辑发布"
                              description="当前展示发布时合并后的文件或样本。"
                            />
                          ) : null}
                          <DatasetPreviewPanel
                            key={previewVersionId}
                            versionId={previewVersionId}
                            compact
                            hierarchical={datasetInfo.type === 'LEROBOT'}
                            samplesOnly={!!previewVersion?.parentVersionId}
                          />
                        </>
                      ) : (
                        <Empty description="当前类型不支持在线预览" />
                      )}
                    </>
                  )}

                  {isPointCloud &&
                    (showWorkspacePanel &&
                    activeWorkspace &&
                    workspaceDatasetType ? (
                      <MultimodalWorkspacePanel
                        key={activeWorkspace.workspaceId}
                        workspaceId={activeWorkspace.workspaceId}
                        workspaceRevision={activeWorkspace.workspaceRevision}
                        onWorkspaceRevisionChange={(revision) => {
                          setActiveWorkspace((prev) =>
                            prev
                              ? { ...prev, workspaceRevision: revision }
                              : prev,
                          );
                        }}
                        datasetId={datasetInfo.id}
                        datasetType={workspaceDatasetType}
                        draftVersionLabel={
                          activeWorkspace.targetVersionLabel ||
                          previewVersion?.version
                        }
                        parentVersionLabel={
                          activeWorkspace.baseVersionLabel ||
                          workspaceParentVersion?.version
                        }
                        onImportJobDiscovered={(jobId) => {
                          saveImportJobId(datasetInfo.id, jobId);
                          setDatasetInfo((prev) =>
                            prev ? { ...prev, importJobId: jobId } : prev,
                          );
                        }}
                        onPublished={async (publishedVersionId) => {
                          setWorkspaceEditSourceVersionId(undefined);
                          setActiveWorkspace(null);
                          await loadDetail();
                          if (publishedVersionId) {
                            setPreviewVersionId(publishedVersionId);
                          }
                        }}
                        onRefresh={loadDetail}
                        onCancelEdit={handleCancelWorkspaceEdit}
                      />
                    ) : (
                      <PointCloudPreviewPanel
                        ref={previewPanelRef}
                        onSelectionChange={setPreviewVersionId}
                      />
                    ))}
                </div>
              ),
            },
            {
              key: 'versions',
              label: '版本',
              children: (
                <>
                  <Table
                    dataSource={datasetInfo.versions}
                    rowKey="id"
                    pagination={false}
                    scroll={{ x: 1280 }}
                    locale={{ emptyText: '暂无版本记录' }}
                    onRow={(record) => ({
                      onClick: () => handleSelectPreview(record),
                      style: {
                        cursor: 'pointer',
                        background:
                          (resolveDatasetVersionId(record, datasetInfo.id) ??
                            record.id) === previewVersionId
                            ? '#e6f4ff'
                            : undefined,
                      },
                    })}
                    columns={[
                      {
                        title: '版本号',
                        dataIndex: 'version',
                        key: 'version',
                        width: 120,
                        render: (
                          text: string,
                          record: API.DatasetVersionDetail,
                        ) => {
                          const vid =
                            resolveDatasetVersionId(record, datasetInfo.id) ??
                            record.id;
                          const isCurrent =
                            !!vid &&
                            !!activeCurrentVersionId &&
                            vid === activeCurrentVersionId;
                          return (
                            <Space size={4}>
                              <span>{text}</span>
                              {isCurrent && <Tag color="blue">当前</Tag>}
                            </Space>
                          );
                        },
                      },
                      {
                        title: '状态',
                        dataIndex: 'status',
                        key: 'status',
                        width: 100,
                        render: (
                          status: string,
                          record: API.DatasetVersionDetail,
                        ) => {
                          if (isWorkspaceDraftVersion(record, draftContext)) {
                            return <Tag color="processing">版本工作区</Tag>;
                          }
                          if (isImportDraftVersion(record, draftContext)) {
                            if (draftContext?.importStatus === 'PARTIAL') {
                              return <Tag color="warning">部分导入</Tag>;
                            }
                            if (draftContext?.importStatus === 'FAILED') {
                              return <Tag color="error">导入失败</Tag>;
                            }
                            return <Tag color="default">导入中</Tag>;
                          }
                          if (status === 'DEPRECATED') {
                            return <Tag color="warning">已废弃</Tag>;
                          }
                          if (status === 'ARCHIVED') {
                            return <Tag>已归档</Tag>;
                          }
                          return (
                            <Tag
                              color={status === 'READY' ? 'success' : 'default'}
                            >
                              {status || 'READY'}
                            </Tag>
                          );
                        },
                      },
                      {
                        title: '文件名',
                        dataIndex: 'fileName',
                        key: 'fileName',
                        width: 200,
                        ellipsis: true,
                        render: (text: string) => (
                          <TableEllipsisCell text={text} />
                        ),
                      },
                      {
                        title: '大小',
                        dataIndex: 'size',
                        key: 'size',
                        width: 100,
                      },
                      {
                        title: '上传时间',
                        dataIndex: 'createdAt',
                        key: 'createdAt',
                        width: 180,
                        render: (value?: string) =>
                          formatDisplayDateTime(value),
                      },
                      {
                        title: '版本描述',
                        dataIndex: 'remark',
                        key: 'remark',
                        width: 160,
                        ellipsis: true,
                        render: (text: string) =>
                          text ? (
                            <Tooltip title={text}>
                              <span>{text}</span>
                            </Tooltip>
                          ) : (
                            <Typography.Text type="secondary">
                              未填写
                            </Typography.Text>
                          ),
                      },
                      {
                        title: '操作',
                        key: 'action',
                        width: 480,
                        fixed: 'right',
                        align: 'left',
                        render: (_, record: API.DatasetVersionDetail) => {
                          const vid =
                            resolveDatasetVersionId(record, datasetInfo.id) ??
                            record.id;
                          const isCurrent =
                            !!vid &&
                            !!activeCurrentVersionId &&
                            vid === activeCurrentVersionId;
                          const canSetCurrent =
                            record.status === 'READY' &&
                            !isCurrent &&
                            !isWorkspaceDraftVersion(record, draftContext);
                          const canDeprecate =
                            record.status === 'READY' &&
                            !isCurrent &&
                            !isWorkspaceDraftVersion(record, draftContext) &&
                            !isImportDraftVersion(record, draftContext);
                          const canArchive = record.status === 'DEPRECATED';
                          const canEditVersionWorkspace =
                            supportsWorkspaceEdit &&
                            (isWorkspaceDraftVersion(record, draftContext) ||
                              (record.status === 'READY' &&
                                !isImportDraftVersion(record, draftContext) &&
                                isZipBackedDatasetVersion(record)));

                          return (
                            <Space
                              size={0}
                              wrap
                              split={
                                <span style={{ color: '#f0f0f0' }}>|</span>
                              }
                              onClick={(e) => e.stopPropagation()}
                            >
                              <Button
                                type="link"
                                style={{ paddingLeft: 0 }}
                                onClick={() => handleSelectPreview(record)}
                              >
                                选中预览
                              </Button>
                              {canEditVersionWorkspace && (
                                <Button
                                  type="link"
                                  icon={<EditOutlined />}
                                  onClick={() =>
                                    handleEditCurrentVersion(record)
                                  }
                                >
                                  创建/继续版本工作区
                                </Button>
                              )}
                              <Button
                                type="link"
                                onClick={() => openEditRemark(record)}
                              >
                                编辑描述
                              </Button>
                              {canSetCurrent && (
                                <Popconfirm
                                  title="将此版本设为列表和训练的默认当前版本？"
                                  onConfirm={() =>
                                    handleSetCurrentVersion(record)
                                  }
                                >
                                  <Button type="link">设为当前</Button>
                                </Popconfirm>
                              )}
                              {canDeprecate && (
                                <Popconfirm
                                  title="标记为废弃后不可用于新训练，仍可预览（多模态除外）。"
                                  onConfirm={() =>
                                    handleVersionStatusChange(
                                      record,
                                      'DEPRECATED',
                                    )
                                  }
                                >
                                  <Button type="link">废弃</Button>
                                </Popconfirm>
                              )}
                              {canArchive && (
                                <Popconfirm
                                  title="归档后不可预览或训练，确认归档？"
                                  onConfirm={() =>
                                    handleVersionStatusChange(
                                      record,
                                      'ARCHIVED',
                                    )
                                  }
                                >
                                  <Button type="link">归档</Button>
                                </Popconfirm>
                              )}
                              <Button
                                type="link"
                                onClick={() =>
                                  handleDownload(
                                    resolveDatasetVersionId(
                                      record,
                                      datasetInfo.id,
                                    ),
                                    record.storagePath,
                                    record.fileName,
                                  )
                                }
                              >
                                下载
                              </Button>
                              <Popconfirm
                                title="确认删除该版本？"
                                onConfirm={() => handleDeleteVersion(record.id)}
                              >
                                <Button type="link" danger>
                                  删除
                                </Button>
                              </Popconfirm>
                            </Space>
                          );
                        },
                      },
                    ]}
                  />
                  <Typography.Text
                    type="secondary"
                    style={{ display: 'block', marginTop: 8 }}
                  >
                    版本号可用 vN 或 vX.Y.Z（如
                    v2、v1.0.0），资产内须唯一；版本描述记录更新原因与内容。新建版本记录后请「上传新版本」绑定文件；点击行可选中版本，并在「README」「文件预览」中查看。
                  </Typography.Text>
                </>
              ),
            },
          ]}
        />
      </Card>

      <Modal
        title={
          versionModalMode === 'create'
            ? '新建版本记录'
            : versionModalMode === 'editWorkspace'
              ? '创建版本工作区'
              : '编辑版本描述'
        }
        open={versionModalOpen}
        onCancel={() => setVersionModalOpen(false)}
        onOk={submitVersionModal}
        confirmLoading={versionModalLoading}
        destroyOnClose
        width={560}
        okText={versionModalMode === 'editWorkspace' ? '创建工作区' : '确定'}
      >
        <Form form={versionForm} layout="vertical">
          {(versionModalMode === 'create' ||
            versionModalMode === 'editWorkspace') && (
            <Form.Item
              name="version"
              label="新版本号"
              rules={versionFormRules}
              extra={DATASET_VERSION_FORMAT_HINT}
            >
              <Input placeholder="例如 v1.0.1" />
            </Form.Item>
          )}
          <Form.Item
            name="remark"
            label="版本描述"
            rules={datasetVersionDescFormRules()}
            extra="说明本版本的更新原因与内容"
          >
            <Input.TextArea
              rows={4}
              placeholder={DATASET_VERSION_DESC_PLACEHOLDER}
              showCount
              maxLength={2000}
            />
          </Form.Item>
          {versionModalMode === 'create' && (
            <Alert
              type="info"
              showIcon
              message="创建版本记录后，请通过「上传新版本」上传数据文件完成绑定。"
            />
          )}
          {versionModalMode === 'editWorkspace' && (
            <Alert
              type="info"
              showIcon
              message="将基于所选正式版本开始在线编辑"
              description="请填写未使用的新版本号。若已有未发布的编辑版本，请先发布或放弃。"
            />
          )}
        </Form>
      </Modal>
    </PageContainer>
  );
};

export default DatasetDetail;
