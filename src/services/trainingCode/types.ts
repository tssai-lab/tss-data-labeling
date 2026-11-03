/** 旧入口兼容类型。仅整理职责；权限、版本 CAS、重试及兼容规则沿用原实现。 */


export const CONSISTENCY_TRAINING_PROFILE = 'image_text_consistency_fusion_logreg';

export type CodeUploadResult = {
  codeAssetId: string;
  codeVersionId: string;
  version: string;
  fileName: string;
  storagePath: string;
  sizeBytes: number;
  trainingProfile: string;
  status: string;
  approvalStatus: string;
};

export type CodeVersionApprovalResult = {
  codeVersionId: string;
  approvalStatus: string;
  decisionSource?: string;
};

export type CodeVersionListItem = {
  codeVersionId: string;
  codeAssetId: string;
  /** 用户填写的代码名称（优先展示） */
  codeName?: string;
  codeAssetName: string;
  version: string;
  fileName: string;
  trainingProfile: string;
  approvalStatus: string;
  status: string;
  validationStatus?: string;
  validationPolicyVersion?: string;
  artifactSha256?: string;
  riskAssessmentId?: string;
  riskStatus?: string;
  riskLevel?: string;
  reviewDisposition?: string;
  riskPolicyVersion?: string;
  /** 上传/创建时间（优先展示） */
  createdAt?: string;
  /** 与 createdAt 同源，待审队列等场景沿用此字段名 */
  submittedAt?: string;
  /** 资产归属用户 ID（管理员跨用户视图） */
  ownerUserId?: number | string;
};

export type CodeVersionTrainingCheckResult = {
  codeVersionId: string;
  trainingProfile: string;
  trainingProfileDisplayName?: string;
  passed: boolean;
  reused?: boolean;
  approvalStatus?: string;
  validationStatus?: string;
  validationPolicyVersion?: string;
  artifactSha256?: string;
  reasonCode?: string;
  reasons?: string[];
  checkedAt?: string;
};

export type CodeVersionDetail = CodeVersionListItem & {
  sizeBytes?: number;
  remark?: string;
  artifactSha256?: string;
  entryScript?: string;
  runtime?: string;
  purpose?: string;
  trainingType?: string;
  consumerManifest?: {
    validationRunId?: string;
    validationPolicyVersion?: string;
    approvalRecordId?: string;
    approvalSource?: string;
    riskAssessmentId?: string;
    riskLevel?: string;
    riskPolicyVersion?: string;
  };
  riskAssessment?: {
    id?: string;
    validationRunId?: string;
    artifactSha256?: string;
    riskPolicyVersion?: string;
    status?: string;
    riskLevel?: string;
    disposition?: string;
    findingCount?: number;
    reasonCode?: string;
    findings?: Array<{
      ruleId?: string;
      severity?: string;
      category?: string;
      filePath?: string;
      lineStart?: number;
      lineEnd?: number;
      description?: string;
    }>;
  };
};

export type CodeVersionPreviewBundle = {
  codeFiles: API.ModelCodeFile[];
  codeContent?: string;
  codeFileName?: string;
  codeFilePath?: string;
  loadError?: string;
};

export type SaveCodeVersionFileResult = {
  workspaceId: string;
  publishedVersionId: string;
  publishedVersion?: string;
  path: string;
};

export type AdminCodeWorkspaceSaveResult = {
  workspaceId: string;
  workspaceRevision: number;
  path: string;
};

export type AdminCodeWorkspacePublishResult = {
  workspaceId: string;
  publishedVersionId: string;
  publishedVersion?: string;
  path: string;
};
