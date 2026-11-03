export function formatMiB(value) {
  const amount = Number(value);
  if (!Number.isFinite(amount) || amount < 0) return '无数据';
  if (amount >= 1024 && amount % 1024 === 0) return `${amount / 1024} GiB`;
  return `${Math.round(amount)} MiB`;
}

/** 确认页必须展示本次真正提交的资源值，避免把方案上限误当成自定义值。 */
export function formatTrainingResourceSummary(mode, values, capability) {
  if (!capability) return '-';
  const custom = mode === 'custom';
  const cpuCores = Number(
    custom ? values?.cpuCores : capability.cpu?.limitCores,
  );
  const memoryMiB = Number(
    custom ? values?.memoryMiB : capability.memory?.limitMiB,
  );
  const cpuText = Number.isFinite(cpuCores) ? `${cpuCores} 核` : '无数据';
  const hardwareText = capability.displayName
    ? `${capability.displayName} / `
    : '';
  return `${hardwareText}${cpuText} / ${formatMiB(memoryMiB)}`;
}

export function resourceStatusPresentation(capability, error) {
  if (error) {
    return {
      type: 'error',
      message: '实际资源读取失败',
      description: error,
    };
  }
  if (!capability) {
    return {
      type: 'info',
      message: '正在读取实际资源',
      description: '推荐配置仍以训练方案为准。',
    };
  }
  if (capability.dataStatus === 'AVAILABLE') {
    return {
      type: 'success',
      message: `检测到 ${capability.eligibleNodeCount} 个符合方案的节点`,
      description: capability.message,
    };
  }
  return {
    type: capability.dataStatus === 'PARTIAL' ? 'warning' : 'error',
    message:
      capability.dataStatus === 'PARTIAL'
        ? '实际资源信息不完整'
        : '当前没有符合方案的节点',
    description: capability.message,
  };
}

export function buildTrainingResourceRequest(
  mode,
  values,
  profile,
  capability,
  hardwareTargetId,
) {
  if (!hardwareTargetId) throw new Error('请选择当前可用的硬件型号');
  if (mode !== 'custom') return { hardwareTargetId };
  if (!capability) throw new Error('实际资源数据不可用，不能使用自定义配置');
  const cpuCores = Number(values?.cpuCores);
  const memoryMiB = Number(values?.memoryMiB);
  if (
    !Number.isFinite(cpuCores) ||
    cpuCores < capability.cpu.requestCores ||
    cpuCores > capability.cpu.limitCores
  ) {
    throw new Error('CPU 核数超出当前训练方案范围');
  }
  if (
    !Number.isInteger(memoryMiB) ||
    memoryMiB < capability.memory.requestMiB ||
    memoryMiB > capability.memory.limitMiB
  ) {
    throw new Error('系统内存超出当前训练方案范围');
  }
  const result = {
    hardwareTargetId,
    cpuCores,
    memoryMiB,
    gpuCount: Number(profile?.gpuCount || 0),
  };
  const rawBudget = values?.gpuMemoryLimitMiB;
  if (rawBudget !== undefined && rawBudget !== null && rawBudget !== '') {
    const budget = Number(rawBudget);
    if (capability.deviceType !== 'NVIDIA_GPU') {
      throw new Error('CPU 训练不能设置 GPU 显存预算');
    }
    if (
      !capability.gpu?.metricsComplete ||
      !Number.isInteger(budget) ||
      budget <= 0 ||
      budget > Number(capability.gpu.safeTotalMemoryMiB || 0)
    ) {
      throw new Error('GPU 显存预算超出当前安全范围');
    }
    result.gpuMemoryLimitMiB = budget;
  }
  return result;
}
