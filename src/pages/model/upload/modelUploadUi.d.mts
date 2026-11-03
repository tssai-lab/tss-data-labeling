export type ModelUploadCategory =
  | 'CV'
  | 'NLP'
  | 'POINT_CLOUD'
  | 'ROBOT'
  | 'OTHER';

export const MODEL_UPLOAD_CATEGORY_OPTIONS: readonly Readonly<{
  value: ModelUploadCategory;
  label: string;
}>[];

export function isModelUploadCategory(value: unknown): boolean;

export function inheritedModelIdentity(detail?: {
  id?: string;
  name?: string;
  type?: 'CV' | 'NLP' | 'POINT_CLOUD' | 'ROBOT' | 'OTHER';
  remark?: string;
  latestVersion?: { artifactSpecId?: string };
}):
  | {
      id: string;
      name: string;
      type: 'CV' | 'NLP' | 'POINT_CLOUD' | 'ROBOT' | 'OTHER';
      remark: string;
      artifactSpecId?: string;
    }
  | undefined;
