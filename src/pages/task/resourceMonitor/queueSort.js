const compareAutoTasks = (a, b) => a.submitTime.localeCompare(b.submitTime);

/**
 * 排队任务排序：
 * 1. queueSortIndex ≠ 0 的任务占指定槽位（升序，数字越小越靠前）
 * 2. 其余槽位由 queueSortIndex = 0 的任务按提交时间 FIFO 填充（越早越靠前）
 *
 * 当前业务默认全员 priority=中，自动队列仅按发起训练时间排序；
 * priority 字段保留供后续扩展，见 compareAutoTasks 可恢复优先级分层。
 */
export const sortQueuedTasks = (tasks) => {
  if (!tasks?.length) return [];

  const n = tasks.length;
  const manual = tasks.filter(
    (t) => t.queueSortIndex != null && t.queueSortIndex !== 0,
  );
  const auto = [
    ...tasks.filter((t) => !t.queueSortIndex || t.queueSortIndex === 0),
  ].sort(compareAutoTasks);

  manual.sort((a, b) => a.queueSortIndex - b.queueSortIndex);

  const result = new Array(n).fill(null);
  const overflowManual = [];

  manual.forEach((task) => {
    let pos = Math.max(0, Math.min(task.queueSortIndex - 1, n - 1));
    while (pos < n && result[pos] != null) pos += 1;
    if (pos < n) {
      result[pos] = task;
    } else {
      overflowManual.push(task);
    }
  });

  let autoIdx = 0;
  for (let i = 0; i < n; i += 1) {
    if (result[i] == null && autoIdx < auto.length) {
      result[i] = auto[autoIdx];
      autoIdx += 1;
    }
  }
  while (autoIdx < auto.length) {
    result.push(auto[autoIdx]);
    autoIdx += 1;
  }
  for (const task of overflowManual) {
    result.push(task);
  }

  return result.filter(Boolean);
};

/** 将人工序号与当前展示顺位对齐（取消排队后由后端重算） */
export const renumberManualSortIndices = (tasks) => {
  const sorted = sortQueuedTasks(tasks);
  sorted.forEach((task, index) => {
    if (task.queueSortIndex != null && task.queueSortIndex !== 0) {
      task.queueSortIndex = index + 1;
    }
  });
  return sorted;
};
