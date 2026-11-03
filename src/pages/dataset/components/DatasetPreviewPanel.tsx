import { FileOutlined, FolderOpenOutlined } from '@ant-design/icons';
import {
  Alert,
  Button,
  Col,
  Empty,
  Image,
  Input,
  Row,
  Select,
  Space,
  Spin,
  Table,
  Tag,
  Tree,
  Typography,
} from 'antd';
import type { ColumnsType, TablePaginationConfig } from 'antd/es/table';
import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import {
  type DatasetPreviewContentData,
  type DatasetPreviewFileItem,
  DatasetPreviewFileKind,
  type DatasetPreviewFilesData,
  fetchDatasetPreviewContent,
  fetchDatasetPreviewFiles,
  getDatasetPreviewImage,
} from '@/services/datasetPreview';
import {
  type ConsumerManifestSample,
  fetchConsumerManifest,
  fetchMultimodalDataPreview,
  fetchMultimodalSampleDetail,
  fetchMultimodalSamples,
  type MultimodalSampleDataItem,
} from '@/services/platform';
import { getApiErrorMessage } from '@/utils/apiError';
import MarkdownPreview from './MarkdownPreview';
import TableEllipsisCell from './TableEllipsisCell';

const KIND_OPTIONS: { value: DatasetPreviewFileKind; label: string }[] = [
  { value: DatasetPreviewFileKind.IMAGE, label: '图片' },
  { value: DatasetPreviewFileKind.TEXT, label: '文本' },
  { value: DatasetPreviewFileKind.TABLE, label: '表格' },
  { value: DatasetPreviewFileKind.UNSUPPORTED, label: '不支持' },
];

const KIND_COLORS: Record<DatasetPreviewFileKind, string> = {
  [DatasetPreviewFileKind.IMAGE]: 'blue',
  [DatasetPreviewFileKind.TEXT]: 'green',
  [DatasetPreviewFileKind.TABLE]: 'orange',
  [DatasetPreviewFileKind.UNSUPPORTED]: 'default',
};

