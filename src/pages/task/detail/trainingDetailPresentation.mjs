export function readHyperParamSummary(hyperParams = {}) {
  return {
    epochs: hyperParams.epochs ?? hyperParams.num_epochs,
    batch: hyperParams.batch_size ?? hyperParams.batch ?? hyperParams.batchSize,
    lr: hyperParams.learning_rate ?? hyperParams.lr0 ?? hyperParams.lr,
  };
}

export function resolveTrainingPlanDisplayName(trainingProfile, plans = []) {
  if (!trainingProfile) return '-';
  const plan = plans.find((item) => item?.id === trainingProfile);
  return plan?.displayName || trainingProfile;
}

export function buildTrainingOutputArtifactItems(trainingOutput) {
  const artifacts = Array.isArray(trainingOutput?.artifacts)
    ? trainingOutput.artifacts
    : [];
  const seen = new Set();

  return artifacts
    .filter((artifact) => artifact?.role !== 'PRIMARY_MODEL')
    .map((artifact) => {
      const objectName = String(artifact?.objectName || '').trim();
      const path = String(artifact?.path || '').trim();
      if (!objectName || !path || seen.has(objectName)) return null;
      seen.add(objectName);
      return {
        name: path,
        desc: `minio://${objectName}`,
        objectName,
      };
    })
    .filter(Boolean);
}
