export function listTrainingResourceProfiles(plan) {
  const seen = new Set();
  const profiles = [];
  for (const runtime of plan?.runtimes || []) {
    if (!['CPU', 'NVIDIA_GPU'].includes(runtime?.deviceType)) continue;
    for (const profile of runtime?.resourceProfiles || []) {
      const gpuCount = Number(profile?.gpuCount);
      const validDeviceCount =
        (runtime.deviceType === 'CPU' && gpuCount === 0) ||
        (runtime.deviceType === 'NVIDIA_GPU' && gpuCount > 0);
      if (
        !profile?.id ||
        !Number.isInteger(gpuCount) ||
        !validDeviceCount ||
        seen.has(profile.id)
      ) {
        continue;
      }
      seen.add(profile.id);
      profiles.push({
        ...profile,
        runtimeId: runtime.id,
        deviceType: runtime.deviceType,
      });
    }
  }
  return profiles;
}

export function firstTrainingResourceProfileId(plan) {
  const profiles = listTrainingResourceProfiles(plan);
  return (
    profiles.find((profile) => profile.deviceType === 'CPU') || profiles[0]
  )?.id;
}

export function isTrainingResourceProfileIdAllowed(profiles, profileId) {
  return (
    typeof profileId === 'string' &&
    profileId.length > 0 &&
    profiles.some((profile) => profile.id === profileId)
  );
}

export function formatTrainingResourceProfileLabel(profile) {
  if (!profile) return '';
  const device =
    profile.deviceType === 'NVIDIA_GPU'
      ? `GPU · ${profile.gpuCount} 卡`
      : 'CPU';
  return `${device} · ${profile.cpuLimit} 核 · ${profile.memoryLimit}`;
}
