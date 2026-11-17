// Generic synthetic planning/statistics helpers. No uncontracted QC endpoint is invented.
export function fixedQualityPlan(
  sampleIds,
  { seed, goldCount, inspectionRate, doubleRate },
) {
  if (
    !seed ||
    new Set(sampleIds).size !== sampleIds.length ||
    !Number.isInteger(goldCount) ||
    goldCount < 0 ||
    goldCount > sampleIds.length ||
    [inspectionRate, doubleRate].some(
      (r) => !Number.isFinite(r) || r < 0 || r > 1,
    )
  )
    throw new Error('抽样母体、种子、数量或比例不合法');
  const rank = (id) => {
    let h = 2166136261;
    for (const c of `${seed}:${id}`)
      h = Math.imul(h ^ c.charCodeAt(0), 16777619);
    return h >>> 0;
  };
  const population = [...sampleIds].sort();
  const ordered = [...population].sort(
    (a, b) => rank(a) - rank(b) || a.localeCompare(b),
  );
  return {
    version: 'synthetic-fixed-sample-v1',
    seed,
    population,
    gold: ordered.slice(0, goldCount),
    inspection: ordered.slice(
      goldCount,
      goldCount + Math.ceil((sampleIds.length - goldCount) * inspectionRate),
    ),
    double: ordered.slice(0, Math.ceil(sampleIds.length * doubleRate)),
    goldConfirmed: false,
  };
}
export function cohenKappa(pairs) {
  const left = {},
    right = {},
    seen = new Set();
  let count = 0,
    excluded = 0,
    same = 0;
  for (const p of pairs) {
    const key = `${p.sampleId}:${p.specVersionId}`;
    if (
      !p.sampleId ||
      !p.specVersionId ||
      !p.left?.labelId ||
      !p.right?.labelId ||
      !p.left.actorId ||
      !p.right.actorId ||
      p.left.actorId === p.right.actorId ||
      p.left.referenceResultId ||
      p.right.referenceResultId ||
      p.left.sourceKind !== 'HUMAN' ||
      p.right.sourceKind !== 'HUMAN' ||
      seen.has(key)
    ) {
      excluded++;
      continue;
    }
    seen.add(key);
    count++;
    same += Number(p.left.labelId === p.right.labelId);
    left[p.left.labelId] = (left[p.left.labelId] || 0) + 1;
    right[p.right.labelId] = (right[p.right.labelId] || 0) + 1;
  }
  const observedAgreement = count ? same / count : null;
  const expectedAgreement = count
    ? Object.keys(left).reduce((s, k) => s + left[k] * (right[k] || 0), 0) /
      count ** 2
    : null;
  return {
    pairCount: count,
    excludedCount: excluded,
    observedAgreement,
    expectedAgreement,
    kappa:
      !count || expectedAgreement === 1
        ? null
        : (observedAgreement - expectedAgreement) / (1 - expectedAgreement),
    reason: !count
      ? 'NO_VALID_PAIRS'
      : expectedAgreement === 1
        ? 'DEGENERATE_EXPECTATION'
        : null,
  };
}
