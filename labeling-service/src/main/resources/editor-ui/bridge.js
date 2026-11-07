/* No platform Bearer or Label Studio service credentials are exposed. */
(async () => {
 const status=document.getElementById('status'),save=document.getElementById('save');
 const host=window.parent!==window?window.parent:window.opener;
 let store,context,ready=false,saving=false,snapshot='';
 const serialized=()=>store?.annotationStore.selected?.serializeAnnotation()||[];
 const dirty=()=>ready&&JSON.stringify(serialized())!==snapshot;
 const notify=(type,extra={})=>{if(host&&context)host.postMessage({type,editorSessionId:context.editorSessionId,assignmentId:context.assignmentId,projectId:context.projectId,specVersionId:context.specVersionId,sampleId:context.sampleId,actorId:context.actorId,...extra},window.location.origin);};
 const api=async(path,options={})=>{const response=await fetch(path,{credentials:'same-origin',...options});const body=await response.json();if(!response.ok)throw new Error(body.error?.message||`请求失败 (${response.status})`);return body.data;};
 window.addEventListener('beforeunload',event=>{if(saving||dirty()){event.preventDefault();event.returnValue='';}});
 document.getElementById('reload').onclick=()=>{if(!saving&&(!dirty()||window.confirm('当前修改尚未保存，确定重新读取吗？'))){snapshot=JSON.stringify(serialized());window.location.reload();}};
 const saveResult=async requestId=>{
  if(!ready||saving){notify('labeling:save-failed',{requestId,message:'编辑器尚未就绪或正在保存'});return;}
  saving=true;save.disabled=true;status.textContent='正在保存到编辑器并同步回平台…';
  try{
   if(!store.annotationStore.selected)throw new Error('请先选择类别');
   const submitted=serialized();
   const draft=await api('result',{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({baseRevision:context.draft.revision,result:submitted})});
   context.draft=draft;snapshot=JSON.stringify(submitted);
   status.textContent=`已保存到平台，版本 ${draft.revision}。刷新后仍可读取。`;
   notify('labeling:saved',{requestId,revision:draft.revision});
  }catch(error){status.textContent=`未确认平台保存成功：${error.message}。请重试；若版本冲突，请先重新读取。`;notify('labeling:save-failed',{requestId,message:error.message});}
  finally{saving=false;save.disabled=!ready;}
 };
 save.onclick=()=>saveResult();
 window.addEventListener('message',event=>{
  if(!host||event.source!==host||event.origin!==window.location.origin||!context)return;
  const data=event.data;if(!data||data.editorSessionId!==context.editorSessionId||typeof data.requestId!=='string')return;
  if(data.type==='labeling:save')void saveResult(data.requestId);
  if(data.type==='labeling:request-state')notify('labeling:state',{requestId:data.requestId,ready,saving,dirty:dirty()});
  if(data.type==='labeling:discard'&&!saving){snapshot=JSON.stringify(serialized());notify('labeling:discarded',{requestId:data.requestId});}
 });
 try{
  await new Promise((resolve,reject)=>{const script=document.createElement('script');script.type='module';script.src='/api/labeling/v1/editor-assets/main.js';script.onload=resolve;script.onerror=()=>reject(new Error('编辑器脚本下载失败'));document.head.appendChild(script);});
  if(typeof window.LabelStudio!=='function')throw new Error('编辑器资源未加载');
  context=await api('context');
  document.getElementById('identity').textContent=`标注员：${context.actorDisplayName} · 图片：${context.sourcePath}`;
  window.platformEditor=new window.LabelStudio('editor',{
   config:context.config,interfaces:['panel','controls','side-column'],
   user:{id:1,pk:1,firstName:'当前平台标注员',lastName:''},users:[{id:1,firstName:'当前平台标注员',lastName:''}],
   task:{id:1,data:{image:new URL('image',window.location.href).href},annotations:[],predictions:[]},
   onLabelStudioLoad:LS=>{store=LS;const annotation=LS.annotationStore.addAnnotation({userGenerate:true});LS.annotationStore.selectAnnotation(annotation.id);if(context.draft.result?.length)annotation.deserializeAnnotation(context.draft.result);snapshot=JSON.stringify(serialized());ready=true;save.disabled=false;status.textContent=context.draft.revision?`已恢复平台分类结果，版本 ${context.draft.revision}`:'请选择一个类别，然后保存。';notify('labeling:ready',{revision:context.draft.revision});},
  });
 }catch(error){status.textContent=`加载失败：${error.message}。请从平台重新打开或联系管理员。`;}
})();
