import { Alert, Button, Empty, Spin, Typography } from 'antd';
import React, { useEffect, useState } from 'react';
import MarkdownPreview from '@/pages/dataset/components/MarkdownPreview';
import {
  fetchDatasetPreviewContent,
  fetchDatasetPreviewFiles,
} from '@/services/datasetPreview';
import {
  type ConsumerManifestPage,
  type ConsumerManifestSample,
  fetchConsumerManifest,
  fetchMultimodalDataPreview,
  fetchMultimodalSampleDetail,
  fetchMultimodalSamples,
  listModelCodeFiles,
  type MultimodalSampleDataItem,
  previewModelCode,
} from '@/services/platform';
import { isLegacyEndpointUnavailable } from '@/utils/apiCompatibility.mjs';
import { getApiErrorMessage } from '@/utils/apiError';
import { findReadmeNamedFile, findReadmePath } from '@/utils/readmePath';

export type ZipReadmeSource = 'model' | 'dataset';

type ZipReadmePanelProps = {
  source: ZipReadmeSource;
  versionId?: string;
  /** 模型侧可直接传入已加载的文件路径，避免重复请求 code-files */
  filePaths?: string[];
  /** 数据集类型：多模态走样本预览，不能打 CV/NLP/LeRobot 通用 preview */
  datasetType?: string;
};

type LoadState =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'empty'; message: string }
  | { status: 'ready'; path: string; content: string }
  | { status: 'error'; message: string };

const COMMON_DATASET_PREVIEW_TYPES = new Set(['CV', 'NLP', 'LEROBOT']);
const MULTIMODAL_README_PAGE_SIZE = 50;
const MULTIMODAL_README_MAX_PAGES = 10;

async function loadModelReadme(
  versionId: string,
  filePaths?: string[],
): Promise<LoadState> {
  let paths = filePaths;
  if (!paths?.length) {
    const res = await listModelCodeFiles(versionId, {
      skipErrorHandler: true,
    });
    paths = (res?.data ?? [])
      .map((f) => f.path || f.fileName)
      .filter(Boolean) as string[];
  }
  const readmePath = findReadmePath(paths);
  if (!readmePath) {
    return {
      status: 'empty',
      message: '当前版本未包含 README.md',
    };
  }
  const previewRes = await previewModelCode(versionId, readmePath, {
    skipErrorHandler: true,
  });
  const content = previewRes?.data?.content;
  if (!content?.trim()) {
    return {
      status: 'empty',
      message: `已找到 ${readmePath}，但内容为空`,
    };
  }
  return { status: 'ready', path: readmePath, content };
}

function unwrapConsumerManifestPage(raw: unknown): ConsumerManifestPage | null {
  if (!raw || typeof raw !== 'object') return null;
  const obj = raw as Record<string, unknown>;
  if (Array.isArray(obj.samples)) {
    return raw as ConsumerManifestPage;
  }
  if (obj.data && typeof obj.data === 'object') {
    const nested = obj.data as Record<string, unknown>;
    if (Array.isArray(nested.samples)) {
      return nested as unknown as ConsumerManifestPage;
    }
  }
  return null;
}

function unwrapBlob(raw: unknown): Blob | null {
  if (raw instanceof Blob) return raw;
  if (
    raw &&
    typeof raw === 'object' &&
    (raw as { data?: unknown }).data instanceof Blob
  ) {
    return (raw as { data: Blob }).data;
  }
  return null;
}

function sampleDataLabel(item: MultimodalSampleDataItem): string {
  const meta = item.metadata ?? undefined;
  const metaPath = typeof meta?.path === 'string' ? meta.path : '';
  const metaName = typeof meta?.fileName === 'string' ? meta.fileName : '';
  return (item.fileName || metaPath || metaName || item.sampleDataId).trim();
}

async function collectSampleDataItems(
  samples: ConsumerManifestSample[],
): Promise<MultimodalSampleDataItem[]> {
  const items: MultimodalSampleDataItem[] = [];
  for (const sample of samples) {
    if (sample.data?.length) {
      items.push(...sample.data);
      continue;
    }
    if (!sample.sampleId) continue;
    const res = await fetchMultimodalSampleDetail(sample.sampleId, {
      skipErrorHandler: true,
    });
    const detail = res?.data;
    if (detail?.data?.length) {
      items.push(...detail.data);
    }
  }
  return items;
}

async function readSampleDataText(
  item: MultimodalSampleDataItem,
): Promise<string> {
  const raw = await fetchMultimodalDataPreview(item.sampleDataId, {
    skipErrorHandler: true,
  });
  const blob = unwrapBlob(raw);
  if (!blob) return '';
  return blob.text();
}

async function loadMultimodalReadme(versionId: string): Promise<LoadState> {
  let samples: ConsumerManifestSample[] = [];
  let usedManifest = false;

  try {
    for (let page = 1; page <= MULTIMODAL_README_MAX_PAGES; page += 1) {
      const raw = await fetchConsumerManifest(
        versionId,
        { page, pageSize: MULTIMODAL_README_PAGE_SIZE },
        { skipErrorHandler: true },
      );
      const manifest = unwrapConsumerManifestPage(raw);
      const pageSamples = manifest?.samples ?? [];
      samples = samples.concat(pageSamples);
      usedManifest = true;
      const total = manifest?.totalSamples ?? samples.length;
      if (samples.length >= total || pageSamples.length === 0) {
        break;
      }
    }
  } catch (error) {
    if (!isLegacyEndpointUnavailable(error)) throw error;
    usedManifest = false;
    samples = [];
  }

  if (!usedManifest || !samples.length) {
    const res = await fetchMultimodalSamples(
      versionId,
      { page: 1, pageSize: 100 },
      { skipErrorHandler: true },
    );
    const list = res?.data?.data ?? [];
    samples = list.map((sample) => ({
      ...sample,
      data: [],
      annotations: [],
    }));
  }

  if (!samples.length) {
    return {
      status: 'empty',
      message: '当前版本未包含 README.md',
    };
  }

  const dataItems = await collectSampleDataItems(samples);
  const readmeItem = findReadmeNamedFile(dataItems);
  if (!readmeItem) {
    return {
      status: 'empty',
      message: '当前版本未包含 README.md',
    };
  }

  const content = await readSampleDataText(readmeItem);
  const path = sampleDataLabel(readmeItem);
  if (!content?.trim()) {
    return {
      status: 'empty',
      message: `已找到 ${path}，但内容为空`,
    };
  }
  return { status: 'ready', path, content };
}

