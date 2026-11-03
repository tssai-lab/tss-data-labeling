import { UPLOAD_CONFIG } from '@/constants/platform';
import {
  modelUploadChunk,
  modelUploadComplete,
  modelUploadInit,
} from '@/services/platform';
import { buildModelFileFingerprint } from '@/utils/uploadResume';

const CHUNK_FALLBACK = 5 * 1024 * 1024;

export type UploadModelZipParams = {
  file: File;
  modelName: string;
  version: string;
  type: string;
  remark: string;
  /** 不传则复用 remark */
  commitInfo?: string;
  hyperParams?: Record<string, unknown>;
  assetId?: string;
  onProgress?: (percent: number) => void;
  onUploadSession?: (payload: {
    uploadId: string;
    fileFingerprint: string;
  }) => void;
  requestOpts?: { skipErrorHandler?: boolean };
};

export type UploadModelZipResult = {
  modelVersionId: string;
  assetId?: string;
  name: string;
  version: string;
  type: string;
};

/** 与「上传模型」页一致的分片上传流程 */
export async function uploadModelZipPackage(
  params: UploadModelZipParams,
): Promise<UploadModelZipResult> {
  const {
    file,
    modelName,
    version,
    type,
    remark,
    commitInfo: commitInfoInput,
    hyperParams,
    assetId,
    onProgress,
    onUploadSession,
    requestOpts,
  } = params;

  if (
    !UPLOAD_CONFIG.MODEL.ACCEPT_TYPES.some((suffix) =>
      file.name.toLowerCase().endsWith(suffix),
    )
  ) {
    throw new Error('模型仅支持 .zip、.safetensors、.pt、.pth、.ckpt 或 .onnx');
  }
  if (file.size > UPLOAD_CONFIG.MODEL.MAX_SIZE) {
    throw new Error(
      `文件大小不能超过 ${UPLOAD_CONFIG.MODEL.MAX_SIZE / 1024 / 1024 / 1024}GB`,
    );
  }

  const commitInfo = String(commitInfoInput ?? remark ?? '').trim();
  if (!commitInfo) {
    throw new Error('commitInfo 不能为空');
  }
  const fileFingerprint = buildModelFileFingerprint(
    file,
    modelName,
    version,
    type,
  );
  const initRes = await modelUploadInit(
    {
      fileName: file.name,
      fileSize: file.size,
      fileFingerprint,
      commitInfo,
      modelName: modelName.trim(),
      modelVersion: version.trim(),
      taskType: type,
      remark: String(remark ?? '').trim() || commitInfo,
      ...(assetId ? { targetAssetId: assetId } : {}),
      ...(hyperParams ? { hyperParams } : {}),
    },
    requestOpts,
  );
  const initData = initRes?.data as API.ModelUploadInitResult | undefined;
  const uploadId = initData?.uploadId;
  if (!uploadId) {
    throw new Error('初始化上传失败：缺少 uploadId');
  }

  onUploadSession?.({ uploadId, fileFingerprint });

  const chunkSize =
    initData?.chunkSize && initData.chunkSize > 0
      ? initData.chunkSize
      : CHUNK_FALLBACK;
  const totalChunks =
    initData?.totalChunks && initData.totalChunks > 0
      ? initData.totalChunks
      : Math.max(1, Math.ceil(file.size / chunkSize));

  const uploaded = new Set(initData?.uploadedPartIndexes ?? []);
  let finishedParts = uploaded.size;
  onProgress?.(Math.min(100, Math.round((finishedParts / totalChunks) * 100)));

  for (let partIndex = 0; partIndex < totalChunks; partIndex += 1) {
    if (uploaded.has(partIndex)) {
      continue;
    }
    const start = partIndex * chunkSize;
    const end = Math.min(start + chunkSize, file.size);
    await modelUploadChunk(
      uploadId,
      partIndex,
      file.slice(start, end),
      requestOpts,
    );
    finishedParts += 1;
    onProgress?.(
      Math.min(100, Math.round((finishedParts / totalChunks) * 100)),
    );
  }

  const completeRes = await modelUploadComplete(
    {
      uploadId,
      modelName: modelName.trim(),
      version: version.trim(),
      type,
      remark: String(remark ?? '').trim() || commitInfo,
      commitInfo,
      ...(hyperParams ? { hyperParams } : {}),
      ...(assetId ? { assetId } : {}),
    },
    requestOpts,
  );
  const completed = completeRes?.data as
    | {
        id?: string;
        assetId?: string;
        name?: string;
        version?: string;
        type?: string;
      }
    | undefined;
  const modelVersionId = completed?.id;
  if (!modelVersionId) {
    throw new Error('上传完成但缺少模型版本 ID');
  }

  return {
    modelVersionId,
    assetId: completed?.assetId,
    name: completed?.name ?? modelName.trim(),
    version: completed?.version ?? version.trim(),
    type: completed?.type ?? type,
  };
}
