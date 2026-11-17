// Local HTTP fixtures verify the bundled preview gateway. No accounts, real LS, or external network.
import assert from 'node:assert/strict';
import http from 'node:http';
import net from 'node:net';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { writeFile } from 'node:fs/promises';
const devProxy = process.argv.includes('--dev');
const listen = async (server, port = 0) => {
  server.listen(port, '127.0.0.1');
  await once(server, 'listening');
  return server.address().port;
};
let launched = false;
const upstream = http.createServer((req, res) => {
  if (
    req.url ===
    '/api/labeling/v1/editor-ui/fixture-session/launch?ticket=synthetic'
  ) {
    if (launched) {
      res.writeHead(409);
      res.end('EDITOR_ENTRY_EXPIRED');
      return;
    }
    launched = true;
    res.writeHead(302, {
      Location: '/api/labeling/v1/editor-ui/fixture-session/',
      'Set-Cookie':
        'fixture-session=synthetic; HttpOnly; SameSite=Strict; Path=/api/labeling/v1/editor-ui/fixture-session/',
    });
    res.end();
    return;
  }
  if (req.url === '/api/labeling/v1/editor-assets/fixture.js') {
    res.setHeader('Content-Type', 'text/javascript');
    res.end('/* synthetic editor asset */');
    return;
  }
  res.writeHead(404);
  res.end();
});
const upstreamPort = await listen(upstream, devProxy ? 8082 : 0);
const reserve = net.createServer();
const port = await listen(reserve);
await new Promise((resolve) => reserve.close(resolve));
const child = spawn(process.execPath, ['scripts/serve-dist.cjs'], {
  env: {
    ...process.env,
    HOST: '127.0.0.1',
    PORT: String(port),
    DEV_LABELING_TARGET: `http://127.0.0.1:${upstreamPort}`,
  },
  stdio: ['ignore', 'pipe', 'pipe'],
  windowsHide: true,
});
const origin = devProxy ? 'http://127.0.0.1:8001' : `http://127.0.0.1:${port}`;
try {
  for (let tries = 0; tries < 40; tries++) {
    if (
      await fetch(origin)
        .then((r) => r.ok)
        .catch(() => false)
    )
      break;
    await new Promise((resolve) => setTimeout(resolve, 200));
  }
  const entry = `${origin}/api/labeling/v1/editor-ui/fixture-session/launch?ticket=synthetic`;
  const response = await fetch(entry, { redirect: 'manual' });
  assert.equal(response.status, 302);
  assert.equal(
    response.headers.get('location'),
    '/api/labeling/v1/editor-ui/fixture-session/',
  );
  const cookie = response.headers.get('set-cookie');
  for (const field of [
    'HttpOnly',
    'SameSite=Strict',
    'Path=/api/labeling/v1/editor-ui/fixture-session/',
  ])
    assert.ok(cookie.includes(field));
  assert.equal((await fetch(entry, { redirect: 'manual' })).status, 409);
  assert.equal(
    await (
      await fetch(`${origin}/api/labeling/v1/editor-assets/fixture.js`)
    ).text(),
    '/* synthetic editor asset */',
  );
  const report = {
    fixture: true,
    realBackend: false,
    checks: [
      'editor-ui exact path and query forwarded',
      'one-time synthetic ticket 302 then 409',
      'HttpOnly SameSite Strict scoped cookie preserved',
      'editor-assets forwarded to labeling upstream',
    ],
    passed: true,
  };
  await writeFile(
    new URL(
      `../../docs/labeling-v11-evidence/${devProxy ? 'dev-proxy' : 'proxy'}-report.json`,
      import.meta.url,
    ),
    JSON.stringify(report, null, 2),
  );
  console.log(JSON.stringify(report));
} finally {
  child.kill();
  upstream.close();
}
