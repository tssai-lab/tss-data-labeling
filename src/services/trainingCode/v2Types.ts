/** V2 数据契约及下载超时常量。仅整理职责；权限、版本 CAS、重试及兼容规则沿用原实现。 */
import { FILE_DOWNLOAD_REQUEST_TIMEOUT } from '@/constants/request';


/** @deprecated 请改用 FILE_DOWNLOAD_REQUEST_TIMEOUT */
export const CODE_DOWNLOAD_REQUEST_TIMEOUT = FILE_DOWNLOAD_REQUEST_TIMEOUT;

export type V2CodeLanguageId =
  | 'python'
  | 'json'
  | 'yaml'
  | 'markdown'
  | 'plaintext'
  | string;

export type V2CodeVersionStatus = 'READY' | 'DEPRECATED' | 'ARCHIVED' | string;

export type V2CodeValidationStatus = 'NOT_RUN' | 'PASSED' | 'FAILED' | string;

export type V2CodeApprovalStatus =
  | 'PENDING'
  | 'APPROVED'
  | 'REJECTED'
  | 'REVOKED'
  | string;

export type V2CodeAsset = {
  id?: string;
  name?: string;
  trainingProfile?: string;
  purpose?: string;
  runtime?: string;
  entryScript?: string;
  trainingType?: string;
  remark?: string;
  assetRevision?: number;
  createdAt?: string;
  updatedAt?: string;
  hasOpenWorkspace?: boolean;
};

export type V2CodeVersion = {
  versionId?: string;
  id?: string;
  codeVersionId?: string;
  assetId?: string;
  codeAssetId?: string;
  assetName?: string;
  codeAssetName?: string;
  codeName?: string;
  name?: string;
  version?: string;
  versionLabel?: string;
  fileName?: string;
  trainingProfile?: string;
  status?: V2CodeVersionStatus;
  approvalStatus?: V2CodeApprovalStatus;
  validationStatus?: V2CodeValidationStatus;
  validationPolicyVersion?: string;
  sizeBytes?: number;
  artifactSha256?: string;
  remark?: string;
  entryScript?: string;
  createdAt?: string;
  publishedAt?: string;
  updatedAt?: string;
  riskAssessmentId?: string;
  riskStatus?: string;
  riskLevel?: string;
  reviewDisposition?: string;
  riskPolicyVersion?: string;
};

export type V2CodeConsumerManifest = {
  assetId?: string;
  versionId?: string;
  purpose?: string;
  runtime?: string;
  entryScript?: string;
  trainingType?: string;
  trainingProfile?: string;
  artifactSha256?: string;
  validationRunId?: string;
  validationPolicyVersion?: string;
  approvalRecordId?: string;
  approvalSource?: string;
  riskAssessmentId?: string;
  riskLevel?: string;
  riskPolicyVersion?: string;
};

export type V2CodeRiskFinding = {
  ruleId?: string;
  severity?: string;
  category?: string;
  filePath?: string;
  lineStart?: number;
  lineEnd?: number;
  description?: string;
};

export type V2CodeRiskAssessmentDetail = {
  id?: string;
  versionId?: string;
  validationRunId?: string;
  artifactSha256?: string;
  riskPolicyVersion?: string;
  scannerVersion?: string;
  status?: string;
  riskLevel?: string;
  disposition?: string;
  findingCount?: number;
  reasonCode?: string;
  createdAt?: string;
  startedAt?: string;
  completedAt?: string;
  findings?: V2CodeRiskFinding[];
};

export type V2AdminCodeReviewTask = {
  versionId?: string;
  codeVersionId?: string;
  id?: string;
  assetId?: string;
  assetName?: string;
  codeAssetName?: string;
  codeName?: string;
  name?: string;
  ownerUserId?: number;
  version?: string;
  lifecycleStatus?: string;
  approvalStatus?: string;
  artifactSha256?: string;
  validationStatus?: string;
  validationPolicyVersion?: string;
  riskAssessmentId?: string;
  riskStatus?: string;
  riskLevel?: string;
  reviewDisposition?: string;
  riskPolicyVersion?: string;
  findingCount?: number;
  submittedAt?: string;
  fileName?: string;
  trainingProfile?: string;
};

