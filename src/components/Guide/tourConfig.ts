import type { TourProps } from 'antd';

export interface GuideSegment {
  /** 该段所在路由 */
  route: string;
  steps: TourProps['steps'];
}

/**
 * 定位页面上 data-tour="<key>" 的元素。
 * antd Tour 的 target 要求函数返回 HTMLElement；元素未渲染时运行时返回 null 会被 antd 视为居中兜底。
 */
const byTour = (key: string) => (): HTMLElement =>
  document.querySelector<HTMLElement>(`[data-tour="${key}"]`) as HTMLElement;

/** 无 target 时 antd Tour 居中显示（用于欢迎/完成步） */
const centered = () => null;

/**
 * 非最终段：最后一步的按钮文案改为「下一阶段」。
 * 点击它触发 onFinish → 跳转下一路由，语义是"进入下一阶段"，而非"结束导览"。
 */
function markStageStep(steps: TourProps['steps']): TourProps['steps'] {
  if (!steps || steps.length === 0) return steps;
  const last = steps[steps.length - 1];
  return [
    ...steps.slice(0, -1),
    {
      ...last,
      nextButtonProps: {
        ...(last.nextButtonProps ?? {}),
        children: '下一阶段',
      },
    },
  ];
}

// ==================== S0 首页欢迎 /dashboard ====================

const s0Steps = (): TourProps['steps'] => [
  {
    target: centered,
    title: '欢迎使用本平台',
    description:
      '本引导将带你走完「资产上传 → 训练 → 推理」全流程，可随时跳过或退出。系统已为你准备默认资产（示例数据集/模型/代码/推理脚本），可直接用于训练与推理，详见「用户手册」。',
  },
  {
    target: byTour('home-stats'),
    title: '第一步：上传资产',
    description:
      '平台的资产包括数据集、模型、训练代码。先带你去上传一个数据集。',
  },
];

// ==================== S1 数据集上传 /dataset/upload ====================

const s1Steps = (): TourProps['steps'] => [
  {
    target: byTour('ds-name'),
    title: '数据集名称',
    description:
      '填写名称，同一用户名下不可重复。（若你没有自己的数据，可用系统默认数据集，本页可跳过）',
  },
  {
    target: byTour('ds-version'),
    title: '版本号',
    description:
      '格式 vN（如 v2）或 vX.Y.Z（如 v1.0.0）；同一资产内唯一，新版本必须大于已有版本。',
  },
  {
    target: byTour('ds-category'),
    title: '数据集类型',
    description:
      '选择 CV / NLP / 点云 / 多模态 / 机器人等，决定导入与解析规则（机器人、LeRobot 有专属格式）。',
  },
  {
    target: byTour('ds-upload'),
    title: '上传文件',
    description:
      '单文件最大 50GB，分片断点续传，刷新/关浏览器可续；CV 多文件请打包 zip。上传完成后点「提交」。',
  },
];

// ==================== S2 模型上传 /model/upload ====================

const s2Steps = (): TourProps['steps'] => [
  {
    target: byTour('model-name'),
    title: '模型名称',
    description:
      '填写模型名称，同一用户名下不可重复。（若你没有自己的模型，可用系统默认模型，本页可跳过）',
  },
  {
    target: byTour('model-type'),
    title: '模型类型',
    description:
      '选择 CV / NLP / 点云 / 机器人 / OTHER（暂未归类）；注意：类型上传后不可更改。',
  },
  {
    target: byTour('model-remark'),
    title: '备注与 Commit',
    description:
      '资产备注描述整份模型的用途来源；Commit 说明只描述「本次版本」改了什么（类似 Git 提交信息）。',
  },
  {
    target: byTour('model-submit'),
    title: '上传模型包',
    description:
      '支持 .zip/.safetensors/.pt/.pth/.ckpt/.onnx，单文件 ≤2GB，多文件模型打包 zip。点「提交」完成资产上传。',
  },
];

// ==================== S3 发起训练 /task/create ====================

const s3Steps = (): TourProps['steps'] => [
  {
    target: byTour('train-steps'),
    title: '发起训练 = 5 步向导',
    description:
      '训练方案 → 基础模型 → 训练数据集 → 训练配置与代码 → 确认并提交。每一步选好对应的资产或配置。',
  },
  {
    target: byTour('train-panel'),
    title: '按向导逐项填写',
    description:
      '依次选择训练方案、基础模型权重、训练数据集、训练代码；代码需为已审核通过版本。当前步骤的内容就是上方表单。',
  },
  {
    target: byTour('train-actions'),
    title: '提交训练',
    description:
      '填完当前步点「下一步」；最后一步点「提交 K8s 训练」即可发起训练。训练时可以选用系统默认资产（见「用户手册」）。这里仅作讲解，是否真实提交由你决定。',
  },
];

// ==================== S4 推理工作台 /inference/workbench ====================

const s4Steps = (): TourProps['steps'] => [
  {
    target: byTour('inf-create'),
    title: '创建推理任务',
    description:
      '这是推理配置区：选择模型、推理脚本与输入数据，配置好后点「创建并执行」发起推理。',
  },
  {
    target: byTour('inf-model'),
    title: '选择模型',
    description:
      '选择训练产出的模型版本。若训练尚未完成，需等训练成功产出模型后，该下拉才会出现你的模型。',
  },
  {
    target: byTour('inf-script'),
    title: '推理脚本与输入',
    description:
      '选择推理脚本（可先上传新脚本，也可用系统默认推理脚本）；输入方式可选「单文件」或「数据集」。',
  },
  {
    target: byTour('inf-submit'),
    title: '创建并执行',
    description:
      '点「创建并执行」发起推理；下方任务列表可查看进度、日志与结果，支持停止/重试/删除。',
  },
];

// ==================== S5 完成页 /dashboard ====================

const s5Steps = (): TourProps['steps'] => [
  {
    target: centered,
    title: '流程完成',
    description:
      '恭喜！你已走完「资产上传 → 训练 → 推理」全流程。更详细的格式/版本/约束和系统默认资产见「用户手册」；想重看，点首页「开始引导」即可。',
  },
];

/** 构建完整的分段流程（段索引即跨页续跑的依据）；S5 为最终段，保持「完成」文案 */
export function buildSegments(): GuideSegment[] {
  return [
    { route: '/dashboard', steps: markStageStep(s0Steps()) },
    { route: '/dataset/upload', steps: markStageStep(s1Steps()) },
    { route: '/model/upload', steps: markStageStep(s2Steps()) },
    { route: '/task/create', steps: markStageStep(s3Steps()) },
    { route: '/inference/workbench', steps: markStageStep(s4Steps()) },
    { route: '/dashboard', steps: s5Steps() },
  ];
}

/** 取某段的步骤（供页面 usePageTour 使用） */
export function segmentSteps(segment: number): TourProps['steps'] {
  return buildSegments()[segment]?.steps ?? [];
}
