const fs=require('node:fs'),crypto=require('node:crypto'),assert=require('node:assert/strict');
const {chromium}=require('../../../.tools/browser-tests/node_modules/playwright-core');
const base='http://127.0.0.1:8001',report={steps:[],errors:[],widths:[]};
(async()=>{
 const browser=await chromium.launch({executablePath:'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true});
 const page=await browser.newPage({viewport:{width:1700,height:1050}});page.setDefaultTimeout(30000);let acceptDialogs=true;
 page.on('dialog',d=>acceptDialogs?d.accept():d.dismiss());page.on('pageerror',e=>report.errors.push(e.message));
 await page.route('**/*',r=>['127.0.0.1','localhost'].includes(new URL(r.request().url()).hostname)?r.continue():r.abort());
 const step=async(name,fn)=>{await fn();report.steps.push(name);console.log(name);};
 try{
  await page.goto(base+'/#/user/login');await page.getByPlaceholder('请输入用户名或手机号').fill(process.env.LS_TEST_USERNAME||'local_dev');await page.getByPlaceholder('请输入密码',{exact:true}).fill(process.env.LS_TEST_PASSWORD||'LocalDev_2026');await page.getByRole('button',{name:'登录工作空间'}).click();await page.waitForURL(u=>!u.hash.includes('/user/login'));
  const token=await page.evaluate(()=>JSON.parse(sessionStorage.getItem('token')||localStorage.getItem('token')));
  const api=async(path,body,method='POST',extra={})=>{const r=await page.request.fetch(base+'/api/labeling/v1'+path,{method,headers:{Authorization:'Bearer '+token,'Idempotency-Key':crypto.randomUUID(),'Content-Type':body instanceof Buffer?'application/octet-stream':'application/json',...extra},...(body===undefined?{}:{data:body instanceof Buffer?body:JSON.stringify(body)})});const b=await r.json();assert.ok(r.ok(),JSON.stringify(b));return b.data;};
  const project=await api('/projects',{name:'LS 工作区布局验收-'+Date.now(),taskType:'TIME_SERIES'});report.projectId=project.projectId;
  const {zipSync,strToU8}=require('fflate');const bytes=Buffer.from(zipSync({'tasks.json':strToU8(JSON.stringify([0,1].map(i=>({data:{series:{time:[0,0.25,1.5,2.5],power:[-3,null,0,5]}},meta:{displayName:'布局样本-'+(i+1)}}))))})),sha=b=>crypto.createHash('sha256').update(b).digest('hex');
  const batch=await api('/projects/'+project.projectId+'/imports',{filename:'samples.zip',sizeBytes:bytes.length,sha256:sha(bytes)});
  await api('/imports/'+batch.batchId+'/chunks?offset=0',bytes,'PUT',{'X-Chunk-SHA256':sha(bytes)});await api('/imports/'+batch.batchId+'/complete');
  let ready=false;for(let i=0;i<60;i++){const b=await api('/imports/'+batch.batchId,undefined,'GET');if(b.status==='READY'){ready=true;break;}await new Promise(r=>setTimeout(r,300));}assert.ok(ready);
  const xml='<View><TimeSeries name="curve" value="$series" valueType="json" timeColumn="time" overviewWidth="100%"><Channel column="power"/></TimeSeries><Choices name="c" toName="curve" choice="single-radio" required="true"><Choice value="正常"/><Choice value="异常"/></Choices><TimeSeriesLabels name="events" toName="curve"><Label value="事件"/></TimeSeriesLabels></View>';
  const spec=async(labelConfig)=>api('/projects/'+project.projectId+'/spec-versions',{taskType:'TIME_SERIES',schemaVersion:'label-studio-v1',labels:[],instructions:'先查看曲线，再选择类别。',editorConfig:{labelConfig,templateId:'custom'}});
  const v1=await spec(xml);await spec(xml.replaceAll('正常','正常二').replaceAll('异常','异常二'));
  const samples=(await api('/projects/'+project.projectId+'/input-samples',undefined,'GET')).items;assert.equal(samples.length,2);
  await step('version-bound-preview-and-bulk-selection',async()=>{
   await page.goto(base+'/?layout=manager#/labeling/projects/'+project.projectId);await page.getByText('标注样本（2）',{exact:true}).waitFor();
   await page.getByRole('combobox',{name:'规范版本'}).focus();await page.keyboard.press('ArrowDown');await page.locator('.ant-select-item-option-content').filter({hasText:/^第 1 版$/}).click();
   await page.getByRole('button',{name:'预览',exact:true}).first().click();await page.getByRole('radio',{name:/正常/}).first().waitFor();assert.equal(await page.getByRole('radio',{name:/正常二/}).count(),0);
   await page.getByRole('button',{name:'预览下一条样本'}).click();await page.waitForFunction(id=>window.Htx?.task?.id===id,samples[1].sampleId);await page.getByRole('button',{name:'预览上一条样本'}).click();await page.waitForFunction(id=>window.Htx?.task?.id===id,samples[0].sampleId);
   await page.locator('.ant-drawer-open .ant-drawer-close').click();await page.getByRole('table').first().getByRole('checkbox').first().check();await page.getByRole('button',{name:'发布所选样本',exact:true}).click();await page.getByText(/已选择 2 个样本；/).waitFor();
  });
  const plan=await api('/projects/'+project.projectId+'/package-plans',{specVersionId:v1.specVersionId,samples:samples.map(s=>({sampleId:s.sampleId})),packageSize:2});const published=await api('/package-plans/'+plan.planId+'/publish',{selectionHash:plan.selectionHash});const assignment=await api('/packages/'+published.packages[0].packageId+'/claims',{});report.assignmentId=assignment.assignmentId;
  const items=(await api('/assignments/'+assignment.assignmentId+'/items',undefined,'GET')).items;
  const current=async id=>page.waitForFunction(id=>window.Htx?.task?.id===id&&window.Htx?.annotationStore?.selected?.names.get('curve')?.dataObj?.power?.length===4,id);
  await page.goto(base+'/?layout=editor#/labeling/assignments/'+assignment.assignmentId);await current(items[0].sampleId);
  await step('focused-editor-layout-two-widths',async()=>{
   for(const width of [1280,1700]){await page.setViewportSize({width,height:1050});await page.evaluate(()=>window.scrollTo(0,0));const editor=await page.locator('[data-platform-ls-editor]').boundingBox();const metrics=await page.evaluate(()=>({width:document.documentElement.clientWidth,scroll:document.documentElement.scrollWidth}));assert.ok(metrics.scroll<=metrics.width+1);assert.ok(editor.y<300,JSON.stringify(editor));assert.equal(await page.getByRole('navigation',{name:'标注平台导航'}).isVisible(),false);report.widths.push({width,editorTop:editor.y,editorWidth:editor.width});await page.screenshot({path:`D:/projects/Annotation/.tools/native-tests/ls-focused-${width}.png`});}
  });
  await step('upstream-panel-defaults-and-platform-help',async()=>{
   report.editorMetrics=await page.evaluate(async()=>{
    await document.fonts.load("14px Figtree");
    const root=Array.from(document.querySelectorAll('*')).find(el=>el.shadowRoot)?.shadowRoot;
    const target=root?.querySelector('.lsf-sidepanels__wrapper_align_right > div');
    const editor=root?.querySelector('.lsf-editor');
    return {fontLoaded:document.fonts.check('14px Figtree'),font:editor?getComputedStyle(editor).fontFamily:null, fontSize:editor?getComputedStyle(editor).fontSize:null,
     panelWidth:target?.getBoundingClientRect().width,
     links:root?Array.from(root.querySelectorAll('a[href]')).map(a=>a.href):[]};
   });
   assert.equal(report.editorMetrics.fontSize,'14px');
   assert.equal(report.editorMetrics.fontLoaded,true);
   assert.equal(report.editorMetrics.panelWidth,320);
   assert.ok(report.editorMetrics.font.includes('Figtree'));
   assert.ok(report.editorMetrics.links.every(url=>!url.includes('labelstud.io')&&!url.includes('docs.humansignal.com')));
   const help=await browser.newPage();await help.goto(base+'/#/labeling/help?topic=guide%2Flabeling');
   await help.getByText('标注使用说明',{exact:true}).waitFor();await help.getByText('时序坐标与缩放',{exact:true}).waitFor();await help.close();
  });
  await step('native-settings-and-task-list',async()=>{
   const settings=page.getByRole('button',{name:'设置',exact:true});await settings.hover();const tip=page.getByTestId('tooltip').filter({hasText:'设置'}).last();await tip.waitFor({state:'visible'});assert.ok(await tip.evaluate(el=>el.getRootNode() instanceof ShadowRoot));let positioned=false;for(let frame=0;frame<30;frame++){const tipBox=await tip.boundingBox(),triggerBox=await settings.boundingBox();if(Math.abs(tipBox.y-triggerBox.y)<100){positioned=true;break;}await page.waitForTimeout(50);}assert.ok(positioned,'tooltip did not align with its trigger');await settings.click();await page.locator('.ant-modal:visible').waitFor();await page.locator('.ant-modal-close:visible').click();
   await page.getByRole('button',{name:'任务包样本',exact:true}).click();await page.getByLabel('搜索任务包样本',{exact:true}).fill('no-match');assert.equal(await page.locator('[aria-current="true"]').count(),0);await page.getByLabel('搜索任务包样本',{exact:true}).fill('');await page.locator('.ant-drawer-open .ant-drawer-close').click();
  });
  const save=async(done)=>{const saved=page.waitForResponse(r=>r.url().includes('/drafts/')&&r.request().method()==='PUT');await page.getByRole('button',{name:done?'标记完成并保存':'保存草稿',exact:true}).click();const r=await saved;assert.equal(r.status(),200);return(await r.json()).data;};
  await step('unsaved-switch-guard-and-draft-restore',async()=>{
   await page.getByRole('radio',{name:/正常/}).first().check();acceptDialogs=false;await page.getByRole('button',{name:'下一条样本',exact:true}).click();await current(items[0].sampleId);assert.ok(await page.getByRole('radio',{name:/正常/}).first().isChecked());acceptDialogs=true;
   const draft=await save(false);assert.equal(draft.explicitDone,false);await page.getByRole('button',{name:'下一条样本',exact:true}).click();await current(items[1].sampleId);await page.getByRole('radio',{name:/异常/}).first().check();await save(true);
   await page.getByRole('button',{name:'上一条样本',exact:true}).click();await current(items[0].sampleId);assert.ok(await page.getByRole('radio',{name:/正常/}).first().isChecked());await save(true);
  });
  await step('unified-save-bar-and-submit',async()=>{
   assert.equal(await page.getByRole('button',{name:'保存草稿',exact:true}).count(),1);assert.equal(await page.getByRole('button',{name:'提交整个任务包',exact:true}).count(),1);const r=page.waitForResponse(r=>r.url().includes('/submissions')&&r.request().method()==='POST');await page.getByRole('button',{name:'提交整个任务包',exact:true}).click();assert.equal((await r).status(),201);await page.getByRole('heading',{name:'已提交结果',exact:true}).waitFor();assert.equal(await page.getByRole('button',{name:'标记完成并保存',exact:true}).count(),0);
  });
  assert.deepEqual(report.errors,[]);report.success=true;
 }catch(e){report.failure=e.stack;report.url=page.url();report.body=(await page.locator('body').innerText()).slice(-4500);await page.screenshot({path:'D:/projects/Annotation/.tools/native-tests/ls-layout-failed.png',fullPage:true});process.exitCode=1;}
 finally{fs.writeFileSync('D:/projects/Annotation/.tools/native-tests/ls-layout-regression.json',JSON.stringify(report,null,2));console.log(JSON.stringify(report));await browser.close();}
})();
