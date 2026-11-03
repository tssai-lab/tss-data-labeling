/** 本轮页面隔离夹具：真实 React 页面/Hook，服务由固定测试回执替代，禁止真实写入。 */
import { build } from 'esbuild';
import express from 'express';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const root = fileURLToPath(new URL('../../', import.meta.url));
const platform = `
  const qa = () => window.__qa;
  export async function listExperimentVersions(id) {
    qa().calls.push(['history',id]); const mode=qa().mode;
    if(mode==='held') await new Promise(resolve=>qa().held.push(resolve));
    if(mode==='error') throw new Error('历史服务故障');
    if(mode==='invalid') return {};
    return { data: mode==='empty'?[]:[{id:'version-'+id,name:id,status:'success'}] };
  }
  const versions=['a','b'].map(id=>({id:'model-ver-'+id,assetId:'asset',version:'v'+id,status:'READY',type:'CV',fileName:'weights.zip',sizeBytes:12}));
  export async function fetchModelAssetDetail(){ return {data:{id:'asset',name:'测试模型',type:'CV',versions,currentVersionId:'model-ver-a',latestVersion:versions[0]}}; }
  export async function getModelVersion(id){ return {data:versions.find(v=>v.id===id)}; }
  export async function fetchModelVersionCodePreview(id){
    qa().calls.push(['code',id]); const mode=qa().mode;
    if(mode==='held') await new Promise(resolve=>qa().held.push(resolve));
    if(mode==='error') throw new Error('代码目录故障');
    return {data:{...versions.find(v=>v.id===id),codeFiles: mode==='empty'?[]:[{path:'train.py',fileName:'train.py'}],codeContent:'文件属于 '+id,codeFilePath:'train.py'}};
  }
  export async function fetchModelConsumerManifest(id){
    if(qa().mode==='error') throw new Error('消费清单故障'); return {modelVersionId:id,modelAssetId:'asset'};
  }
  export async function listModelCodeFiles(){ if(qa().mode==='error')throw new Error('README 服务故障');return {data:qa().mode==='empty'?[]:[{path:'README.md'}]}; }
  export async function previewModelCode(id,path){ if(qa().mode==='error')throw new Error('README 服务故障');return {data:{path,content:'README '+id}}; }
  export const fetchConsumerManifest=async()=>{throw new Error('未声明数据集读取');};
  export const fetchMultimodalDataPreview=fetchConsumerManifest,fetchMultimodalSampleDetail=fetchConsumerManifest,fetchMultimodalSamples=fetchConsumerManifest;
  const blocked=async()=>{qa().writes++;throw new Error('夹具禁止写入');};
  export const deleteModelAsset=blocked,deleteModelVersion=blocked,downloadModelVersion=blocked,switchModelCurrentVersion=blocked,updateModelAsset=blocked;
`;
const result = await build({
  absWorkingDir: root, stdin: { loader: 'jsx', resolveDir: root, contents: `
    import React from 'react'; import {createRoot} from 'react-dom/client';
    import '@ant-design/v5-patch-for-react-19'; import {Form} from 'antd';
    import {useExperimentVersions} from './src/pages/task/detail/useExperimentVersions';
    import ModelDetail from './src/pages/model/detail/[id]';
    import Readme from './src/components/ZipReadmePanel';
    import * as Steps from './src/pages/task/create/TrainingCreateSteps';
    window.__qa={mode:new URLSearchParams(location.search).get('mode')||'success',calls:[],held:[],writes:0,navigation:[],release(){this.held.splice(0).forEach(r=>r());}};
    const plan={id:'cv',version:'1',displayName:'测试 CV 方案',category:'CV',enabled:true,trainingModes:['SINGLE'],
      inputs:{model:{taskTypes:['CV']},dataset:{taskTypes:['CV']},code:{required:true}},
      execution:{interpreter:'python',entrypoint:'train.py',arguments:[]},parameters:[],
      runtimes:[{id:'gpu',deviceType:'NVIDIA_GPU',resourceProfiles:[{id:'gpu-small',cpuRequest:'1',cpuLimit:'4',memoryRequest:'2Gi',memoryLimit:'8Gi',gpuCount:1}]}]};
    const hardware={hardwareTargetId:'gpu-one',displayName:'测试 RTX 5090',resourceProfileId:'gpu-small',deviceType:'NVIDIA_GPU',cpu:{requestCores:1,limitCores:4},memory:{requestMiB:2048,limitMiB:8192},gpuCount:1,eligibleNodeCount:1,gpu:{model:'测试 RTX 5090',metricsComplete:true,safeTotalMemoryMiB:32000,maxFreeMemoryMiB:16000,observedGpuCount:1},dataStatus:'AVAILABLE'};
    function History(){const[id,setId]=React.useState('a');const state=useExperimentVersions(id);return <section>
      <button onClick={()=>setId('a')}>实验 A</button><button onClick={()=>setId('b')}>实验 B</button>
      <button disabled={state.loading} onClick={()=>state.refreshVersions()}>刷新历史</button>
      <output id="history-error">{state.error}</output><output id="history-data">{state.versions.map(v=>v.id).join(',')}</output>
    </section>;}
    function TrainingSteps(){const[step,setStep]=React.useState(0);const[form]=Form.useForm();const mode=Form.useWatch('resourceMode',form)||'recommended';const[codeInputMode,setCodeInputMode]=React.useState('select');
      const note=(...values)=>window.__qa.calls.push(['callback',...values]);
      const state={form,isExperimentContinue:false,trainingPlans:[plan],selectedTrainingPlan:plan,selectedTrainingPlanId:'cv',
        setSelectedTrainingPlanId:note,setSelectedBaseModelVersionId:note,setSelectedDatasetVersionId:note,setSelectedCodeVersionId:note,setSelectedCodeApprovalStatus:note,setCodeCheck:note,
        selectedBaseModelVersionId:'model-ver-a',selectedDatasetVersionId:'dataset-ver-a',selectedCodeVersionId:'code-ver-a',
        modelLoading:false,filteredModelSelectOptions:[{id:'model-ver-a',name:'测试权重',version:'v1',type:'CV'}],reloadModelOptions:async()=>note('reload-model'),specDrivenModel:false,acceptedModelSpecIds:[],selectedModel:{name:'测试权重',version:'v1',type:'CV'},
        datasetSelectionReady:true,specDrivenDataset:false,requiredDatasetType:'CV',datasetLoading:false,filteredDatasetOptions:[{id:'dataset-ver-a',name:'测试数据集',version:'v1',type:'CV'}],reloadDatasetOptions:async()=>note('reload-dataset'),acceptedDatasetSpecIds:[],
        codeInputMode,setCodeInputMode,codeLoading:false,filteredCodeOptions:[{codeVersionId:'code-ver-a',codeAssetName:'测试代码',version:'v1'}],codeUploading:false,uploadTrainingCodeZip:async()=>{throw new Error('不执行上传');},selectedCode:{codeAssetName:'测试代码',version:'v1'},selectedCodeApprovalStatus:'APPROVED',renderCodeCheckAlert:()=>null,
        hardwareOptionsLoading:false,hardwareOptions:[hardware],hardwareOptionsError:null,resourceMode:mode,
        selectedResourceProfile:{...plan.runtimes[0].resourceProfiles[0],deviceType:'NVIDIA_GPU'},selectedHardwareOption:hardware,
        resourceStatus:{type:'success',message:'测试硬件可用',description:'测试回执'},codeCheck:{loading:false,passed:true,approvalStatus:'APPROVED'}};
      window.__qa.values=()=>form.getFieldsValue(true);const Step=Steps['TrainingStep'+step];return <section>
        {[0,1,2,3,4,5].map(n=><button key={n} onClick={()=>setStep(n)}>步骤 {n+1}</button>)}
        <Form form={form} preserve layout="vertical" initialValues={{resourceMode:'recommended',hardwareTargetId:'gpu-one',hyperParams:'{}'}}><Step state={state}/></Form>
      </section>;
    }
    const view=new URLSearchParams(location.search).get('view');
    createRoot(document.getElementById('root')).render(<React.StrictMode><main style={{padding:24}}><h1>本轮隔离测试，无真实后端</h1>{view==='model'?<ModelDetail/>:view==='readme'?<Readme source="model" versionId="model-ver-a"/>:view==='steps'?<TrainingSteps/>:<History/>}</main></React.StrictMode>);
  ` }, bundle: true, write: false, outfile: 'qa-bundle.js', format: 'iife', platform: 'browser',
  define: {'process.env.NODE_ENV':'"development"'}, plugins:[{
    name:'six-issue-isolation',setup(builder){
      builder.onResolve({filter:/^@umijs\/max$/},()=>({path:'umi',namespace:'qa'}));
      builder.onResolve({filter:/^@\/services\/platform$/},()=>({path:'platform',namespace:'qa'}));
      builder.onResolve({filter:/^@\//},args=>builder.resolve(path.resolve(root,'src',args.path.slice(2)),{resolveDir:root,kind:args.kind}));
      builder.onLoad({filter:/^platform$/,namespace:'qa'},()=>({contents:platform,loader:'js'}));
      builder.onLoad({filter:/^umi$/,namespace:'qa'},()=>({contents:`const search=new URLSearchParams();export const useParams=()=>({id:'asset'});export const useSearchParams=()=>[search];export const history={createHref:p=>p.pathname,push:p=>window.__qa.navigation.push(p),replace:p=>window.__qa.navigation.push(p)};export const request=async()=>{throw new Error('夹具禁止真实网络请求');};`,loader:'js'}));
    }
  }]
});
const app=express();app.get('/',(_req,res)=>res.type('html').send('<!doctype html><html lang="zh"><meta charset="utf-8"><link rel="stylesheet" href="/bundle.css"><div id="root"></div><script src="/bundle.js"></script></html>'));
app.get('/favicon.ico',(_req,res)=>res.status(204).end());
app.get('/bundle.js',(_req,res)=>res.type('js').send(result.outputFiles.find(file=>file.path.endsWith('.js')).text));
app.get('/bundle.css',(_req,res)=>res.type('css').send(result.outputFiles.find(file=>file.path.endsWith('.css'))?.text||''));
app.use((_req,res)=>res.status(405).send('No backend; writes forbidden'));
app.listen(18894,'127.0.0.1',()=>console.log('six-issue harness http://127.0.0.1:18894'));
