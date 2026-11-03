import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import ts from 'typescript';

const sourceUrl = new URL('./trainingCodeReviewPolicy.ts', import.meta.url);
const source = await readFile(sourceUrl, 'utf8');
const compiled = ts.transpileModule(source, {
  compilerOptions: {
    module: ts.ModuleKind.ESNext,
    target: ts.ScriptTarget.ES2022,
  },
}).outputText;
const policy = await import(
  `data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`
);

test('maps the two switches to exactly three valid backend modes', () => {
  assert.equal(policy.reviewModeFromSwitches(false, false), 'DIRECT_PASS');
  assert.equal(policy.reviewModeFromSwitches(false, true), 'DIRECT_PASS');
  assert.equal(policy.reviewModeFromSwitches(true, true), 'STANDARD_REVIEW');
  assert.equal(policy.reviewModeFromSwitches(true, false), 'MANUAL_ONLY');
});

test('unknown or missing server values fail closed to manual review', () => {
  assert.equal(policy.normalizeTrainingCodeReviewMode(), 'MANUAL_ONLY');
  assert.equal(
    policy.normalizeTrainingCodeReviewMode('unexpected'),
    'MANUAL_ONLY',
  );
  assert.equal(policy.reviewEnabledFromMode('unexpected'), true);
  assert.equal(policy.automaticDecisionsEnabledFromMode('unexpected'), false);
});

test('direct pass disables effective review but keeps the safe next selection', () => {
  assert.equal(policy.reviewEnabledFromMode('DIRECT_PASS'), false);
  assert.equal(policy.automaticDecisionsEnabledFromMode('DIRECT_PASS'), false);
  assert.equal(policy.automaticReviewSelectedFromMode('DIRECT_PASS'), true);
  assert.equal(policy.automaticReviewSelectedFromMode('MANUAL_ONLY'), false);
});
