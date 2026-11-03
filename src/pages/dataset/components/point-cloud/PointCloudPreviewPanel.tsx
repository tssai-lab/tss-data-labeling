import { Alert, Button, Card, message } from 'antd';
import React, {
  forwardRef,
  useCallback,
  useEffect,
  useImperativeHandle,
  useMemo,
  useRef,
  useState,
} from 'react';
import {
  fetchPointCloudPreviewBlob,
  fetchPointCloudPreviewInfo,
  PointCloudFileFormat,
  PointCloudPreviewFormat,
  type PointCloudPreviewInfo,
  type PointCloudZipEntry,
  pointCloudBlobToArrayBuffer,
} from '@/services/pointcloud';
import { getApiErrorMessage } from '@/utils/apiError';
import PointCloudCanvas from './PointCloudCanvas';
import PointCloudZipFileList from './PointCloudZipFileList';
import {
  filterInvalidPoints,
  makePointsMaterial,
  normalizeGeometry,
  type PointCloudLoadResult,
  parsePointCloudBuffer,
} from './pointCloudUtils';

export type PointCloudPreviewPanelRef = {
  loadVersion: (version: API.DatasetVersionDetail) => Promise<void>;
  cancelPreview: () => void;
};

export type PointCloudPreviewPanelProps = {
  onSelectionChange?: (versionId?: string) => void;
};

function isZipFileName(fileName?: string | null) {
  return !!fileName?.toLowerCase().endsWith('.zip');
}

const PointCloudPreviewPanel = forwardRef<
  PointCloudPreviewPanelRef,
  PointCloudPreviewPanelProps
