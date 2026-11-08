/* Component transport only. Business controls/status belong to the platform frontend. */
(async () => {
 const host=window.parent!==window?window.parent:window.opener;
 const sessionId=new URL(window.location.href).pathname.match(/\/editor-ui\/([^/]+)\//)?.[1];
 let store,context,ready=false,saving=false,snapshot='',loadError=null;
 const serialized=()=>store?.annotationStore.selected?.serializeAnnotation()||[];
 const dirty=()=>ready&&JSON.stringify(serialized())!==snapshot;
 const notify=(type,extra={})=>{
  if(!host)return;
  host.postMessage({type,editorSessionId:sessionId,
   ...(context?{assignmentId:context.assignmentId,projectId:context.projectId,specVersionId:context.specVersionId,sampleId:context.sampleId,actorId:context.actorId}:{}),
   ...extra},window.location.origin);
 };
 const api=async(path,options={})=>{
  const response=await fetch(path,{credentials:'same-origin',...options});
  const body=await response.json();
  if(!response.ok)throw Object.assign(new Error(body.error?.message||`请求失败 (${response.status})`),{code:body.error?.code||'EDITOR_REQUEST_FAILED',status:response.status});
  return body.data;
 };
 const state=()=>({ready,saving,dirty:dirty(),revision:context?.draft.revision??null,...(loadError?{error:loadError}:{})});
 window.addEventListener('beforeunload',event=>{if(saving||dirty()){event.preventDefault();event.returnValue='';}});
 const saveResult=async(requestId,native=false)=>{
  if(!ready||saving){
   const error=Object.assign(new Error('编辑器尚未就绪或正在保存'),{code:saving?'EDITOR_BUSY':'EDITOR_NOT_READY'});
   notify('labeling:save-failed',{requestId,code:error.code,message:error.message});
   if(native)throw error;return;
  }
  saving=true;
  try{
   if(!store.annotationStore.selected)throw Object.assign(new Error('请先选择类别'),{code:'INVALID_RESULT'});
   const submitted=serialized();
   const draft=await api('result',{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({baseRevision:context.draft.revision,result:submitted})});
   context.draft=draft;snapshot=JSON.stringify(submitted);
   // Use public SDK actions so the native control reflects the saved annotation.
   store.annotationStore.selected.addVersions({result:submitted});
   notify('labeling:saved',{requestId,revision:draft.revision,dirty:dirty()});
  }catch(error){notify('labeling:save-failed',{requestId,code:error.code||'EDITOR_REQUEST_FAILED',status:error.status,message:error.message});if(native)throw error;}
  finally{saving=false;}
 };
 window.addEventListener('message',event=>{
  if(!host||event.source!==host||event.origin!==window.location.origin)return;
  const data=event.data;
  if(!data||data.editorSessionId!==sessionId||typeof data.requestId!=='string'||!data.requestId.length||data.requestId.length>200)return;
  if(data.type==='labeling:save')void saveResult(data.requestId);
  if(data.type==='labeling:request-state')notify('labeling:state',{requestId:data.requestId,...state()});
  // Acknowledges leaving without saving; the host must then close or replace this editor.
  if(data.type==='labeling:discard'&&!saving){snapshot=JSON.stringify(serialized());notify('labeling:discarded',{requestId:data.requestId});}
 });
 try{
  context=await api('context');
  if(context.editorSessionId!==sessionId)throw new Error('编辑器会话不匹配');
  await new Promise((resolve,reject)=>{const script=document.createElement('script');script.type='module';script.src='/api/labeling/v1/editor-assets/main.js';script.onload=resolve;script.onerror=()=>reject(new Error('编辑器脚本下载失败'));document.head.appendChild(script);});
  if(typeof window.LabelStudio!=='function')throw new Error('编辑器资源未加载');
  window.platformEditor=new window.LabelStudio('editor',{
   config:context.config,interfaces:['panel','topbar','controls','edit-history','side-column','submit','update','annotations:deny-empty'],
   user:{id:1,pk:1,firstName:'当前平台标注员',lastName:''},users:[{id:1,firstName:'当前平台标注员',lastName:''}],
   task:{id:1,data:{image:new URL('image',window.location.href).href},annotations:[],predictions:[]},
   onSubmitAnnotation:()=>saveResult(undefined,true),
   onUpdateAnnotation:()=>saveResult(undefined,true),
   onLabelStudioLoad:LS=>{store=LS;const annotation=LS.annotationStore.addAnnotation({userGenerate:false,pk:'1'});LS.annotationStore.selectAnnotation(annotation.id);if(context.editorResult?.length)annotation.deserializeAnnotation(context.editorResult);if(context.draft.revision>0)annotation.addVersions({result:context.editorResult});snapshot=JSON.stringify(serialized());ready=true;notify('labeling:ready',{revision:context.draft.revision});},
  });
 }catch(error){loadError={code:error.code||'EDITOR_LOAD_FAILED',status:error.status,message:error.message};notify('labeling:load-failed',loadError);}
})();
