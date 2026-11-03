export type DatasetDirectory =
  | 'VISUAL'
  | 'TEXT'
  | 'POINT_CLOUD'
  | 'ROBOT'
  | 'MULTIMODAL'
  | 'OTHER';
export type VisualFileLayout = 'UNLABELED' | 'IMAGE_FOLDER' | 'YOLO';
export type RobotDataFormat = 'CONFIG' | 'LEROBOT';
export type BackendDatasetType =
  | 'CV'
  | 'NLP'
  | 'POINT_CLOUD'
  | 'ROBOT'
  | 'LEROBOT'
  | 'MULTIMODAL'
  | 'OTHER';

export const DATASET_DIRECTORY_OPTIONS: readonly Readonly<{
  value: DatasetDirectory;
  label: string;
}>[];
export const VISUAL_FILE_LAYOUT_OPTIONS: readonly Readonly<{
  value: VisualFileLayout;
  label: string;
}>[];
export const ROBOT_DATA_FORMAT_OPTIONS: readonly Readonly<{
  value: RobotDataFormat;
  label: string;
}>[];

export function directoryFromBackendType(
  type: unknown,
): DatasetDirectory | undefined;
export function resolveDatasetUploadMetadata(
  directory?: DatasetDirectory,
  visualFileLayout?: VisualFileLayout,
  robotDataFormat?: RobotDataFormat,
):
  | {
      type: BackendDatasetType;
      cvTaskType?: 'UNLABELED' | 'IMAGE_CLASSIFICATION' | 'OBJECT_DETECTION';
      annotationFormat?: 'NONE' | 'FOLDER_CLASSIFICATION' | 'YOLO';
    }
  | undefined;
export function visualLayoutFromSpecId(
  specId?: string,
): VisualFileLayout | undefined;
export function visualUploadViolation(
  layout: VisualFileLayout | undefined,
  fileNames: string[],
): string | undefined;
export function inheritedDatasetIdentity(detail?: {
  id?: string;
  name?: string;
  type?: BackendDatasetType;
  latestVersion?: { artifactSpecId?: string };
}):
  | {
      id: string;
      name: string;
      type: BackendDatasetType;
      directory: DatasetDirectory;
      artifactSpecId?: string;
    }
  | undefined;
