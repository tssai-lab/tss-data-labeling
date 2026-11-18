// Check the actual editor resources copied into the application release.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const root = path.resolve(__dirname, '../..', process.argv[2] || 'public');
const directory = path.join(root, 'ls-editor');
const manifest = JSON.parse(fs.readFileSync(path.join(directory, 'manifest.json'), 'utf8'));
assert.equal(manifest.mode, 'production');
assert.equal(manifest.protocolVersion, 1);
assert.match(manifest.entry, /^editor\.[a-f0-9]{12}\.js$/);
const bytes = fs.readFileSync(path.join(directory, manifest.entry));
assert.equal(crypto.createHash('sha256').update(bytes).digest('hex'), manifest.sha256);
assert.ok(bytes.length > 100000, 'Editor bundle unexpectedly small');
assert.ok(!bytes.includes(Buffer.from('react.development.js')), 'Development React was bundled');
console.log(`Production LS ${manifest.sourceVersion} entry and SHA-256 verified (${bytes.length} bytes).`);
