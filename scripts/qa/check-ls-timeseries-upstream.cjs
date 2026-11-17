const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');
const source = path.resolve(__dirname, '../../vendor/label-studio');
const baseline = JSON.parse(fs.readFileSync(path.join(source, 'timeseries-upstream-baseline.json'), 'utf8'));
for (const [file, expected] of Object.entries(baseline.files)) {
  const actual = crypto.createHash('sha256').update(fs.readFileSync(path.join(source, 'libs/editor/src', file))).digest('hex');
  assert.equal(actual, expected, `LS ${baseline.sourceVersion} source changed: ${file}`);
}
console.log(`LS ${baseline.sourceVersion}: ${Object.keys(baseline.files).length} CSV/time-series source files match the pinned upstream bytes.`);
