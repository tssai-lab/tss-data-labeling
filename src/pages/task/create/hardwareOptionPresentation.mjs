export function firstTrainingHardwareOption(options, preferredProfileId) {
  if (!Array.isArray(options) || !options.length) return undefined;
  if (preferredProfileId) {
    const preferred = options.find(
      (option) => option?.resourceProfileId === preferredProfileId,
    );
    if (preferred) return preferred;
  }
  return options[0];
}

export function isTrainingHardwareTargetAllowed(options, targetId) {
  return (
    typeof targetId === 'string' &&
    targetId.length > 0 &&
    Array.isArray(options) &&
    options.some((option) => option?.hardwareTargetId === targetId)
  );
}

export function formatTrainingHardwareOptionLabel(option, formatMemory) {
  if (!option) return '';
  const memory = formatMemory(option.memory?.limitMiB);
  const accelerator =
    option.deviceType === 'NVIDIA_GPU'
      ? ` · ${option.gpuCount} 卡`
      : '';
  return `${option.displayName}${accelerator} · ${option.cpu?.limitCores} 核 · ${memory}`;
}
