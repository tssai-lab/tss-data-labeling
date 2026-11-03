export const DATASET_DIRECTORY_OPTIONS = Object.freeze([
  Object.freeze({ value: 'VISUAL', label: '视觉数据' }),
  Object.freeze({ value: 'TEXT', label: '文本数据' }),
  Object.freeze({ value: 'POINT_CLOUD', label: '点云数据' }),
  Object.freeze({ value: 'ROBOT', label: '机器人数据' }),
  Object.freeze({ value: 'MULTIMODAL', label: '多模态数据' }),
  Object.freeze({ value: 'OTHER', label: '其他（暂未归类）' }),
]);

export const VISUAL_FILE_LAYOUT_OPTIONS = Object.freeze([
  Object.freeze({ value: 'UNLABELED', label: '未标注图片' }),
  Object.freeze({ value: 'IMAGE_FOLDER', label: 'ImageFolder 图像分类目录' }),
  Object.freeze({ value: 'YOLO', label: 'YOLO 目标检测包' }),
]);

export const ROBOT_DATA_FORMAT_OPTIONS = Object.freeze([
  Object.freeze({ value: 'CONFIG', label: '机器人配置（XML/YAML）' }),
  Object.freeze({ value: 'LEROBOT', label: 'LeRobot v3 时序数据集' }),
]);

const DIRECTORY_BY_BACKEND_TYPE = Object.freeze({
  CV: 'VISUAL',
  NLP: 'TEXT',
  POINT_CLOUD: 'POINT_CLOUD',
  ROBOT: 'ROBOT',
  LEROBOT: 'ROBOT',
  MULTIMODAL: 'MULTIMODAL',
  OTHER: 'OTHER',
});

export function directoryFromBackendType(type) {
  return DIRECTORY_BY_BACKEND_TYPE[String(type || '').toUpperCase()];
}

export function resolveDatasetUploadMetadata(
  directory,
  visualFileLayout,
  robotDataFormat,
) {
  switch (directory) {
    case 'VISUAL':
      if (visualFileLayout === 'UNLABELED') {
        return {
          type: 'CV',
          cvTaskType: 'UNLABELED',
          annotationFormat: 'NONE',
        };
      }
      if (visualFileLayout === 'IMAGE_FOLDER') {
        return {
          type: 'CV',
          cvTaskType: 'IMAGE_CLASSIFICATION',
          annotationFormat: 'FOLDER_CLASSIFICATION',
        };
      }
      if (visualFileLayout === 'YOLO') {
        return {
          type: 'CV',
          cvTaskType: 'OBJECT_DETECTION',
          annotationFormat: 'YOLO',
        };
      }
      return undefined;
    case 'TEXT':
      return { type: 'NLP' };
    case 'POINT_CLOUD':
      return { type: 'POINT_CLOUD' };
    case 'ROBOT':
      if (robotDataFormat === 'CONFIG') return { type: 'ROBOT' };
      if (robotDataFormat === 'LEROBOT') return { type: 'LEROBOT' };
      return undefined;
    case 'MULTIMODAL':
      return { type: 'MULTIMODAL' };
    case 'OTHER':
      return { type: 'OTHER' };
    default:
      return undefined;
  }
}

export function visualLayoutFromSpecId(specId) {
  if (specId === 'dataset.cv.unlabeled-images/v1') return 'UNLABELED';
  if (specId === 'dataset.cv.imagefolder/v1') return 'IMAGE_FOLDER';
  if (specId === 'dataset.cv.yolo/v1') return 'YOLO';
  return undefined;
}

export function visualUploadViolation(layout, fileNames) {
  if (layout !== 'IMAGE_FOLDER' && layout !== 'YOLO') return undefined;
  const label = layout === 'IMAGE_FOLDER' ? 'ImageFolder' : 'YOLO';
  if (
    fileNames.length !== 1 ||
    !String(fileNames[0] || '')
      .toLowerCase()
      .endsWith('.zip')
  ) {
    return `${label} 须上传单个 zip`;
  }
  return undefined;
}

export function inheritedDatasetIdentity(detail) {
  const directory = directoryFromBackendType(detail?.type);
  if (!detail?.id || !detail?.name || !detail?.type || !directory) {
    return undefined;
  }
  return {
    id: detail.id,
    name: detail.name,
    type: detail.type,
    directory,
    artifactSpecId: detail.latestVersion?.artifactSpecId,
  };
}
