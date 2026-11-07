// Node 18+. Uses current platform identity; never uses LS service credentials.
import { randomUUID } from 'node:crypto';
const base = process.env.LABELING_API_BASE;
const token = process.env.LABELING_TOKEN;
const assignment = process.env.LABELING_ASSIGNMENT_ID;
const sample = process.env.LABELING_SAMPLE_ID;
if (!base || !token || !assignment || !sample) throw new Error('Set LABELING_API_BASE, LABELING_TOKEN, LABELING_ASSIGNMENT_ID, LABELING_SAMPLE_ID in your private terminal environment.');
if (!/^https?:/.test(base)) throw new Error('Invalid API base');
for (const id of [assignment, sample]) if (!/^[0-9a-f-]{36}$/i.test(id)) throw new Error('Expected actual UUID');
const headers = { Authorization: token.startsWith('Bearer ') ? token : `Bearer ${token}` };
async function request(method, route, body) {
  const response = await fetch(`${base.replace(/\/$/, '')}${route}`, { method,
    headers: { ...headers, ...(body ? { 'Content-Type': 'application/json', 'Idempotency-Key': randomUUID() } : {}) },
    body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(120000) });
  const result = await response.json();
  if (!response.ok) throw new Error(`${response.status} ${result.error?.code}: ${result.error?.message}`);
  return result.data;
}
const mode = process.argv[2] || 'open';
if (mode === 'open') {
  const session = await request('POST', `/assignments/${assignment}/editor-sessions`, { sampleId: sample });
  // Avoid printing the one-time authorization URL into logs. Write it only when explicitly requested.
  console.log(JSON.stringify({ editorSessionId: session.editorSessionId, assignmentId: session.assignmentId,
    sampleId: session.sampleId, actorId: session.actorId, expiresAt: session.expiresAt, launchExpiresAt: session.launchExpiresAt, revision: session.draft.revision }, null, 2));
  if (process.env.LABELING_SHOW_ENTRY === 'true') console.log(new URL(session.editorUrl, new URL(base).origin).href);
  else console.log('The response includes editorUrl. In your frontend, navigate to it. For a private CLI demonstration only, set LABELING_SHOW_ENTRY=true; never share or log that URL.');
} else if (mode === 'sync') {
  console.log(JSON.stringify(await request('POST', `/assignments/${assignment}/editor-sync`, { sampleId: sample }), null, 2));
} else if (mode === 'read') {
  console.log(JSON.stringify(await request('GET', `/assignments/${assignment}/drafts/${sample}`), null, 2));
} else throw new Error('Use open, sync or read');
