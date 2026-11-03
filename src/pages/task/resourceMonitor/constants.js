/** 前端展示用常量（非接口数据） */

export const TIME_INTERVAL_CONFIG = {
  '1min': {
    label: '1 分钟',
    spanLabel: '近 1 小时（按分钟）',
  },
  '10min': {
    label: '10 分钟',
    spanLabel: '近 12 小时（按 10 分钟）',
  },
  '1hour': {
    label: '1 小时',
    spanLabel: '近 24 小时（按小时）',
  },
  '1day': {
    label: '1 天',
    spanLabel: '近 7 天（按天）',
  },
};

export const TIME_INTERVAL_OPTIONS = Object.entries(TIME_INTERVAL_CONFIG).map(
  ([value, cfg]) => ({
    value,
    label: cfg.label,
  }),
);

export const getIntervalSpanLabel = (interval = '1hour') =>
  TIME_INTERVAL_CONFIG[interval]?.spanLabel ||
  TIME_INTERVAL_CONFIG['1hour'].spanLabel;

export const getUsageColor = (rate) => {
  if (rate >= 85) return '#ff4d4f';
  if (rate >= 60) return '#faad14';
  return '#52c41a';
};

export const getUsageStatus = (rate) => {
  if (rate >= 85) return 'exception';
  if (rate >= 60) return 'active';
  return 'normal';
};

/** 排队任务默认优先级（当前公司内部统一为中，保留字段供后续扩展） */
export const DEFAULT_QUEUE_PRIORITY = '中';

/** 任务原生业务优先级权重（预留：启用多优先级自动排序时使用） */
export const TASK_PRIORITY_WEIGHT = {
  高: 1,
  中: 2,
  低: 3,
};
