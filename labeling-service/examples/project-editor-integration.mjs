// Node18+. Project/annotation API; no task package or claim is created.
import {randomUUID} from 'node:crypto';
const base=process.env.LABELING_API_BASE?.replace(/\/$/,'');
const token=process.env.LABELING_TOKEN;
const project=process.env.LABELING_PROJECT_ID;
const sample=process.env.LABELING_SAMPLE_ID;
if(!base||!token||!project||!sample)throw new Error('Set LABELING_API_BASE (including /api/labeling/v1), LABELING_TOKEN, LABELING_PROJECT_ID and LABELING_SAMPLE_ID privately.');
if(!/^https?:\/\//.test(base)||![project,sample].every(id=>/^[0-9a-f-]{36}$/i.test(id)))throw new Error('Invalid API base or UUID');
const mode=process.argv[2]||'open';
if(!['open','read'].includes(mode))throw new Error('Use open or read');
const response=await fetch(`${base}/projects/${project}/${mode==='open'?'editor-sessions':`samples/${sample}/annotation`}`,{
 method:mode==='open'?'POST':'GET',headers:{Authorization:token.startsWith('Bearer ')?token:`Bearer ${token}`,
 ...(mode==='open'?{'Content-Type':'application/json','Idempotency-Key':randomUUID()}:{})},
 ...(mode==='open'?{body:JSON.stringify({sampleId:sample})}:{}),signal:AbortSignal.timeout(120000),
});
const body=await response.json();
if(!response.ok)throw new Error(`${response.status} ${body.error?.code}: ${body.error?.message}`);
if(mode==='read')console.log(JSON.stringify(body.data,null,2));
else{
 const {editorUrl,...summary}=body.data;
 console.log(JSON.stringify(summary,null,2));
 if(process.env.LABELING_SHOW_ENTRY==='true')console.log(new URL(editorUrl,new URL(base).origin).href);
 else console.log('Use data.editorUrl in the same-origin frontend. Do not log/share the single-use ticket.');
}