>(({ onSelectionChange }, ref) => {
  const [activeVersion, setActiveVersion] =
    useState<API.DatasetVersionDetail | null>(null);
  const [previewInfo, setPreviewInfo] = useState<PointCloudPreviewInfo | null>(
    null,
  );
  const [zipEntryPath, setZipEntryPath] = useState<string>();
  const [metaLoading, setMetaLoading] = useState(false);
  const [fileLoading, setFileLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<PointCloudLoadResult | null>(null);
  const [previewIdleHint, setPreviewIdleHint] = useState<
    'cancelled' | 'cleared' | null
  >(null);
  const controlsRef = useRef<any>(null);
  const loadAbortRef = useRef<AbortController | null>(null);
  const cancelledRef = useRef(false);
  const viewRadiusRef = useRef(1);

  const loading = metaLoading || fileLoading;

  const disposeResult = useCallback(() => {
    setResult((prev) => {
      prev?.geometry.dispose();
      prev?.material.dispose();
      return null;
    });
  }, []);

  useEffect(() => {
    return () => {
      loadAbortRef.current?.abort();
      disposeResult();
    };
  }, [disposeResult]);

  const resetView = useCallback((radius: number) => {
    viewRadiusRef.current = radius;
    const controls = controlsRef.current;
    if (!controls) return;
    controls.target.set(0, 0, 0);
    controls.object.position.set(radius * 2, radius * 1.5, radius * 2);
    controls.object.near = Math.max(0.01, radius / 100);
    controls.object.far = Math.max(1000, radius * 100);
    controls.object.updateProjectionMatrix();
    controls.update();
  }, []);

  useEffect(() => {
    if (!result) return;
    let frameId = 0;
    const tryResetView = () => {
      if (controlsRef.current) {
        resetView(viewRadiusRef.current);
        return;
      }
      frameId = requestAnimationFrame(tryResetView);
    };
    tryResetView();
    return () => cancelAnimationFrame(frameId);
  }, [result, resetView]);

  const loadGeometry = useCallback(
    async (
      fileName: string,
      format: PointCloudFileFormat,
      arrayBuffer: ArrayBuffer,
    ) => {
      const geometry = parsePointCloudBuffer(format, arrayBuffer);
      if (!geometry.getAttribute('position')) {
        throw new Error('点云缺少 position 属性');
      }

      const filterStats = filterInvalidPoints(geometry);
      if (filterStats.removed > 0) {
        console.info('[PointCloud] NaN 过滤', fileName, filterStats);
      }

      const { radius } = normalizeGeometry(geometry, {
        pcdCoordinateFix: format === PointCloudFileFormat.PCD,
      });
      const material = makePointsMaterial(geometry);
      const pointCount = geometry.getAttribute('position').count ?? 0;
      disposeResult();
      setResult({
        fileName,
        geometry,
        material,
        pointCount,
        originalPointCount: filterStats.total,
        removedPointCount: filterStats.removed,
      });
      resetView(radius);
    },
    [disposeResult, resetView],
  );

  const loadFromPreviewUrl = useCallback(
    async (
      previewUrl: string,
      format: PointCloudFileFormat,
      fileName: string,
    ) => {
      loadAbortRef.current?.abort();
      const controller = new AbortController();
      loadAbortRef.current = controller;

      setPreviewIdleHint(null);
      setFileLoading(true);
      setError(null);
      try {
        const blob = await fetchPointCloudPreviewBlob(previewUrl, {
          signal: controller.signal,
          skipErrorHandler: true,
        });
        if (cancelledRef.current) return;

        const buffer = await pointCloudBlobToArrayBuffer(blob);
        if (cancelledRef.current) return;

        await loadGeometry(fileName, format, buffer);
        if (cancelledRef.current) return;

        message.success('点云加载成功');
      } catch (e: unknown) {
        if ((e as Error)?.name !== 'AbortError' && !cancelledRef.current) {
          setError(getApiErrorMessage(e));
        }
      } finally {
        if (!cancelledRef.current) {
          setFileLoading(false);
        }
      }
    },
    [loadGeometry],
  );

  const cancelPreview = useCallback(
    (options?: { silent?: boolean }) => {
      cancelledRef.current = true;
      loadAbortRef.current?.abort();
      loadAbortRef.current = null;
      setMetaLoading(false);
      setFileLoading(false);
      setError(null);
      disposeResult();
      setPreviewIdleHint('cancelled');

      if (!options?.silent) {
        const isZip =
          previewInfo?.format === PointCloudPreviewFormat.ZIP ||
          isZipFileName(activeVersion?.fileName);
        message.info(
          isZip
            ? '预览已取消，可再次点击「开始预览」重新预览'
            : '预览已取消，可再次点击版本列表【选中预览】重新预览',
        );
      }
    },
    [activeVersion?.fileName, disposeResult, previewInfo?.format],
  );

  const loadVersion = useCallback(
    async (version: API.DatasetVersionDetail) => {
      cancelledRef.current = false;
      loadAbortRef.current?.abort();
      setActiveVersion(version);
      onSelectionChange?.(version.id);
      setZipEntryPath(undefined);
      setPreviewInfo(null);
      setPreviewIdleHint(null);
      setError(null);
      disposeResult();

      setMetaLoading(true);
      try {
        const info = await fetchPointCloudPreviewInfo(version.id, {
          skipErrorHandler: true,
        });
        if (cancelledRef.current) return;

        setPreviewInfo(info);

        if (!info.previewSupported) {
          setError(info.message || '当前文件不支持在线预览');
          return;
        }

        if (info.format === PointCloudPreviewFormat.ZIP) {
          return;
        }

        if (!info.previewUrl) {
          setError(info.message || '缺少 previewUrl，无法加载点云');
          return;
        }

        if (info.format === PointCloudPreviewFormat.PCD) {
          await loadFromPreviewUrl(
            info.previewUrl,
            PointCloudFileFormat.PCD,
            info.fileName,
          );
        } else if (info.format === PointCloudPreviewFormat.PLY) {
          await loadFromPreviewUrl(
            info.previewUrl,
            PointCloudFileFormat.PLY,
            info.fileName,
          );
        }
      } catch (e: unknown) {
        if (!cancelledRef.current) {
          setError(getApiErrorMessage(e));
        }
      } finally {
        if (!cancelledRef.current) {
          setMetaLoading(false);
        }
      }
    },
    [disposeResult, loadFromPreviewUrl, onSelectionChange],
  );

  useImperativeHandle(ref, () => ({ loadVersion, cancelPreview }), [
    loadVersion,
    cancelPreview,
  ]);

  const zipEntries: PointCloudZipEntry[] = useMemo(
    () => previewInfo?.pointCloudFiles ?? [],
    [previewInfo],
  );

  const handleZipEntrySelect = (entry: PointCloudZipEntry) => {
    setZipEntryPath(entry.path);
    setError(null);
  };

  const handleZipEntryPreview = async (entry: PointCloudZipEntry) => {
    if (!activeVersion || !previewInfo) return;

    if (!entry.previewAllowed) {
      setError(entry.message || '该文件过大，请下载后本地查看');
      return;
    }

    if (!entry.previewUrl) {
      setError(entry.message || '缺少 previewUrl，无法加载点云');
      return;
    }

    setZipEntryPath(entry.path);
    cancelledRef.current = false;
    setPreviewIdleHint(null);
    loadAbortRef.current?.abort();
    const controller = new AbortController();
    loadAbortRef.current = controller;

    setFileLoading(true);
    setError(null);
    disposeResult();
    try {
      const blob = await fetchPointCloudPreviewBlob(entry.previewUrl, {
        signal: controller.signal,
        skipErrorHandler: true,
      });
      if (cancelledRef.current) return;

      const buffer = await pointCloudBlobToArrayBuffer(blob);
      if (cancelledRef.current) return;

      await loadGeometry(entry.fileName, entry.format, buffer);
      if (cancelledRef.current) return;

      message.success('点云加载成功');
    } catch (e: unknown) {
      if ((e as Error)?.name !== 'AbortError' && !cancelledRef.current) {
        setError(getApiErrorMessage(e));
      }
    } finally {
      if (!cancelledRef.current) {
        setFileLoading(false);
      }
    }
  };

  const handleClearPreview = () => {
    setError(null);
    disposeResult();
    setPreviewIdleHint('cleared');
  };

  const isZipContext =
    previewInfo?.format === PointCloudPreviewFormat.ZIP ||
    isZipFileName(activeVersion?.fileName);

  const isActivelyPreviewing = !previewIdleHint && (loading || !!result);

  const versionInfoText = activeVersion
    ? `${isActivelyPreviewing ? '正在预览' : '已选版本'}：${activeVersion.version}${activeVersion.fileName ? ` · ${activeVersion.fileName}` : ''}`
    : '尚未选择版本，请在上方版本列表点击【选中预览】';

  const selectedZipEntry = zipEntryPath
    ? zipEntries.find((item) => item.path === zipEntryPath)
    : undefined;
  const zipFileInfoText =
    previewInfo?.format === PointCloudPreviewFormat.ZIP && selectedZipEntry
      ? ` · 当前文件：${selectedZipEntry.path}`
      : null;

  const isZipPreview =
    previewInfo?.format === PointCloudPreviewFormat.ZIP &&
    !result &&
    !zipEntryPath &&
    !previewIdleHint;
  const zipHintText = isZipPreview
    ? previewInfo?.message || '请选择包内文件后点击「开始预览」'
    : null;

  const showZipFileList = !!activeVersion && isZipContext;

  const cancelledHintText = isZipContext
    ? '预览已取消，可再次点击「开始预览」重新预览'
    : '预览已取消，可再次点击版本列表【选中预览】重新预览';

  const clearedHintText = isZipContext
    ? '预览已清除，可再次点击「开始预览」重新查看'
    : '预览已清除，可再次点击版本列表【选中预览】重新加载';

  const canvasPlaceholder = !activeVersion
    ? '请在版本列表点击【选中预览】'
    : previewIdleHint === 'cancelled'
      ? cancelledHintText
      : previewIdleHint === 'cleared'
        ? clearedHintText
        : previewInfo?.format === PointCloudPreviewFormat.ZIP && !result
          ? '请在上方列表选择文件并点击「开始预览」'
          : '';

  return (
    <Card
      id="point-cloud-preview"
      style={{ marginTop: 16 }}
      title={
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            flexWrap: 'wrap',
            gap: 12,
            minWidth: 0,
          }}
        >
          <span>点云在线预览</span>
          {!isZipContext && (
            <>
              <span
                style={{
                  fontSize: 14,
                  fontWeight: 'normal',
                  color: 'rgba(0,0,0,0.45)',
                }}
              >
                支持 110MB 以内的点云文件在线预览
              </span>
              <span
                style={{
                  fontSize: 14,
                  fontWeight: 'normal',
                  color: activeVersion
                    ? 'rgba(0,0,0,0.65)'
                    : 'rgba(0,0,0,0.45)',
                }}
              >
                {versionInfoText}
              </span>
            </>
          )}
          {isZipContext && (
            <span
              style={{
                fontSize: 14,
                fontWeight: 'normal',
                color: activeVersion ? 'rgba(0,0,0,0.65)' : 'rgba(0,0,0,0.45)',
              }}
            >
              {versionInfoText}
              {zipFileInfoText && (
                <span style={{ color: 'rgba(0,0,0,0.65)' }}>
                  {zipFileInfoText}
                </span>
              )}
              {zipHintText && (
                <span style={{ color: 'rgba(0,0,0,0.45)' }}>
                  {' '}
                  · {zipHintText}
                </span>
              )}
            </span>
          )}
        </div>
      }
      extra={
        activeVersion ? (
          <Button onClick={() => cancelPreview()}>取消预览</Button>
        ) : undefined
      }
    >
      {showZipFileList && activeVersion && (
        <PointCloudZipFileList
          key={activeVersion.id}
          files={zipEntries}
          selectedPath={zipEntryPath}
          loading={metaLoading}
          onSelect={handleZipEntrySelect}
          onPreview={handleZipEntryPreview}
        />
      )}

      {error && (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 12 }}
          message={error}
        />
      )}

      <PointCloudCanvas
        result={result}
        loading={loading}
        placeholder={canvasPlaceholder}
        controlsRef={controlsRef}
        onClearPreview={handleClearPreview}
      />
    </Card>
  );
});

PointCloudPreviewPanel.displayName = 'PointCloudPreviewPanel';

export default PointCloudPreviewPanel;
