import { isLegacyEndpointUnavailable } from './apiCompatibility.mjs';

function nonBlank(value) {
  return typeof value === 'string' && value.trim() ? value.trim() : undefined;
}

/**
 * Build the client-side receipt used only as a list fallback.
 * The server-side code_asset/code_version rows remain authoritative.
 */
export function buildCodeUploadReceipt(data, metadata) {
  const codeVersionId = nonBlank(data?.codeVersionId);
  if (!codeVersionId) return undefined;

  return {
    codeVersionId,
    codeAssetId: nonBlank(data?.codeAssetId),
    codeAssetName: nonBlank(metadata?.codeName),
    fileName: nonBlank(data?.fileName) || nonBlank(metadata?.fileName),
    trainingProfile:
      nonBlank(data?.trainingProfile) || nonBlank(metadata?.trainingProfile),
    approvalStatus: nonBlank(data?.approvalStatus) || 'PENDING',
    storagePath: nonBlank(data?.storagePath),
    sizeBytes:
      Number.isFinite(data?.sizeBytes) && data.sizeBytes >= 0
        ? data.sizeBytes
        : undefined,
    source: 'upload',
  };
}

/** Persist only a confirmed successful upload; rejected/failed responses are ignored. */
export function persistSuccessfulCodeUpload(response, metadata, persist) {
  if (
    !response ||
    response.success === false ||
    typeof persist !== 'function'
  ) {
    return undefined;
  }
  const receipt = buildCodeUploadReceipt(response.data, metadata);
  if (!receipt) return undefined;
  try {
    persist(receipt);
  } catch {
    // The server has already persisted the asset. A browser storage failure
    // must never retry the upload and create a duplicate server-side asset.
  }
  return receipt;
}

/**
 * Legacy upload is a compatibility path, not a retry path. Only fall back
 * when the server explicitly says that the V2 endpoint is unavailable.
 * Timeouts and 5xx responses are ambiguous: the V2 request may already have
 * persisted the asset, so replaying the ZIP could create a duplicate.
 */
export function shouldFallbackToLegacyCodeUpload(error) {
  return isLegacyEndpointUnavailable(error);
}
