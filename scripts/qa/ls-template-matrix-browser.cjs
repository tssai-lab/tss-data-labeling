// Local acceptance of every currently exposed community template. Never contacts remote services.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');
const { zipSync, unzipSync, strToU8, strFromU8 } = require('fflate');
const { chromium } = require('../../../.tools/browser-tests/node_modules/playwright-core');
const root = path.resolve(__dirname, '../../..');
const evidence = path.join(root, '.tools/template-acceptance');
const base = 'http://127.0.0.1:8001';
const report = process.env.LS_QA_RESUME === '1' && fs.existsSync(path.join(evidence,'matrix.json')) ? JSON.parse(fs.readFileSync(path.join(evidence,'matrix.json'),'utf8')) : { sourceVersion: '1.23.2', syntheticData: true, cases: [], errors: [] };
delete report.success;delete report.infrastructureFailure;
const persist = () => fs.writeFileSync(path.join(evidence, 'matrix.json'), JSON.stringify(report, null, 2));
const hash = b => crypto.createHash('sha256').update(b).digest('hex');
const canonical = value => JSON.stringify(value, (_, v) => typeof v === 'number' ? Math.round(v * 1e6) / 1e6 : v && !Array.isArray(v) && typeof v === 'object' ? Object.fromEntries(Object.entries(v).sort(([a], [b]) => a.localeCompare(b))) : v);
const slug = template => template.id.split('/').at(-1);
const localeSource = fs.readFileSync(path.join(root, 'frontend/vendor/label-studio/platform-config/locale.js'), 'utf8');
const titles = vm.runInNewContext('(' + localeSource.match(/const zh=(\{[\s\S]*?\n\});/)[1] + ')');

