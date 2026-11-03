import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';
import ts from 'typescript';
import { loadTypeScriptModule } from '../../scripts/qa/load-typescript-module.mjs';

const deferred = () => { let resolve, reject; const promise = new Promise((yes,no) => { resolve=yes; reject=no; }); return {promise,resolve,reject}; };
const checked = (patch = {}) => ({success:true,data:{codeVersionId:'v',trainingProfile:'plan',passed:true,...patch}});
function training(request) {
  return loadTypeScriptModule(new URL('./trainingCode/approval.ts',import.meta.url), {
    '@umijs/max':{request}, '../codeV2':{validateV2CodeVersion:async()=>{throw new Error('禁止替换为包校验');}},
  });
}
test('准入：明确拒绝、服务错误、超时及取消只请求一次，异常原样上抛',async()=>{
  for(const error of [401,403,409,429,500,503].map(status=>({response:{status}})).concat([new Error('timeout'),{name:'AbortError'},{response:{status:404,data:{errorCode:'CODE_NOT_FOUND'}}}])) {
    const calls=[];const api=training(async(...args)=>{calls.push(args);throw error;});
    await assert.rejects(api.checkCodeVersionForTraining('v','plan'),e=>e===error);
    assert.equal(calls.length,1);
  }
});
test('准入：接口不支持也不能改用不等价 V2 校验',async()=>{
  for(const status of [404,405,501]) {
    let count=0;const api=training(async()=>{count++;throw {response:{status}};});
    await assert.rejects(api.checkCodeVersionForTraining('v','plan'),/不支持.*准入/);assert.equal(count,1);
  }
});
test('准入：正常通过及不通过保持后端结论和检查范围',async()=>{
  for(const passed of [true,false]) {
    const raw=checked({passed,reasons:passed?[]:['TRAINING_PROFILE_MISMATCH']});
    const api=training(async(url,options)=>{assert.match(url,/trainingProfile=plan/);assert.equal(options.method,'GET');return raw;});
    const result=await api.checkCodeVersionForTraining('v','plan');assert.equal(result,raw);
  }
});
test('准入：业务失败、空/畸形、错版本和错方案不产生通过结论',async()=>{
  for(const raw of [null,{}, {success:false,data:checked().data}, {success:true,data:null},checked({passed:'true'}),checked({codeVersionId:'other'}),checked({trainingProfile:'other'}),checked({errorCode:'FAILED'}),{...checked(),code:403}]) {
    let count=0;const api=training(async()=>{count++;return raw;});
    await assert.rejects(api.checkCodeVersionForTraining('v','plan'));assert.equal(count,1);
  }
});

