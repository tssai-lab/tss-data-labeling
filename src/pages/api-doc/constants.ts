/** HTTP 方法 → Ant Design Tag color */
export const METHOD_TAG_COLOR: Record<string, { color: string; text: string }> =
  {
    GET: { color: 'blue', text: 'GET' },
    POST: { color: 'green', text: 'POST' },
    PUT: { color: 'orange', text: 'PUT' },
    DELETE: { color: 'red', text: 'DELETE' },
    PATCH: { color: 'purple', text: 'PATCH' },
    HEAD: { color: 'default', text: 'HEAD' },
    OPTIONS: { color: 'default', text: 'OPTIONS' },
  };

/**
 * SpringDoc controller tag → 中文模块名 + 描述
 * 后端没有顶层 tags 数组，模块信息从 paths 中的 controller tag 推导
 */
export const TAG_MODULE_MAP: Record<
  string,
  { name: string; description: string }
> = {
  // 认证
  'auth-controller': {
    name: '认证',
    description: '用户登录、注册、登出、密码重置等认证相关接口',
  },
  'user-controller': { name: '认证', description: '' },
  // 模型管理
  'model-controller': {
    name: '模型管理',
    description: '模型资产的查询、上传与代码预览',
  },
  'model-asset-crud-controller': { name: '模型管理', description: '' },
  'model-version-crud-controller': { name: '模型管理', description: '' },
  'model-upload-controller': { name: '模型管理', description: '' },
  'v-2-model-upload-controller': { name: '模型管理', description: '' },
  // 数据集管理
  'dataset-controller': {
    name: '数据集管理',
    description: '数据集资产的增删改查、版本管理与断点续传',
  },
  'dataset-asset-crud-controller': { name: '数据集管理', description: '' },
  'dataset-version-crud-controller': { name: '数据集管理', description: '' },
  'dataset-upload-controller': { name: '数据集管理', description: '' },
  'dataset-preview-controller': { name: '数据集管理', description: '' },
  'dataset-version-package-upload-controller': {
    name: '数据集管理',
    description: '',
  },
  // V2 数据集
  'v-2-dataset-controller': {
    name: '数据集 V2',
    description: 'V2 版本数据集的管理接口',
  },
  'v-2-dataset-edit-controller': { name: '数据集 V2', description: '' },
  'v-2-dataset-upload-controller': { name: '数据集 V2', description: '' },
  'v-2-dataset-preview-controller': { name: '数据集 V2', description: '' },
  'v-2-dataset-consumer-manifest-controller': {
    name: '数据集 V2',
    description: '',
  },
  // 多模态
  'dataset-workspace-controller': {
    name: '多模态数据集',
    description: '多模态数据集样本导入、标注查看与工作区管理',
  },
  'dataset-workspace-sample-controller': {
    name: '多模态数据集',
    description: '',
  },
  'dataset-workspace-publish-controller': {
    name: '多模态数据集',
    description: '',
  },
  'sample-controller': { name: '多模态数据集', description: '' },
  'sample-file-controller': { name: '多模态数据集', description: '' },
  'import-job-controller': { name: '多模态数据集', description: '' },
  // 训练调度
  'training-task-controller': {
    name: '训练调度',
    description: '训练任务的创建、调度、停止、删除与实验版本管理',
  },
  'training-experiment-controller': { name: '训练调度', description: '' },
  'training-environment-controller': { name: '训练调度', description: '' },
  // 训练代码
  'code-upload-controller': {
    name: '训练代码',
    description: '训练代码的上传、审核、预览与准入校验',
  },
  'code-version-controller': { name: '训练代码', description: '' },
  // 模型推理
  'inference-script-controller': {
    name: '模型推理',
    description: '推理脚本上传、推理任务创建与结果查询',
  },
  'inference-task-controller': { name: '模型推理', description: '' },
  // 文件服务
  'file-object-controller': {
    name: '文件服务',
    description: '通用文件对象的上传与下载',
  },
  // 点云
  'point-cloud-preview-controller': {
    name: '点云预览',
    description: '点云数据的三维可视化预览',
  },
  // 系统管理
  'system-user-controller': {
    name: '系统管理',
    description: '用户管理、角色管理、操作日志与系统配置',
  },
  'system-log-controller': { name: '系统管理', description: '' },
  'operation-log-controller': { name: '系统管理', description: '' },
  'role-controller': { name: '系统管理', description: '' },
  // 内部回调（隐藏）
  'internal-training-callback-controller': { name: '', description: '' },
  'internal-inference-callback-controller': { name: '', description: '' },
};

/** 模块显示顺序 */
export const MODULE_ORDER: string[] = [
  '认证',
  '模型管理',
  '数据集管理',
  '数据集 V2',
  '多模态数据集',
  '训练调度',
  '训练代码',
  '模型推理',
  '文件服务',
  '点云预览',
  '系统管理',
];