(async () => {
 fs.mkdirSync(evidence, { recursive: true });
 const browser = await chromium.launch({ executablePath: 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', headless: true });
 const page = await browser.newPage({ viewport: { width: 1700, height: 1100 } });
 page.setDefaultTimeout(20000);
 page.on('dialog', d => d.accept());
 let current;
 page.on('pageerror', error => (current?.browserErrors || report.errors).push(error.message));
 await page.route('**/*', r => ['localhost', '127.0.0.1'].includes(new URL(r.request().url()).hostname) ? r.continue() : r.abort());
 try {
  await page.goto(base + '/#/user/login');
  await page.getByPlaceholder('请输入用户名或手机号').fill(process.env.LS_TEST_USERNAME || 'local_dev');
  await page.getByPlaceholder('请输入密码', { exact: true }).fill(process.env.LS_TEST_PASSWORD || 'LocalDev_2026');
  await page.getByRole('button', { name: '登录工作空间' }).click();
  await page.waitForURL(u => !u.hash.includes('/user/login'));
  const token = await page.evaluate(() => JSON.parse(sessionStorage.getItem('token') || localStorage.getItem('token')));
  const request = (route, body, method = 'POST') => page.request.fetch(base + '/api/labeling/v1' + route, {
   method, headers: { Authorization: 'Bearer ' + token, 'Idempotency-Key': crypto.randomUUID(),
    'Content-Type': Buffer.isBuffer(body) ? 'application/octet-stream' : 'application/json', ...(Buffer.isBuffer(body) ? { 'X-Chunk-SHA256': hash(body) } : {}) },
   ...(body === undefined ? {} : { data: Buffer.isBuffer(body) ? body : JSON.stringify(body) })
  });
  const api = async (route, body, method = 'POST') => {
   const response = await request(route, body, method);const text=await response.text();let payload;try{payload=JSON.parse(text);}catch{const e=new Error(route+' HTTP '+response.status()+': '+text.slice(0,200));e.infrastructure=true;throw e;}
   assert.ok(response.ok(), route + ' ' + response.status() + ' ' + JSON.stringify(payload));return payload.data;
  };
  const catalog = await api('/label-configs/templates', undefined, 'GET');
  const templates = catalog.templates.filter(t => t.type === 'community');
  assert.equal(templates.length, 17);report.templateCount = templates.length;persist();
  for (const template of templates) {
   if(report.cases.some(c=>c.id===template.id&&c.success))continue;
   if(process.env.LS_TEMPLATE_QA_FILTER&&!new RegExp(process.env.LS_TEMPLATE_QA_FILTER).test(template.id))continue;
   report.cases=report.cases.filter(c=>c.id!==template.id);
   current = { id: template.id, title: template.title, stages: [], browserErrors: [], exports: [] };
   report.cases.push(current);persist();
   const stage = async (name, action) => { await action();current.stages.push(name);persist();console.log(slug(template) + ': ' + name); };
   const type = template.group === 'Time Series Analysis' ? 'TIME_SERIES' : 'COMPUTER_VISION';
   try {
    await stage('http-config-and-example', async () => {
     const checked = await api('/label-configs/validate', { labelConfig: template.config, taskType: type });assert.equal(checked.valid, true);
     const example = await api('/label-configs/sample-task', { labelConfig: template.config, taskType: type });assert.equal(example.isExample, true);
    });
    await stage('catalog-selection-and-preview', async () => {
     await page.goto(base + '/?templateQA=' + slug(template) + '#/labeling/templates');
     await page.locator('.lsf-templates-list__group').filter({ hasText: titles[template.group] }).click();
     await page.getByRole('heading', { name: titles[template.title] || template.title, exact: true }).click();
     await page.waitForFunction(() => window.Htx?.annotationStore?.selected?.names?.size > 0);
     const errors = await page.getByRole('alert').allTextContents();assert.ok(!errors.some(t => /校验失败|加载失败/.test(t)), errors.join('\n'));
    });
    const structure = await page.evaluate(xml => {
     const d = new DOMParser().parseFromString(xml, 'application/xml');
     const nodes = [...d.querySelectorAll('*')].map(el => ({ tag: el.tagName, attrs: Object.fromEntries([...el.attributes].map(a => [a.name,a.value])),
      labels: [...el.children].filter(c => ['Label','Choice'].includes(c.tagName)).map(c => c.getAttribute('value')) }));
     return nodes;
    }, template.config);
    const objects = structure.filter(n => ['Image', 'TimeSeries', 'Text'].includes(n.tag));
    const controls = structure.filter(n => ['RectangleLabels','PolygonLabels','KeyPointLabels','BrushLabels','Labels','Choices','TextArea','TimeSeriesLabels','Rating'].includes(n.tag));
    const data = {}, files = {};
    for (const object of objects) {
     const field = (object.attrs.valueList || object.attrs.value || '').replace(/^\$/, '');
     if (object.tag === 'Image') {
      files['page1.png'] = fs.readFileSync(path.join(evidence, 'page1.png'));
      data[field] = object.attrs.valueList ? ['page1.png','page2.png'] : 'page1.png';
      if (object.attrs.valueList) files['page2.png'] = fs.readFileSync(path.join(evidence, 'page2.png'));
     } else if (object.tag === 'Text') data[field] = 'Which object is shown in the synthetic image?';
     else {
      const columns = structure.filter(n => n.tag === 'Channel').map(n => n.attrs.column);
      const time = object.attrs.timeColumn || 'time';
      files['curve.csv'] = strToU8([time + ',' + columns.join(','), ...Array.from({length:24},(_,i)=>
       (object.attrs.timeFormat ? '2026-01-01 '+String(i).padStart(2,'0')+':00:00.000000' : i) + ',' + columns.map((_,c)=>10+c*5+Math.sin(i/3)*3).join(','))].join('\n'));
      data[field] = 'curve.csv';
     }
    }
    for (const c of controls) if (c.attrs.value?.startsWith('$')) data[c.attrs.value.slice(1)] = [{ value:'QA '+c.attrs.name+' A' },{ value:'QA '+c.attrs.name+' B' }];
    files['tasks.json'] = strToU8(JSON.stringify([{ data, meta: { displayName:'Synthetic template acceptance', qaTemplate:template.id } }]));
    let project, spec, assignment, sample, receipt;
    await stage('native-import-and-frozen-spec', async () => {
     project = await api('/projects', { name:'模板验收 '+slug(template)+' '+Date.now(), taskType:type });current.projectId = project.projectId;
     const bytes = Buffer.from(zipSync(files));
     const batch = await api('/projects/'+project.projectId+'/imports', { filename:'qa.zip', sizeBytes:bytes.length, sha256:hash(bytes) });
     await api('/imports/'+batch.batchId+'/chunks?offset=0', bytes, 'PUT');await api('/imports/'+batch.batchId+'/complete');
     let ready=false;for(let i=0;i<80;i++){const b=await api('/imports/'+batch.batchId,undefined,'GET');if(b.status==='READY'){ready=true;break;}if(b.status==='FAILED')throw Error(JSON.stringify(b));await page.waitForTimeout(250);}assert.ok(ready);
     sample = (await api('/projects/'+project.projectId+'/input-samples',undefined,'GET')).items[0];assert.ok(sample);
     spec = await api('/projects/'+project.projectId+'/spec-versions', { taskType:type, schemaVersion:'label-studio-v1',labels:[],instructions:'Synthetic template acceptance',editorConfig:{ labelConfig:template.config,templateId:template.id } });
     assert.equal(spec.editorConfig.templateId,template.id);assert.ok(spec.editorConfig.templateVersion.startsWith('1.23.2:'));
    });
    const waitEditor = async () => {
     await page.waitForFunction(({objects,id}) => {
      const store=window.Htx,a=store?.annotationStore?.selected;if(store?.task?.id!==id||!a)return false;
      return objects.every(o=>{const model=a.names.get(o.attrs.name);return o.tag==='TimeSeries'?model?.dataObj&&Object.values(model.dataObj).some(v=>Array.isArray(v)&&v.length===24):o.tag==='Image'?model?.naturalWidth>0:!!model;});
     },{objects,id:sample.sampleId});
    };
    await stage('publish-claim-and-real-resource-render',async()=>{
     const plan=await api('/projects/'+project.projectId+'/package-plans',{specVersionId:spec.specVersionId,samples:[{sampleId:sample.sampleId}],packageSize:1});
     const published=await api('/package-plans/'+plan.planId+'/publish',{selectionHash:plan.selectionHash});
     assignment=await api('/packages/'+published.packages[0].packageId+'/claims',{});current.assignmentId=assignment.assignmentId;
     await page.goto(base+'/?templateQA='+slug(template)+'#/labeling/assignments/'+assignment.assignmentId);await waitEditor();
     await page.getByRole('button',{name:'保存草稿',exact:true}).waitFor({state:'visible'});
     await page.waitForFunction(()=>Array.from(document.querySelectorAll('*')).some(el=>el.shadowRoot?.querySelector('.lsf-editor')));
    });
    const results = () => page.evaluate(()=>window.Htx.annotationStore.selected.serializeAnnotation());
    const clickLabel = async c => {
     const value=c.labels[0] || data[c.attrs.value?.slice(1)]?.[0]?.value;assert.ok(value,c.attrs.name);
     await page.locator('.lsf-label_clickable').filter({hasText:value}).first().click();
    };
    const imageDraw = async (control,index=0) => {
     await clickLabel(control);const canvas=page.locator('canvas').first();await canvas.scrollIntoViewIfNeeded();const b=await canvas.boundingBox();assert.ok(b);
     const x=b.x+b.width*(.18+index*.13),y=b.y+b.height*.25,w=b.width*.25,h=b.height*.3;
     if(control.tag==='KeyPointLabels')await page.mouse.click(x,y);
     else if(control.tag==='PolygonLabels'){await page.mouse.click(x,y);await page.mouse.click(x+w,y);await page.mouse.click(x+w,y+h);await page.mouse.click(x,y);}
     else {await page.mouse.move(x,y);await page.mouse.down();await page.mouse.move(x+w,y+h,{steps:12});await page.mouse.up();}
    };
    await stage('native-mouse-and-form-interaction',async()=>{
     for (const [index,c] of controls.filter(c=>['RectangleLabels','PolygonLabels','BrushLabels','KeyPointLabels'].includes(c.tag)).entries())await imageDraw(c,index);
     if(slug(template)==='optical-character-recognition') {
      await clickLabel(controls.find(c=>c.tag==='Labels'));const tool=page.getByRole('button',{name:'矩形框',exact:true});if(await tool.count())await tool.first().click();
      const canvas=page.locator('canvas').first();await canvas.scrollIntoViewIfNeeded();const b=await canvas.boundingBox();await page.mouse.move(b.x+b.width*.2,b.y+b.height*.25);await page.mouse.down();await page.mouse.move(b.x+b.width*.55,b.y+b.height*.5,{steps:10});await page.mouse.up();
     }
     for(const c of controls.filter(c=>c.tag==='TimeSeriesLabels')) {
      await clickLabel(c);const overlay=page.locator('.new_brush .overlay').first();await overlay.scrollIntoViewIfNeeded();const b=await overlay.boundingBox();assert.ok(b);
      if(slug(template)==='change-point-detection')await page.mouse.dblclick(b.x+b.width*.45,b.y+b.height*.4);
      else {await page.mouse.move(b.x+b.width*.25,b.y+b.height*.35);await page.mouse.down();await page.mouse.move(b.x+b.width*.55,b.y+b.height*.35,{steps:12});await page.mouse.up();}
     }
     if(controls.some(c=>c.attrs.perRegion==='true'&&c.attrs.required==='true')){
      const negative=await request('/assignments/'+assignment.assignmentId+'/drafts/'+sample.sampleId,{baseRevision:0,explicitDone:true,resultFormat:'label-studio-v1',result:{result:await results()}},'PUT');
      assert.equal(negative.status(),422,'missing required region value accepted');current.requiredNegativeCheck=true;
     }
     for(const c of controls.filter(c=>c.tag==='Choices')) {
      const exact=new RegExp('^'+c.labels[0].replace(/[.*+?^${}()|[\]\\]/g,'\\$&'));
      const choice=page.getByRole('checkbox',{name:exact}).or(page.getByRole('radio',{name:exact}));await choice.first().check();
     }
     if(controls.some(c=>c.tag==='Rating')){const stars=page.locator('.ant-rate-star');await stars.nth(6).click();}
     const inputs=page.getByLabel('TextArea Input',{exact:true});
     if(controls.some(c=>c.tag==='TextArea'&&c.attrs.displayMode!=='region-list'))await inputs.first().waitFor();
     for(let i=0;i<await inputs.count();i++){
      const input=inputs.nth(i);if(!await input.isVisible())continue;await input.fill('QA annotation '+i);
      await input.press((await input.evaluate(el=>el.tagName))==='TEXTAREA'?'Shift+Enter':'Enter');
     }
     if(slug(template)==='visual-question-answering'){
      const label=controls.find(c=>c.tag==='Labels');await clickLabel(label);
      const text=page.getByText(data.q1,{exact:true}).first();const b=await text.boundingBox();assert.ok(b);
      await page.mouse.move(b.x+3,b.y+b.height/2);await page.mouse.down();await page.mouse.move(b.x+Math.min(120,b.width-3),b.y+b.height/2,{steps:10});await page.mouse.up();
     }
     await page.waitForFunction(()=>window.Htx?.annotationStore?.selected?.serializeAnnotation().length>0);
     const answer=await results();assert.ok(answer.length,'no answer generated');current.resultTypes=[...new Set(answer.map(r=>r.type))];
     for(const c of controls){assert.ok(answer.some(r=>r.from_name===c.attrs.name),'control did not produce answer: '+c.attrs.name);}
     await page.screenshot({path:path.join(evidence,slug(template)+'.png'),fullPage:true});
    });
    const save = async done => {
     const response=page.waitForResponse(r=>r.url().includes('/drafts/')&&r.request().method()==='PUT');
     await page.getByRole('button',{name:done?'标记完成并保存':'保存草稿',exact:true}).click();const r=await response;
     assert.equal(r.status(),200,await r.text());return(await r.json()).data;
    };
    await stage('draft-persistence-and-reload',async()=>{
     receipt=await save(false);assert.equal(receipt.explicitDone,false);const saved=receipt.result.result;
     await page.reload();await waitEditor();assert.equal(canonical(await results()),canonical(saved));
    });
    await stage('complete-submit-review-and-freeze',async()=>{
     receipt=await save(true);assert.equal(receipt.explicitDone,true);
     const submitted=await api('/assignments/'+assignment.assignmentId+'/submissions',{items:[{sampleId:sample.sampleId,revision:receipt.revision}]});
     await api('/submissions/'+submitted.submissionId+'/review/decide',{action:'ACCEPT',reason:'Synthetic template acceptance'});
     const snapshot=await api('/projects/'+project.projectId+'/final-snapshots',{name:'Template QA',specVersionId:spec.specVersionId,sampleIds:[sample.sampleId]});current.snapshotId=snapshot.finalSnapshotId;
    });
    await stage('configuration-based-export-and-content',async()=>{
     const {formats}=await api('/final-snapshots/'+current.snapshotId+'/export-formats',undefined,'GET');current.formats=formats;
     const selected=formats.filter(f=>f.enabled&&['JSON','CSV'].includes(f.name));assert.ok(selected.some(f=>f.name==='JSON'));
     const special=['COCO','YOLO','VOC','BRUSH_TO_PNG'].find(name=>formats.some(f=>f.enabled&&f.name===name));
     if(special)selected.push(formats.find(f=>f.name===special));
     for(const format of selected){
      const job=await api('/final-snapshots/'+current.snapshotId+'/exports',{format:format.name});let ready;
      for(let i=0;i<160;i++){const j=await api('/exports/'+job.exportId,undefined,'GET');if(j.status==='FAILED')throw Error('export '+format.name+' '+JSON.stringify(j));if(j.status==='READY'){ready=j;break;}await page.waitForTimeout(250);}assert.ok(ready,'export timeout');
      const download=await request('/exports/'+job.exportId+'/file',undefined,'GET');assert.ok(download.ok());const bytes=await download.body();
      const entries=unzipSync(bytes);assert.ok(Object.keys(entries).length);fs.writeFileSync(path.join(evidence,slug(template)+'-'+format.name+'.zip'),bytes);
      if(format.name==='JSON'){
       const native=Object.entries(entries).filter(([name])=>name.endsWith('.json')).map(([,bytes])=>{try{return JSON.parse(strFromU8(bytes));}catch{return null;}}).find(value=>Array.isArray(value)&&value.some(t=>t.annotations?.length));
       assert.ok(native,'no native annotation JSON');assert.equal(canonical(native[0].annotations[0].result),canonical(receipt.result.result));assert.ok(native[0].meta.templateVersion);
      }
      current.exports.push({format:format.name,files:Object.keys(entries)});persist();
     }
    });
    assert.deepEqual(current.browserErrors,[]);current.success=true;
   }catch(error){current.success=false;current.failure=error.stack;current.url=page.url();try{await page.screenshot({path:path.join(evidence,slug(template)+'-failed.png'),fullPage:true});}catch{}if(error.infrastructure){report.infrastructureFailure=error.message;persist();break;}}
   persist();console.log(slug(template)+': '+(current.success?'PASS':'FAIL '+current.failure.split('\n')[0]));
  }
  report.success=report.cases.length===17&&report.cases.every(c=>c.success);persist();
  if(!report.success)process.exitCode=1;
 }catch(error){report.failure=error.stack;persist();process.exitCode=1;}
 finally{await browser.close();console.log(JSON.stringify({success:report.success,passed:report.cases.filter(c=>c.success).length,total:report.cases.length,failure:report.failure}));}
})();
