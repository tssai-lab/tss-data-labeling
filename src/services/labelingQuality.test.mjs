import assert from 'node:assert/strict';
import test from 'node:test';
import { cohenKappa, fixedQualityPlan } from './labelingQuality.mjs';

test('quality preview freezes population, seed, gold and separate inspection/double scopes without claiming gold answers', () => {
  const ids = Array.from({ length: 2000 }, (_, i) => `synthetic-${i}`);
  const options = {
    seed: 'fixed-seed',
    goldCount: 500,
    inspectionRate: 0.2,
    doubleRate: 0.1,
  };
  const plan = fixedQualityPlan(ids, options);
  assert.equal(plan.gold.length, 500);
  assert.equal(plan.inspection.length, 300);
  assert.equal(plan.double.length, 200);
  assert.equal(plan.goldConfirmed, false);
  assert.equal(
    plan.inspection.some((id) => plan.gold.includes(id)),
    false,
  );
  assert.deepEqual(fixedQualityPlan([...ids].reverse(), options), plan);
  ids.push('later');
  assert.equal(plan.population.length, 2000);
  assert.throws(() => fixedQualityPlan(['same', 'same'], options));
});
test('Cohen kappa shows valid independent primary pairs, exclusions, agreement, expectation and degeneracy', () => {
  const pairs = ['a', 'b', 'a', 'b'].map((labelId, i) => ({
    sampleId: `sample-${i}`,
    specVersionId: 'same-spec',
    left: { actorId: 'one', sourceKind: 'HUMAN', labelId },
    right: {
      actorId: 'two',
      sourceKind: 'HUMAN',
      labelId: i === 2 ? 'b' : labelId,
    },
  }));
  const stats = cohenKappa([
    ...pairs,
    { ...pairs[0], sampleId: 'bad', right: pairs[0].left },
    pairs[0],
  ]);
  assert.equal(stats.pairCount, 4);
  assert.equal(stats.excludedCount, 2);
  assert.equal(stats.observedAgreement, 0.75);
  assert.equal(stats.expectedAgreement, 0.5);
  assert.equal(stats.kappa, 0.5);
  assert.equal(cohenKappa([pairs[0]]).reason, 'DEGENERATE_EXPECTATION');
  assert.equal(cohenKappa([]).reason, 'NO_VALID_PAIRS');
  assert.equal(
    cohenKappa([
      {
        ...pairs[0],
        right: { ...pairs[0].right, referenceResultId: 'shared-answer' },
      },
    ]).pairCount,
    0,
  );
});
