export function listUsableInferenceProfiles(profiles) {
  const seen = new Set();
  return (profiles || []).filter((profile) => {
    const gpuCount = Number(profile?.gpuCount || 0);
    const validDevice =
      (profile?.deviceType === 'CPU' && gpuCount === 0) ||
      (profile?.deviceType === 'NVIDIA_GPU' && gpuCount === 1);
    if (
      !profile?.id ||
      !validDevice ||
      seen.has(profile.id)
    ) {
      return false;
    }
    seen.add(profile.id);
    return true;
  });
}

export function defaultInferenceResourceProfileId(profiles) {
  return listUsableInferenceProfiles(profiles)[0]?.id;
}

// 保留旧导出，避免维护期内其他页面或测试突然断开。
export function listUsableCpuInferenceProfiles(profiles) {
  return listUsableInferenceProfiles(profiles).filter(
    (profile) => profile.deviceType === 'CPU',
  );
}