function formatBytes(size?: number | null): string {
  if (size == null || size < 0) return '-';
  if (size < 1024) return `${size} B`;
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`;
  return `${(size / (1024 * 1024)).toFixed(2)} MB`;
}

const DEFAULT_CSV_PAGE_SIZE = 50;
const DEFAULT_TEXT_PAGE_SIZE = 100;
const CSV_PAGE_SIZE_OPTIONS = ['20', '50', '100', '200'];
const TEXT_PAGE_SIZE_OPTIONS = ['50', '100', '200', '500'];

function isContentBackendPagination(
  data: DatasetPreviewContentData | null | undefined,
): data is DatasetPreviewContentData & { total: number } {
  return (
    !!data?.pageable &&
    data.total != null &&
    typeof data.total === 'number' &&
    data.total >= 0
  );
}

/** 后端未返回 total 时的兜底：固定展示 2 个页码 */
function getContentFallbackVisiblePageNumbers(
  currentPage: number,
  hasMoreRows: boolean,
): number[] {
  if (hasMoreRows) {
    return [currentPage, currentPage + 1];
  }
  if (currentPage > 1) {
    return [currentPage - 1, currentPage];
  }
  return [1];
}

function getFileLabel(file: DatasetPreviewFileItem): string {
  return file.path || file.fileName;
}

function extensionOf(name?: string): string | undefined {
  if (!name) return undefined;
  const i = name.lastIndexOf('.');
  if (i < 0) return undefined;
  return name.slice(i + 1).toLowerCase();
}

function kindFromSampleData(
  item: MultimodalSampleDataItem,
): DatasetPreviewFileKind {
  if (item.dataType === 'IMAGE') return DatasetPreviewFileKind.IMAGE;
  if (item.dataType === 'TEXT') {
    const ext = extensionOf(item.fileName);
    if (ext === 'csv' || ext === 'tsv') return DatasetPreviewFileKind.TABLE;
    return DatasetPreviewFileKind.TEXT;
  }
  const ext = extensionOf(item.fileName);
  if (
    ext &&
    ['jpg', 'jpeg', 'png', 'bmp', 'gif', 'webp', 'tif', 'tiff'].includes(ext)
  ) {
    return DatasetPreviewFileKind.IMAGE;
  }
  if (
    ext &&
    ['txt', 'json', 'jsonl', 'xml', 'md', 'yaml', 'yml'].includes(ext)
  ) {
    return DatasetPreviewFileKind.TEXT;
  }
  if (ext === 'csv' || ext === 'tsv') return DatasetPreviewFileKind.TABLE;
  return DatasetPreviewFileKind.UNSUPPORTED;
}

function mapSampleDataToPreviewFile(
  item: MultimodalSampleDataItem,
  sample: { externalId?: string; sampleIndex?: number },
): DatasetPreviewFileItem {
  const kind = kindFromSampleData(item);
  const fileName =
    item.fileName || sample.externalId || item.sampleDataId || 'file';
  const path = sample.externalId
    ? `${sample.externalId}/${fileName}`
    : fileName;
  return {
    path,
    fileName,
    extension: extensionOf(fileName) || item.format,
    kind,
    sizeBytes: item.sizeBytes ?? null,
    previewAllowed:
      kind === DatasetPreviewFileKind.IMAGE ||
      kind === DatasetPreviewFileKind.TEXT ||
      kind === DatasetPreviewFileKind.TABLE,
    previewUrl: item.previewUrl,
    sampleDataId: item.sampleDataId,
    source: 'sample',
  };
}

function flattenManifestSamples(
  samples: ConsumerManifestSample[],
): DatasetPreviewFileItem[] {
  const files: DatasetPreviewFileItem[] = [];
  for (const sample of samples) {
    for (const data of sample.data ?? []) {
      files.push(mapSampleDataToPreviewFile(data, sample));
    }
  }
  return files;
}

function unwrapConsumerManifestPage(raw: unknown): {
  datasetVersionId?: string;
  type?: string;
  versionLabel?: string;
  page?: number;
  pageSize?: number;
  totalSamples: number;
  samples: ConsumerManifestSample[];
} | null {
  if (!raw || typeof raw !== 'object') return null;
  const obj = raw as Record<string, unknown>;
  const page =
    obj.samples != null
      ? obj
      : obj.data && typeof obj.data === 'object'
        ? (obj.data as Record<string, unknown>)
        : null;
  if (!page) return null;
  const samples = Array.isArray(page.samples)
    ? (page.samples as ConsumerManifestSample[])
    : [];
  const totalSamples =
    typeof page.totalSamples === 'number' ? page.totalSamples : samples.length;
  return {
    datasetVersionId:
      typeof page.datasetVersionId === 'string'
        ? page.datasetVersionId
        : undefined,
    type: typeof page.type === 'string' ? page.type : undefined,
    versionLabel:
      typeof page.versionLabel === 'string' ? page.versionLabel : undefined,
    page: typeof page.page === 'number' ? page.page : undefined,
    pageSize: typeof page.pageSize === 'number' ? page.pageSize : undefined,
    totalSamples,
    samples,
  };
}

function extensionOfFile(file: DatasetPreviewFileItem): string | undefined {
  const raw = (file.extension || '').toLowerCase().replace(/^\./, '');
  if (raw) return raw;
  const label = getFileLabel(file).toLowerCase();
  const i = label.lastIndexOf('.');
  if (i < 0) return undefined;
  return label.slice(i + 1);
}

/** NLP/CV 文档约定可预览的文本类扩展名（后端若误标 UNSUPPORTED 仍允许点「查看」） */
const FORCE_TEXT_PREVIEW_EXTS = new Set(['txt', 'json', 'jsonl', 'xml', 'md']);

function isJsonlFile(file: DatasetPreviewFileItem): boolean {
  return extensionOfFile(file) === 'jsonl';
}

function isMarkdownFile(file: DatasetPreviewFileItem): boolean {
  return extensionOfFile(file) === 'md';
}

function isForceTextPreviewFile(file: DatasetPreviewFileItem): boolean {
  const ext = extensionOfFile(file);
  return !!ext && FORCE_TEXT_PREVIEW_EXTS.has(ext);
}

function resolvePreviewKind(
  file: DatasetPreviewFileItem,
): DatasetPreviewFileKind {
  if (isForceTextPreviewFile(file)) {
    return DatasetPreviewFileKind.TEXT;
  }
  return file.kind;
}

function canPreviewFile(file: DatasetPreviewFileItem): boolean {
  if (isForceTextPreviewFile(file)) {
    return true;
  }
  if (!file.previewAllowed) {
    return false;
  }
  return (
    file.kind === DatasetPreviewFileKind.IMAGE ||
    file.kind === DatasetPreviewFileKind.TEXT ||
    file.kind === DatasetPreviewFileKind.TABLE
  );
}

function isTextContentPreview(
  selected: DatasetPreviewFileItem | null,
  contentData: DatasetPreviewContentData | null,
): boolean {
  if (!selected || !contentData) return false;
  if (contentData.content == null) return false;
  // 后端对 json/jsonl 可能返回 contentType=JSONL / TEXT，均按文本区展示
  const ct = String(contentData.contentType || '').toUpperCase();
  if (ct === 'TEXT' || ct === 'JSONL' || ct === 'JSON' || ct === 'XML') {
    return true;
  }
  if (isForceTextPreviewFile(selected)) {
    return true;
  }
  return false;
}

function countTextLines(content?: string | null): number {
  if (!content) return 0;
  return content.split('\n').length;
}

type ContentPageControlsProps = {
  page: number;
  pageSize: number;
  loading: boolean;
  useBackendPagination: boolean;
  total?: number | null;
  totalPages?: number | null;
  hasMoreRows: boolean;
  pageSizeOptions: string[];
  unitLabel: string;
  onChange: (page: number, pageSize: number) => void;
};

function ContentPageControls({
  page,
  pageSize,
  loading,
  useBackendPagination,
  total,
  totalPages,
  hasMoreRows,
  pageSizeOptions,
  unitLabel,
  onChange,
}: ContentPageControlsProps) {
  const fallbackPages = getContentFallbackVisiblePageNumbers(page, hasMoreRows);
  const maxPage = totalPages ?? (useBackendPagination ? page : undefined);
  const canGoNext = useBackendPagination
    ? maxPage != null
      ? page < maxPage
      : hasMoreRows
    : hasMoreRows;

  return (
    <div
      style={{
        display: 'flex',
        justifyContent: 'space-between',
        alignItems: 'center',
        flexWrap: 'wrap',
        gap: 8,
        marginTop: 8,
      }}
    >
      {useBackendPagination && total != null ? (
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          共 {total} {unitLabel}
          {totalPages != null ? `，${totalPages} 页` : ''}
        </Typography.Text>
      ) : (
        <span />
      )}
      <div
        style={{
          display: 'flex',
          justifyContent: 'flex-end',
          alignItems: 'center',
          flexWrap: 'wrap',
          gap: 8,
        }}
      >
        <Button
          size="small"
          disabled={page <= 1 || loading}
          onClick={() => onChange(page - 1, pageSize)}
        >
          上一页
        </Button>
        {(useBackendPagination && totalPages != null && totalPages > 0
          ? Array.from({ length: Math.min(totalPages, 7) }, (_, i) => {
              const start = Math.max(1, Math.min(page - 3, totalPages - 6));
              return start + i;
            }).filter((p) => p <= totalPages)
          : fallbackPages
        ).map((pageNum) => (
          <Button
            key={pageNum}
            size="small"
            type={pageNum === page ? 'primary' : 'default'}
            disabled={loading}
            onClick={() => {
              if (pageNum !== page) {
                onChange(pageNum, pageSize);
              }
            }}
          >
            {pageNum}
          </Button>
        ))}
        <Button
          size="small"
          disabled={!canGoNext || loading}
          onClick={() => onChange(page + 1, pageSize)}
        >
          下一页
        </Button>
        <Select
          size="small"
          value={pageSize}
          style={{ width: 128 }}
          options={pageSizeOptions.map((value) => ({
            value: Number(value),
            label: `${value} ${unitLabel}/页`,
          }))}
          onChange={(nextPageSize) => onChange(1, nextPageSize)}
        />
      </div>
    </div>
  );
}

export type DatasetPreviewPanelProps = {
  versionId?: string;
  /** 嵌入详情页时使用较小高度 */
  compact?: boolean;
  /**
   * 工作区发布版本（含 APPEND）为 true：只展示本版本样本清单，不回退主包 ZIP。
   * 独立整包上传版本保持 false，直接扫该版本主包 ZIP。
   */
  samplesOnly?: boolean;
  hierarchical?: boolean;
};

type FileTreeNode = {
  key: string;
  title: React.ReactNode;
  children?: FileTreeNode[];
  isLeaf?: boolean;
  file?: DatasetPreviewFileItem;
};

const DatasetPreviewPanel: React.FC<DatasetPreviewPanelProps> = ({
  versionId,
  compact = false,
  samplesOnly = false,
  hierarchical = false,
}) => {
  const listScrollY = compact ? 320 : 480;
  const contentMaxHeight = compact ? '45vh' : '65vh';
  const previewPadding = compact ? 48 : 80;

  const [meta, setMeta] = useState<DatasetPreviewFilesData | null>(null);
  const [files, setFiles] = useState<DatasetPreviewFileItem[]>([]);
  const [filesLoading, setFilesLoading] = useState(false);
  const [filesError, setFilesError] = useState<string | null>(null);
  const [listSource, setListSource] = useState<'sample' | 'zip'>('zip');
  const [keyword, setKeyword] = useState('');
  const [kindFilter, setKindFilter] = useState<
    DatasetPreviewFileKind | undefined
  >();
  const [filePage, setFilePage] = useState(1);
  const [filePageSize, setFilePageSize] = useState(compact ? 20 : 50);
  const [fileTotal, setFileTotal] = useState(0);

  const [selected, setSelected] = useState<DatasetPreviewFileItem | null>(null);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [previewError, setPreviewError] = useState<string | null>(null);
  const [contentData, setContentData] =
    useState<DatasetPreviewContentData | null>(null);
  const [contentPage, setContentPage] = useState(1);
  const [contentPageSize, setContentPageSize] = useState(DEFAULT_CSV_PAGE_SIZE);
  const [imageUrl, setImageUrl] = useState<string | null>(null);

  const imageUrlRef = useRef<string | null>(null);
  const loadAbortRef = useRef<AbortController | null>(null);
  const filesAbortRef = useRef<AbortController | null>(null);
  const loadSeqRef = useRef(0);

  const revokeImageUrl = useCallback(() => {
    if (imageUrlRef.current) {
      URL.revokeObjectURL(imageUrlRef.current);
      imageUrlRef.current = null;
    }
    setImageUrl(null);
  }, []);

  const resetPreviewState = useCallback(() => {
    loadAbortRef.current?.abort();
    filesAbortRef.current?.abort();
    revokeImageUrl();
    setSelected(null);
    setPreviewError(null);
    setContentData(null);
    setPreviewLoading(false);
    setKeyword('');
    setKindFilter(undefined);
    setFilePage(1);
    setMeta(null);
    setFiles([]);
    setFileTotal(0);
    setFilesError(null);
    setListSource('zip');
  }, [revokeImageUrl]);

  useEffect(() => {
    resetPreviewState();
  }, [versionId, samplesOnly, resetPreviewState]);

  useEffect(() => {
    return () => {
      loadAbortRef.current?.abort();
      filesAbortRef.current?.abort();
      revokeImageUrl();
    };
  }, [revokeImageUrl]);

  const applySampleFiles = useCallback(
    (args: {
      files: DatasetPreviewFileItem[];
      total: number;
      datasetVersionId?: string;
      type?: string;
      fileName?: string;
      page?: number;
      pageSize?: number;
    }) => {
      let nextFiles = args.files;
      const kw = keyword.trim().toLowerCase();
      if (kw) {
        nextFiles = nextFiles.filter((f) =>
          getFileLabel(f).toLowerCase().includes(kw),
        );
      }
      if (kindFilter) {
        nextFiles = nextFiles.filter((f) => f.kind === kindFilter);
      }
      setListSource('sample');
      setMeta({
        datasetVersionId: args.datasetVersionId || versionId || '',
        type: (args.type as 'CV' | 'NLP') || 'CV',
        fileName: args.fileName || '样本清单',
        sourceArchive: true,
        page: args.page ?? filePage,
        pageSize: args.pageSize ?? filePageSize,
        total: args.total,
        files: nextFiles,
      });
      setFiles(nextFiles);
      setFileTotal(args.total);
    },
    [keyword, kindFilter, versionId, filePage, filePageSize],
  );

  const loadFiles = useCallback(async () => {
    if (!versionId) return;
    if (/^dataset-asset-/i.test(versionId) || /^\d+$/.test(versionId)) {
      setFilesError(
        `当前 ID「${versionId}」是资产 ID 或 Mock 数据，不能用于预览。请使用 dataset-ver-... 版本 ID（从数据集列表进入详情会自动带上 ?versionId=）。`,
      );
      setMeta(null);
      setFiles([]);
      setFileTotal(0);
      return;
    }

    const seq = ++loadSeqRef.current;
    filesAbortRef.current?.abort();
    const controller = new AbortController();
    filesAbortRef.current = controller;
    const isStale = () =>
      controller.signal.aborted || seq !== loadSeqRef.current;

    setFilesLoading(true);
    setFilesError(null);
    try {
      if (samplesOnly) {
        let lastError: string | null = null;

        try {
          const raw = await fetchConsumerManifest(
            versionId,
            { page: filePage, pageSize: filePageSize },
            { skipErrorHandler: true, signal: controller.signal },
          );
          if (isStale()) return;
          const page = unwrapConsumerManifestPage(raw);
          if (page) {
            applySampleFiles({
              files: flattenManifestSamples(page.samples ?? []),
              total: page.totalSamples,
              datasetVersionId: page.datasetVersionId,
              type: page.type,
              fileName: page.versionLabel
                ? `版本 ${page.versionLabel}`
                : '样本清单',
              page: page.page,
              pageSize: page.pageSize,
            });
            return;
          }
          lastError = '样本消费清单返回为空';
        } catch (e: unknown) {
          if ((e as Error)?.name === 'AbortError') return;
          lastError = getApiErrorMessage(e);
        }

        try {
          const samplesRes = await fetchMultimodalSamples(
            versionId,
            { page: filePage, pageSize: Math.min(filePageSize, 20) },
            { skipErrorHandler: true, signal: controller.signal },
          );
          if (isStale()) return;
          const pageData = samplesRes?.data;
          if (pageData) {
            const summaries = pageData.data ?? [];
            const details = await Promise.all(
              summaries.map(async (sample) => {
                try {
                  const detailRes = await fetchMultimodalSampleDetail(
                    sample.sampleId,
                    { skipErrorHandler: true, signal: controller.signal },
                  );
                  return detailRes?.data;
                } catch {
                  return null;
                }
              }),
            );
            if (isStale()) return;
            const nextFiles: DatasetPreviewFileItem[] = [];
            details.forEach((detail, index) => {
              const sample = summaries[index];
              if (!detail?.data?.length) {
                nextFiles.push({
                  path: sample.externalId || sample.sampleId,
                  fileName: sample.externalId || sample.sampleId,
                  kind: DatasetPreviewFileKind.UNSUPPORTED,
                  previewAllowed: false,
                  message: '无数据项',
                  source: 'sample',
                });
                return;
              }
              detail.data.forEach((item) => {
                nextFiles.push(mapSampleDataToPreviewFile(item, sample));
              });
            });
            applySampleFiles({
              files: nextFiles,
              total: pageData.total ?? nextFiles.length,
              page: pageData.page,
              pageSize: pageData.pageSize,
            });
            return;
          }
          lastError = lastError || '样本列表返回为空';
        } catch (e: unknown) {
          if ((e as Error)?.name === 'AbortError') return;
          lastError = getApiErrorMessage(e) || lastError;
        }

        if (isStale()) return;
        setFilesError(
          lastError
            ? `无法加载本版本样本：${lastError}。已停止展示，不会回退到主包 ZIP（以免把未含 APPEND 的主包当成当前版本内容）。`
            : '无法加载本版本样本清单。已停止展示，不会回退到主包 ZIP（以免把未含 APPEND 的主包当成当前版本内容）。',
        );
        setMeta(null);
        setFiles([]);
        setFileTotal(0);
        return;
      }

      const requestedPageSize = hierarchical ? 200 : filePageSize;
      const data = await fetchDatasetPreviewFiles(
        versionId,
        {
          page: hierarchical ? 1 : filePage,
          pageSize: requestedPageSize,
          keyword: keyword.trim() || undefined,
          kind: kindFilter,
        },
        { skipErrorHandler: true, signal: controller.signal },
      );
      if (isStale()) return;
      let nextFiles = data.files ?? [];
      if (hierarchical && nextFiles.length < data.total) {
        const pages = Math.ceil(data.total / requestedPageSize);
        for (let page = 2; page <= pages; page += 1) {
          const next = await fetchDatasetPreviewFiles(
            versionId,
            {
              page,
              pageSize: requestedPageSize,
              keyword: keyword.trim() || undefined,
              kind: kindFilter,
            },
            { skipErrorHandler: true, signal: controller.signal },
          );
          if (isStale()) return;
          nextFiles = nextFiles.concat(next.files ?? []);
        }
      }
      setListSource('zip');
      setMeta(data);
      setFiles(nextFiles);
      setFileTotal(data.total ?? 0);
    } catch (e: unknown) {
      if (isStale() || (e as Error)?.name === 'AbortError') return;
      setFilesError(getApiErrorMessage(e));
      setMeta(null);
      setFiles([]);
      setFileTotal(0);
    } finally {
      if (!isStale()) {
        setFilesLoading(false);
      }
    }
  }, [
    versionId,
    samplesOnly,
    filePage,
    filePageSize,
    keyword,
    kindFilter,
    hierarchical,
    applySampleFiles,
  ]);

  useEffect(() => {
    loadFiles();
  }, [loadFiles]);

  const loadContentPreview = useCallback(
    async (
      file: DatasetPreviewFileItem,
      page: number,
      pageSize: number,
      signal: AbortSignal,
    ) => {
      if (file.sampleDataId) {
        const blob = await fetchMultimodalDataPreview(file.sampleDataId, {
          skipErrorHandler: true,
          signal,
        });
        if (signal.aborted) return;
        const text = await blob.text();
        const lines = text.split('\n');
        const start = Math.max(0, (page - 1) * pageSize);
        const slice = lines.slice(start, start + pageSize);
        setContentData({
          path: file.path,
          fileName: file.fileName,
          extension: file.extension,
          contentType: 'TEXT',
          content: slice.join('\n'),
          page,
          pageSize,
          pageable: true,
          total: lines.length,
          totalPages: Math.max(1, Math.ceil(lines.length / pageSize)),
        });
        setContentPage(page);
        setContentPageSize(pageSize);
        return;
      }
      const data = await fetchDatasetPreviewContent(
        versionId as string,
        {
          path: file.path ?? undefined,
          page,
          pageSize,
        },
        { skipErrorHandler: true, signal },
      );
      setContentData(data);
      setContentPage(data.page ?? page);
      setContentPageSize(data.pageSize ?? pageSize);
    },
    [versionId],
  );

  const loadImagePreview = useCallback(
    async (file: DatasetPreviewFileItem, signal: AbortSignal) => {
      const blob = file.sampleDataId
        ? await fetchMultimodalDataPreview(file.sampleDataId, {
            skipErrorHandler: true,
            signal,
          })
        : await getDatasetPreviewImage(versionId as string, file.path, {
            skipErrorHandler: true,
            signal,
          });
      revokeImageUrl();
      const url = URL.createObjectURL(blob);
      imageUrlRef.current = url;
      setImageUrl(url);
      setContentData(null);
    },
    [versionId, revokeImageUrl],
  );

  const openPreview = useCallback(
    async (file: DatasetPreviewFileItem) => {
      if (!versionId) return;
      setSelected(file);
      setPreviewError(null);
      setContentData(null);
      revokeImageUrl();

      if (!canPreviewFile(file)) {
        setPreviewError(file.message || '该文件不支持在线预览');
        return;
      }

      loadAbortRef.current?.abort();
      const controller = new AbortController();
      loadAbortRef.current = controller;
      setPreviewLoading(true);

      const previewKind = resolvePreviewKind(file);
      const initialPageSize =
        previewKind === DatasetPreviewFileKind.TABLE
          ? DEFAULT_CSV_PAGE_SIZE
          : DEFAULT_TEXT_PAGE_SIZE;

      try {
        if (previewKind === DatasetPreviewFileKind.IMAGE) {
          await loadImagePreview(file, controller.signal);
        } else if (
          previewKind === DatasetPreviewFileKind.TEXT ||
          previewKind === DatasetPreviewFileKind.TABLE
        ) {
          setContentPage(1);
          setContentPageSize(initialPageSize);
          await loadContentPreview(file, 1, initialPageSize, controller.signal);
        } else {
          setPreviewError(
            file.message || '该文件类型暂不支持在线预览，请下载后查看',
          );
        }
      } catch (e: unknown) {
        if ((e as Error)?.name !== 'AbortError') {
          setPreviewError(getApiErrorMessage(e));
        }
      } finally {
        setPreviewLoading(false);
      }
    },
    [versionId, loadContentPreview, loadImagePreview, revokeImageUrl],
  );

  const goToContentPage = useCallback(
    async (page: number, pageSize: number) => {
      if (!selected || !versionId) return;
      const previewKind = resolvePreviewKind(selected);
      if (
        previewKind !== DatasetPreviewFileKind.TABLE &&
        previewKind !== DatasetPreviewFileKind.TEXT
      ) {
        return;
      }
      loadAbortRef.current?.abort();
      const controller = new AbortController();
      loadAbortRef.current = controller;
      setPreviewLoading(true);
      setPreviewError(null);
      try {
        await loadContentPreview(selected, page, pageSize, controller.signal);
      } catch (e: unknown) {
        if ((e as Error)?.name !== 'AbortError') {
          setPreviewError(getApiErrorMessage(e));
        }
      } finally {
        setPreviewLoading(false);
      }
    },
    [selected, versionId, loadContentPreview],
  );

  const fileColumns: ColumnsType<DatasetPreviewFileItem> = [
    {
      title: '文件名',
      dataIndex: 'fileName',
      key: 'fileName',
      ellipsis: true,
      render: (_, record) => <TableEllipsisCell text={getFileLabel(record)} />,
    },
    {
      title: '类型',
      dataIndex: 'kind',
      key: 'kind',
      width: 88,
      render: (kind: DatasetPreviewFileKind) => (
        <Tag color={KIND_COLORS[kind] ?? 'default'}>{kind}</Tag>
      ),
    },
    {
      title: '大小',
      dataIndex: 'sizeBytes',
      key: 'sizeBytes',
      width: 88,
      render: (v: number | null) => formatBytes(v),
    },
    {
      title: '预览',
      key: 'preview',
      width: 72,
      render: (_, record) => (
        <Typography.Link
          disabled={!canPreviewFile(record)}
          onClick={(e) => {
            e.stopPropagation();
            openPreview(record);
          }}
        >
          查看
        </Typography.Link>
      ),
    },
  ];

  const fileTree = useMemo<FileTreeNode[]>(() => {
    const roots: FileTreeNode[] = [];
    const directories = new Map<string, FileTreeNode>();
    for (const file of files) {
      const path = (file.path || file.fileName).replace(/\\/g, '/');
      const parts = path.split('/').filter(Boolean);
      let children = roots;
      let parentPath = '';
      parts.forEach((part, index) => {
        const nodePath = parentPath ? `${parentPath}/${part}` : part;
        const leaf = index === parts.length - 1;
        if (leaf) {
          children.push({
            key: `file:${nodePath}`,
            isLeaf: true,
            file,
            title: (
              <Space size={6}>
                <FileOutlined />
                <Typography.Text
                  ellipsis={{ tooltip: nodePath }}
                  style={{ maxWidth: 240 }}
                >
                  {part}
                </Typography.Text>
                <Tag color={KIND_COLORS[file.kind] ?? 'default'}>
                  {file.kind}
                </Tag>
                <Typography.Text type="secondary">
                  {formatBytes(file.sizeBytes)}
                </Typography.Text>
              </Space>
            ),
          });
        } else {
          let directory = directories.get(nodePath);
          if (!directory) {
            directory = {
              key: `dir:${nodePath}`,
              title: (
                <Space size={6}>
                  <FolderOpenOutlined />
                  {part}
                </Space>
              ),
              children: [],
            };
            directories.set(nodePath, directory);
            children.push(directory);
          }
          children = directory.children as FileTreeNode[];
        }
        parentPath = nodePath;
      });
    }
    return roots;
  }, [files]);

  const csvColumns =
    contentData?.columns?.map((col, index) => ({
      title: col,
      dataIndex: index,
      key: `${col}-${index}`,
      ellipsis: true,
    })) ?? [];

  const csvDataSource =
    contentData?.rows?.map((row, rowIndex) => {
      const record: Record<string, string> = {
        key: String((contentPage - 1) * contentPageSize + rowIndex),
      };
      row.forEach((cell, colIndex) => {
        record[colIndex] = cell;
      });
      return record;
    }) ?? [];

  const csvRowCount = contentData?.rows?.length ?? 0;
  const contentUseBackendPagination = isContentBackendPagination(contentData);
  const csvHasMoreRows = csvRowCount >= contentPageSize;
  const textLineCount = countTextLines(contentData?.content);
  const textHasMoreRows = textLineCount >= contentPageSize;
  const showTextPreview =
    !!selected &&
    !!contentData &&
    !isMarkdownFile(selected) &&
    isTextContentPreview(selected, contentData);
  const showMarkdownPreview =
    !!selected &&
    !!contentData &&
    isMarkdownFile(selected) &&
    contentData.content != null;

  const handleCsvTableChange = (pagination: TablePaginationConfig) => {
    void goToContentPage(
      pagination.current ?? 1,
      pagination.pageSize ?? contentPageSize,
    );
  };

  if (!versionId) {
    return <Empty description="请选择要预览的数据集版本" />;
  }

  const metaHint = meta
    ? `${meta.type} · ${meta.fileName}${meta.sourceArchive ? '（压缩包）' : ''} · ${
        listSource === 'sample' ? '本版本样本' : '主包 ZIP'
      } · ${meta.datasetVersionId || versionId}`
    : null;

  return (
    <div>
      {metaHint && (
        <Typography.Text
          type="secondary"
          style={{ display: 'block', marginBottom: 12 }}
        >
          {metaHint}
        </Typography.Text>
      )}
      {filesError && (
        <Alert
          type="error"
          showIcon
          message={filesError}
          style={{ marginBottom: 16 }}
        />
      )}

      <Row gutter={16}>
        <Col xs={24} lg={10}>
          <Space
            direction="vertical"
            style={{ width: '100%', marginBottom: 12 }}
          >
            <Input.Search
              allowClear
              placeholder="按路径 / 文件名 / 扩展名搜索"
              onSearch={(v) => {
                setKeyword(v);
                setFilePage(1);
              }}
            />
            <Select
              allowClear
              placeholder="按类型筛选"
              style={{ width: '100%' }}
              options={KIND_OPTIONS}
              value={kindFilter}
              onChange={(v) => {
                setKindFilter(v);
                setFilePage(1);
              }}
            />
            <Typography.Text type="secondary">
              共 {fileTotal} 项
            </Typography.Text>
          </Space>
          {hierarchical && listSource === 'zip' ? (
            <div
              style={{
                maxHeight: listScrollY,
                overflow: 'auto',
                border: '1px solid #f0f0f0',
                padding: 8,
              }}
            >
              <Spin spinning={filesLoading}>
                {fileTree.length ? (
                  <Tree<FileTreeNode>
                    blockNode
                    defaultExpandAll
                    treeData={fileTree}
                    selectedKeys={
                      selected
                        ? [`file:${selected.path || selected.fileName}`]
                        : []
                    }
                    onSelect={(_, info) => {
                      const file = info.node.file;
                      if (file && canPreviewFile(file)) openPreview(file);
                    }}
                  />
                ) : (
                  <Empty description="暂无文件或当前筛选无结果" />
                )}
              </Spin>
            </div>
          ) : (
            <Table<DatasetPreviewFileItem>
              size="small"
              rowKey={(row) => row.path ?? row.fileName}
              loading={filesLoading}
              columns={fileColumns}
              dataSource={files}
              pagination={{
                current: filePage,
                pageSize: filePageSize,
                total: fileTotal,
                showSizeChanger: true,
                pageSizeOptions: ['20', '50', '100', '200'],
                onChange: (page, pageSize) => {
                  setFilePage(page);
                  setFilePageSize(pageSize);
                },
              }}
              onRow={(record) => {
                const key = record.path ?? record.fileName;
                const selKey = selected?.path ?? selected?.fileName;
                const active = key === selKey;
                return {
                  onClick: () => {
                    if (canPreviewFile(record)) openPreview(record);
                  },
                  style: {
                    cursor: canPreviewFile(record) ? 'pointer' : 'default',
                    background: active ? '#e6f4ff' : undefined,
                  },
                };
              }}
              locale={{ emptyText: '暂无文件或当前筛选无结果' }}
              scroll={{ y: listScrollY }}
            />
          )}
        </Col>

        <Col xs={24} lg={14}>
          <Typography.Title level={5} style={{ marginTop: 0 }}>
            {selected ? selected.fileName : '预览区'}
          </Typography.Title>
          {!selected && (
            <Empty
              description="请从左侧选择可预览的文件"
              style={{ padding: previewPadding }}
            />
          )}
          {selected && previewError && (
            <Alert type="warning" showIcon message={previewError} />
          )}
          {selected && previewLoading && (
            <div style={{ textAlign: 'center', padding: previewPadding }}>
              <Spin size="large" tip="加载预览…" />
            </div>
          )}
          {selected &&
            !previewLoading &&
            !previewError &&
            imageUrl &&
            resolvePreviewKind(selected) === DatasetPreviewFileKind.IMAGE && (
              <div
                style={{
                  textAlign: 'center',
                  maxHeight: contentMaxHeight,
                  overflow: 'auto',
                }}
              >
                <Image
                  src={imageUrl}
                  alt={selected.fileName}
                  style={{ maxWidth: '100%' }}
                />
              </div>
            )}
          {selected &&
            !previewLoading &&
            !previewError &&
            contentData &&
            showMarkdownPreview && (
              <Space direction="vertical" style={{ width: '100%' }}>
                {contentData.truncated && (
                  <Alert
                    type="info"
                    showIcon
                    message="内容已截断，仅渲染允许读取的最大范围"
                  />
                )}
                {contentData.message && (
                  <Alert type="info" showIcon message={contentData.message} />
                )}
                <MarkdownPreview
                  content={contentData.content ?? ''}
                  maxHeight={contentMaxHeight}
                />
              </Space>
            )}
          {selected &&
            !previewLoading &&
            !previewError &&
            contentData &&
            showTextPreview && (
              <Space direction="vertical" style={{ width: '100%' }}>
                {contentData.truncated && (
                  <Alert
                    type="info"
                    showIcon
                    message="内容已截断，仅展示允许的最大可读范围"
                  />
                )}
                {contentData.message && (
                  <Alert type="info" showIcon message={contentData.message} />
                )}
                <pre
                  style={{
                    margin: 0,
                    padding: 12,
                    maxHeight: contentMaxHeight,
                    overflow: 'auto',
                    background: '#fafafa',
                    borderRadius: 8,
                    fontSize: 12,
                    lineHeight: 1.5,
                    whiteSpace: 'pre-wrap',
                    wordBreak: 'break-word',
                  }}
                >
                  {contentData.content ?? ''}
                </pre>
                <ContentPageControls
                  page={contentPage}
                  pageSize={contentPageSize}
                  loading={previewLoading}
                  useBackendPagination={contentUseBackendPagination}
                  total={contentData.total}
                  totalPages={contentData.totalPages}
                  hasMoreRows={textHasMoreRows}
                  pageSizeOptions={TEXT_PAGE_SIZE_OPTIONS}
                  unitLabel="行"
                  onChange={(page, pageSize) => {
                    void goToContentPage(page, pageSize);
                  }}
                />
              </Space>
            )}
          {selected &&
            !previewLoading &&
            !previewError &&
            contentData &&
            contentData.contentType === 'CSV' &&
            !isJsonlFile(selected) && (
              <Space direction="vertical" style={{ width: '100%' }}>
                {contentData.truncated && (
                  <Alert
                    type="info"
                    showIcon
                    message="内容已按最大可读字节截断；total 仅表示本次可预览范围内的行数，不代表完整文件总行数"
                  />
                )}
                {contentData.message && (
                  <Alert type="info" showIcon message={contentData.message} />
                )}
                <Table
                  size="small"
                  columns={csvColumns}
                  dataSource={csvDataSource}
                  pagination={
                    contentUseBackendPagination
                      ? {
                          current: contentPage,
                          pageSize: contentPageSize,
                          total: contentData.total,
                          showSizeChanger: true,
                          hideOnSinglePage: (contentData.totalPages ?? 0) > 1,
                          pageSizeOptions: CSV_PAGE_SIZE_OPTIONS,
                          showTotal: (total) => {
                            const pages =
                              contentData.totalPages ??
                              Math.max(1, Math.ceil(total / contentPageSize));
                            return `共 ${total} 条，${pages} 页`;
                          },
                        }
                      : false
                  }
                  onChange={
                    contentUseBackendPagination
                      ? handleCsvTableChange
                      : undefined
                  }
                  scroll={{ x: 'max-content', y: compact ? 280 : 400 }}
                />
                {!contentUseBackendPagination && (
                  <ContentPageControls
                    page={contentPage}
                    pageSize={contentPageSize}
                    loading={previewLoading}
                    useBackendPagination={false}
                    hasMoreRows={csvHasMoreRows}
                    pageSizeOptions={CSV_PAGE_SIZE_OPTIONS}
                    unitLabel="条"
                    onChange={(page, pageSize) => {
                      void goToContentPage(page, pageSize);
                    }}
                  />
                )}
              </Space>
            )}
        </Col>
      </Row>
    </div>
  );
};

export default DatasetPreviewPanel;