export type V2AdminCodeReviewTaskDetail = V2AdminCodeReviewTask & {
  purpose?: string;
  runtime?: string;
  entryScript?: string;
  trainingType?: string;
  fileName?: string;
  sizeBytes?: number;
  submittedAt?: string;
  riskAssessment?: {
    id?: string;
    versionId?: string;
    validationRunId?: string;
    artifactSha256?: string;
    riskPolicyVersion?: string;
    scannerVersion?: string;
    status?: string;
    riskLevel?: string;
    disposition?: string;
    findingCount?: number;
    createdAt?: string;
    startedAt?: string;
    completedAt?: string;
  };
};

export type V2AdminCodeReviewTaskPage = {
  items?: V2AdminCodeReviewTask[];
  page?: number;
  pageSize?: number;
  totalElements?: number;
  totalPages?: number;
};

export type V2CodeApprovalRequest = {
  decision: 'APPROVE' | 'REJECT' | 'REVOKE';
  reason?: string;
  expectedValidationRunId?: string;
  expectedRiskAssessmentId?: string;
  expectedArtifactSha256?: string;
  expectedPolicyVersion?: string;
};

export type V2CodeValidationResult = {
  policyVersion?: string;
  artifactSha256?: string;
  status?: string;
  reasonCode?: string;
  message?: string;
  fileCount?: number;
  reused?: boolean;
  validationStatus?: string;
  passed?: boolean;
  valid?: boolean;
  approvalStatus?: string;
  trainingProfileDisplayName?: string;
  checkedAt?: string;
};

export type V2CodeTreeNode = {
  path?: string;
  name?: string;
  fileName?: string;
  type?: string;
  nodeType?: string;
  directory?: boolean;
  isDirectory?: boolean;
  languageId?: V2CodeLanguageId;
  sizeBytes?: number;
  size?: number;
  contentHash?: string;
  children?: V2CodeTreeNode[];
};

export type V2CodeFileContent = {
  path?: string;
  name?: string;
  content?: string;
  text?: string;
  languageId?: V2CodeLanguageId;
  contentHash?: string;
  sizeBytes?: number;
  fileName?: string;
  workspaceRevision?: number;
  editable?: boolean;
  readOnly?: boolean;
  previewable?: boolean;
};

export type V2CodeFileMetadata = {
  path?: string;
  name?: string;
  nodeType?: string;
  extension?: string;
  languageId?: V2CodeLanguageId;
  contentType?: string;
  sizeBytes?: number;
  previewable?: boolean;
  editable?: boolean;
  downloadable?: boolean;
  reasonCode?: string;
  contentHash?: string;
  workspaceRevision?: number;
  readOnly?: boolean;
  deletable?: boolean;
};

export type V2CodeWorkspace = {
  id?: string;
  assetId?: string;
  baseVersionId?: string;
  closedVersionId?: string;
  status?: string;
  revision?: number;
  createdAt?: string;
  updatedAt?: string;
  closedAt?: string;
  readOnly?: boolean;
};

export type V2CodeFileUpsertRequest = {
  content: string;
  expectedWorkspaceRevision: number;
  expectedContentHash?: string;
};

export type V2CodeWorkspacePublishRequest = {
  expectedWorkspaceRevision: number;
  version?: string;
};

export type V2CodeErrorBody = {
  success?: boolean;
  errorCode?: string;
  errorMessage?: string;
  details?: Record<string, unknown>;
  traceId?: string;
};

export type V2CodeTreeFileEntry = {
  path: string;
  fileName: string;
  sizeBytes?: number;
  languageId?: string;
};

/** —— 管理员跨 owner 代码资产 —— */

export type V2AdminCodeAsset = V2CodeAsset & {
  assetId?: string;
  ownerUserId?: string;
};

export type V2AdminCodeAssetPage = {
  items?: V2AdminCodeAsset[];
  page?: number;
  pageSize?: number;
  totalElements?: number;
  totalPages?: number;
};