async function loadCommonDatasetReadme(versionId: string): Promise<LoadState> {
  const filesPage = await fetchDatasetPreviewFiles(
    versionId,
    { page: 1, pageSize: 200, keyword: 'readme' },
    { skipErrorHandler: true },
  );
  const paths = (filesPage.files ?? []).map((f) => f.path || f.fileName || '');
  let readmePath = findReadmePath(paths);

  // keyword 过滤可能漏掉根目录 README，再拉一页全量补一次
  if (!readmePath) {
    const allPage = await fetchDatasetPreviewFiles(
      versionId,
      { page: 1, pageSize: 200 },
      { skipErrorHandler: true },
    );
    readmePath = findReadmePath(
      (allPage.files ?? []).map((f) => f.path || f.fileName || ''),
    );
  }

  if (!readmePath) {
    return {
      status: 'empty',
      message: '当前版本未包含 README.md',
    };
  }

  const contentRes = await fetchDatasetPreviewContent(
    versionId,
    { path: readmePath },
    { skipErrorHandler: true },
  );
  const content = contentRes.content;
  if (!content?.trim()) {
    return {
      status: 'empty',
      message: `已找到 ${readmePath}，但内容为空`,
    };
  }
  return { status: 'ready', path: readmePath, content };
}

function isCommonPreviewUnsupportedError(message: string): boolean {
  return /only supports CV, NLP, or LEROBOT/i.test(message);
}

async function loadDatasetReadme(
  versionId: string,
  datasetType?: string,
): Promise<LoadState> {
  const type = (datasetType || '').trim().toUpperCase();
  if (type === 'MULTIMODAL') {
    return loadMultimodalReadme(versionId);
  }
  if (type === 'POINT_CLOUD') {
    return {
      status: 'empty',
      message:
        '点云数据集请使用专用 3D 预览；当前类型不走通用文件预览，无法在此展示 README.md',
    };
  }
  if (type === 'ROBOT' || (type && !COMMON_DATASET_PREVIEW_TYPES.has(type))) {
    return {
      status: 'empty',
      message:
        '通用数据集 README 预览仅支持 CV、NLP、LeRobot；多模态请使用样本预览接口。当前类型无法在此展示 README.md',
    };
  }

  try {
    return await loadCommonDatasetReadme(versionId);
  } catch (error: unknown) {
    const message = getApiErrorMessage(error, '读取 README.md 失败');
    if (isCommonPreviewUnsupportedError(message)) {
      return loadMultimodalReadme(versionId);
    }
    throw error;
  }
}

/**
 * 从模型/数据集版本 zip 中读取 README.md 并 Markdown 渲染。
 */
const ZipReadmePanel: React.FC<ZipReadmePanelProps> = ({
  source,
  versionId,
  filePaths,
  datasetType,
}) => {
  const [state, setState] = useState<LoadState>({ status: 'idle' });
  const [readAttempt, setReadAttempt] = useState(0);

  useEffect(() => {
    if (!versionId) {
      setState({
        status: 'empty',
        message: '请先选择一个版本以查看 README',
      });
      return;
    }

    let cancelled = false;
    setState({ status: 'loading' });

    (async () => {
      try {
        const next =
          source === 'model'
            ? await loadModelReadme(versionId, filePaths)
            : await loadDatasetReadme(versionId, datasetType);
        if (!cancelled) setState(next);
      } catch (error: unknown) {
        if (!cancelled) {
          setState({
            status: 'error',
            message: getApiErrorMessage(error, '读取 README.md 失败'),
          });
        }
      }
    })();

    return () => {
      cancelled = true;
    };
    // filePaths 用 join 稳定依赖，避免父组件每次渲染新数组导致重复请求
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [source, versionId, datasetType, filePaths?.join('\0'), readAttempt]);

  if (state.status === 'loading' || state.status === 'idle') {
    return (
      <div style={{ textAlign: 'center', padding: 48 }}>
        <Spin tip="加载 README…" />
      </div>
    );
  }

  if (state.status === 'error') {
    return (
      <Alert
        type="error"
        showIcon
        message="README 读取失败"
        description={state.message}
        action={
          <Button onClick={() => setReadAttempt((n) => n + 1)}>重试读取</Button>
        }
      />
    );
  }
  if (state.status === 'empty') {
    return (
      <Empty
        image={Empty.PRESENTED_IMAGE_SIMPLE}
        description={state.message}
        style={{ padding: 48 }}
      />
    );
  }

  return (
    <div>
      <Typography.Text
        type="secondary"
        style={{ display: 'block', marginBottom: 12, fontSize: 12 }}
      >
        来源：{state.path}
      </Typography.Text>
      <div
        style={{
          border: '1px solid #f0f0f0',
          borderRadius: 8,
          padding: 16,
          background: '#fff',
        }}
      >
        <MarkdownPreview content={state.content} maxHeight="70vh" />
      </div>
    </div>
  );
};

export default ZipReadmePanel;