function sourceCallback(file,name) {
  const text=readFileSync(new URL(file,import.meta.url),'utf8');const ast=ts.createSourceFile(file,text,ts.ScriptTarget.Latest,true,ts.ScriptKind.TSX);let found;
  function visit(node){if(ts.isVariableDeclaration(node)&&node.name.getText(ast)===name)found=node.initializer.getText(ast);ts.forEachChild(node,visit);}visit(ast);
  assert.ok(found);return found;
}
function evaluate(source,context) {
  context.exports={};vm.runInNewContext(ts.transpileModule('exports.run='+source+';',{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS}}).outputText,context);return context.exports.run;
}
const asset = id => ({id,name:id,type:'CV',versions:[],currentVersionId:'v-'+id,defaultVersionId:'v-'+id});
const workspace = id => ({workspaceId:'ws-'+id,datasetId:id,workspaceRevision:2,status:'DRAFT',targetVersion:{versionId:'draft-'+id}});
function detailPage(kind,{fetch=async id=>({data:asset(id)}),getWorkspace=async id=>workspace(id.slice(3)),getVersion=async()=>({data:{assetId:'a'}})}={}) {
  const state={asset:null,workspace:null,version:undefined,error:'',workspaceError:'',loading:false,messages:[],redirects:[],applied:0};
  const sequence={current:0},active={current:true},route={current:'a'},query={current:''};
  const source=sourceCallback(`../pages/${kind}/detail/[id].tsx`,kind==='model'?'loadAsset':'loadDetail');
  const setAsset=value=>{state.asset=typeof value==='function'?value(state.asset):value;state.applied++;};
  function reader(id='a',search='') {
    return evaluate(source,{id,searchParams:new URLSearchParams(search),useCallback:fn=>fn,
      detailReadSequence:sequence,detailReadActive:active,detailRouteRef:route,
      detailQueryRef:query,
      activeWorkspaceRef:{get current(){return state.workspace;}},
      setLoading:v=>state.loading=v,setDetailError:v=>state.error=v,setWorkspaceReadError:v=>state.workspaceError=v,
      fetchModelAssetDetail:fetch,fetchDatasetDetail:fetch,getModelVersion:getVersion,
      setAssetInfo:setAsset,setDatasetInfo:setAsset,setActiveWorkspace:v=>state.workspace=v,
      setSelectedVersionId:v=>state.version=v,setPreviewVersionId:v=>state.version=typeof v==='function'?v(state.version):v,
      resolveModelVersionId:v=>v?.id,resolveDatasetVersionId:v=>v?.id,loadImportJobId:()=>null,
      getDatasetWorkspace:getWorkspace,supportsDatasetWorkspaceEdit:()=>true,
      applyWorkspaceState:v=>{state.workspace=v;state.version=v.targetVersion?.versionId;},
      workspaceReadFailure:loadTypeScriptModule(new URL('../utils/datasetWorkspaceRead.ts',import.meta.url)).workspaceReadFailure,
      requireMatchingWorkspace:loadTypeScriptModule(new URL('../utils/datasetWorkspaceRead.ts',import.meta.url)).requireMatchingWorkspace,
      message:{error:v=>state.messages.push(v)},getApiErrorMessage:(e,f)=>e?.message||f,
      history:{replace:v=>state.redirects.push(v)},
    });
  }
  return {reader,state,route,query,dispose(){active.current=false;sequence.current++;}};
}
for(const kind of ['model','dataset']) {
  test(`${kind} 同资产版本参数改变后旧回调不得再读取或回写`,async()=>{
    const held=deferred();let calls=0;const p=detailPage(kind,{fetch:()=>++calls===1?held.promise:Promise.resolve({data:asset('a')})});
    const old=p.reader(),pending=old();p.query.current='versionId=new';await p.reader('a','versionId=new')();
    held.resolve({data:asset('a')});await pending;await old();assert.equal(calls,2);assert.equal(p.state.version,'new');
  });
  test(`${kind} 主详情：A 晚于 B 不串数据`,async()=>{
    const held=deferred();const p=detailPage(kind,{fetch:async id=>id==='a'?held.promise:{data:asset(id)}});
    const first=p.reader('a')();p.route.current='b';await p.reader('b')();held.resolve({data:asset('a')});await first;
    assert.equal(p.state.asset.id,'b');assert.equal(p.state.version,'v-b');
  });
  test(`${kind} 主详情：旧失败/finally 不清新状态或提前取消加载`,async()=>{
    const one=deferred(),two=deferred();let count=0;const p=detailPage(kind,{fetch:()=>++count===1?one.promise:two.promise});
    const first=p.reader()(),last=p.reader()();one.reject(new Error('old error'));await first;
    assert.equal(p.state.loading,true);assert.equal(p.state.error,'');assert.equal(p.state.messages.length,0);
    two.resolve({data:asset('a')});await last;assert.equal(p.state.loading,false);
  });
  test(`${kind} 主详情：卸载及旧回调无权更新`,async()=>{
    const held=deferred();let calls=0;const p=detailPage(kind,{fetch:()=>{calls++;return held.promise;}});
    const read=p.reader(),pending=read();p.dispose();held.resolve({data:asset('a')});await pending;await read();
    assert.equal(p.state.applied,0);assert.equal(calls,1);
  });
  test(`${kind} 主详情：错误对象、错资产不能成为可操作详情，重试可恢复`,async()=>{
    for(const raw of [{}, {data:asset('other')}, {success:false,data:asset('a')}]) {
      let count=0;const p=detailPage(kind,{fetch:async()=>++count===1?raw:{data:asset('a')}});
      await p.reader()();assert.ok(p.state.error);assert.equal(p.state.asset,null);
      await p.reader()();assert.equal(p.state.error,'');assert.equal(p.state.asset.id,'a');
    }
  });
}
test('模型旧版本链接返回迟到，不能把新路由跳回旧资产',async()=>{
  const held=deferred();const p=detailPage('model',{getVersion:()=>held.promise});p.route.current='model-ver-old';
  const pending=p.reader('model-ver-old')();p.route.current='b';await p.reader('b')();held.resolve({data:{assetId:'a'}});await pending;
  assert.equal(p.state.redirects.length,0);assert.equal(p.state.asset.id,'b');
});
test('草稿网络失败/裸 404 保留同资产工作区，提示且重试恢复',async()=>{
  for(const failure of [new Error('timeout'),{response:{status:500}},{response:{status:404}}]) {
    let count=0;const p=detailPage('dataset',{fetch:async()=>({data:{...asset('a'),workspaceId:'ws-a'}}),getWorkspace:async()=>{if(++count===1)throw failure;return workspace('a');}});
    p.state.workspace=workspace('a');p.state.version='draft-a';await p.reader()();
    assert.equal(p.state.workspace.workspaceId,'ws-a');assert.equal(p.state.version,'draft-a');assert.ok(p.state.workspaceError);
    await p.reader()();assert.equal(p.state.workspaceError,'');assert.equal(p.state.workspace.workspaceRevision,2);
  }
});
test('明确不可用/权限拒绝只清本地编辑引用，不声称服务器草稿被删除',async()=>{
  for(const error of [{response:{status:404,data:{errorCode:'DATASET_WORKSPACE_NOT_FOUND'}}},{response:{status:403}},{response:{status:401}}]) {
    const p=detailPage('dataset',{fetch:async()=>({data:{...asset('a'),workspaceId:'ws-a'}}),getWorkspace:async()=>{throw error;}});
    p.state.workspace=workspace('a');await p.reader()();assert.equal(p.state.workspace,null);assert.ok(p.state.workspaceError);
  }
});
test('详情暂缺工作区编号，仍核实同一资产已有草稿，不因字段缺失解除错误',async()=>{
  let calls=0;const p=detailPage('dataset',{getWorkspace:async()=>{calls++;throw new Error('timeout');}});
  p.state.workspace=workspace('a');p.state.workspaceError='之前读取失败';await p.reader()();
  assert.equal(calls,1);assert.ok(p.state.workspaceError);assert.equal(p.state.workspace.workspaceId,'ws-a');
  p.state.workspace=workspace('b');await p.reader()();assert.equal(calls,1,'不拿其它资产的工作区补查');
});
test('草稿回执必须匹配资产/工作区及 revision；不能把畸形回执视为编辑授权',async()=>{
  for(const ws of [null,{}, {...workspace('a'),datasetId:'b'},{...workspace('a'),workspaceId:'other'},{...workspace('a'),workspaceRevision:-1},{...workspace('a'),status:'READY'}]) {
    const p=detailPage('dataset',{fetch:async()=>({data:{...asset('a'),workspaceId:'ws-a'}}),getWorkspace:async()=>ws});
    await p.reader()();assert.ok(p.state.workspaceError);assert.equal(p.state.workspace,null);
  }
});
test('工作区等待期间切换资产，旧成功和错误均不影响新详情',async()=>{
  for(const rejected of [false,true]) {
    const held=deferred(),started=deferred();const p=detailPage('dataset',{fetch:async id=>({data:{...asset(id),workspaceId:'ws-'+id}}),getWorkspace:id=>{if(id==='ws-a'){started.resolve();return held.promise;}return Promise.resolve(workspace('b'));}});
    const pending=p.reader('a')();await started.promise;p.route.current='b';await p.reader('b')();
    rejected?held.reject(new Error('old')):held.resolve(workspace('a'));await pending;
    assert.equal(p.state.asset.id,'b');assert.equal(p.state.workspace.workspaceId,'ws-b');assert.equal(p.state.workspaceError,'');
  }
});

