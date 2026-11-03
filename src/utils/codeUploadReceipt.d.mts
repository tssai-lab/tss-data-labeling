export type CodeUploadReceipt = {
  codeVersionId: string;
  codeAssetId?: string;
  codeAssetName?: string;
  fileName?: string;
  trainingProfile?: string;
  approvalStatus: string;
  storagePath?: string;
  sizeBytes?: number;
  source: 'upload';
};

export function buildCodeUploadReceipt(
  data?: {
    codeVersionId?: string;
    codeAssetId?: string;
    fileName?: string;
    trainingProfile?: string;
    approvalStatus?: string;
    storagePath?: string;
    sizeBytes?: number;
  },
  metadata?: {
    codeName?: string;
    fileName?: string;
    trainingProfile?: string;
  },
): CodeUploadReceipt | undefined;

export function persistSuccessfulCodeUpload(
  response:
    | {
        success?: boolean;
        data?: Parameters<typeof buildCodeUploadReceipt>[0];
      }
    | undefined,
  metadata: Parameters<typeof buildCodeUploadReceipt>[1],
  persist: (receipt: CodeUploadReceipt) => unknown,
): CodeUploadReceipt | undefined;

export function shouldFallbackToLegacyCodeUpload(error: unknown): boolean;
