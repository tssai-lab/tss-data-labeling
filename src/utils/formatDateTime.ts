import dayjs from 'dayjs';

/** 将 ISO / 后端时间字符串格式化为本地可读时间 */
export function formatDisplayDateTime(value?: string | null): string {
  if (!value?.trim()) return '-';
  const parsed = dayjs(value);
  if (!parsed.isValid()) return value;
  return parsed.format('YYYY-MM-DD HH:mm:ss');
}

/** 计算两时间点之间的中文耗时描述 */
export function formatDurationBetween(
  start?: string | null,
  end?: string | null,
): string {
  if (!start?.trim() || !end?.trim()) return '-';
  const startAt = dayjs(start);
  const endAt = dayjs(end);
  if (!startAt.isValid() || !endAt.isValid() || !endAt.isAfter(startAt)) {
    return '-';
  }
  const totalSeconds = endAt.diff(startAt, 'second');
  if (totalSeconds < 60) {
    return `${totalSeconds} 秒`;
  }
  const totalMinutes = Math.floor(totalSeconds / 60);
  if (totalMinutes < 60) {
    const seconds = totalSeconds % 60;
    return seconds > 0
      ? `${totalMinutes} 分钟 ${seconds} 秒`
      : `${totalMinutes} 分钟`;
  }
  const hours = Math.floor(totalMinutes / 60);
  const minutes = totalMinutes % 60;
  if (hours < 24) {
    return minutes > 0 ? `${hours} 小时 ${minutes} 分钟` : `${hours} 小时`;
  }
  const days = Math.floor(hours / 24);
  const remHours = hours % 24;
  return remHours > 0 ? `${days} 天 ${remHours} 小时` : `${days} 天`;
}