test('训练代码/方案切换后旧准入成功和失败不回写',async()=>{
  const file='../pages/task/create/useTrainingCreate.tsx';const text=readFileSync(new URL(file,import.meta.url),'utf8');
  const ast=ts.createSourceFile(file,text,ts.ScriptTarget.Latest,true,ts.ScriptKind.TSX);let effect;
  function visit(node){if(ts.isCallExpression(node)&&node.expression.getText(ast)==='useEffect'&&node.arguments[0].getText(ast).includes('checkCodeVersionForTraining('))effect=node.arguments[0].getText(ast);ts.forEachChild(node,visit);}visit(ast);assert.ok(effect);
  for(const reject of [false,true]) {
    const held=deferred();const state={check:null,approval:undefined};
    const cleanup=evaluate(effect,{selectedCodeVersionId:'v',selectedTrainingPlanId:'plan',selectedCodeApprovalStatus:'PENDING',CONSISTENCY_TRAINING_PROFILE:'plan',
      setCodeCheck:v=>state.check=v,setSelectedCodeApprovalStatus:v=>state.approval=v,checkCodeVersionForTraining:()=>held.promise}) ();
    cleanup();state.check={loading:false,passed:false,reasons:['new selection']};
    reject?held.reject(new Error('old failure')):held.resolve(checked({approvalStatus:'APPROVED'}));
    await new Promise(resolve=>setImmediate(resolve));assert.equal(state.check.reasons[0],'new selection');assert.equal(state.approval,undefined);
  }
});
