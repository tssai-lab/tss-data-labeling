/** 三处边界的本地夹具：真实详情页 + 受控读取；无真实 API，禁止业务写入。 */
import { build } from 'esbuild';
import express from 'express';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const root=fileURLToPath(new URL('../../',import.meta.url));
const platform=`
  const qa=()=>window.__qa;
  async function read(kind,id,value){const mode=qa().modes[kind]||'success';qa().calls.push([kind,id,mode]);
    if(mode==='held')await new Promise((resolve,reject)=>qa().held.push({kind,id,resolve,reject}));
    if(mode==='error')throw new Error(kind+' timeout');
    if(mode==='forbidden')throw {response:{status:403}};
    if(mode==='notfound')throw {response:{status:404,data:{errorCode:'DATASET_WORKSPACE_NOT_FOUND'}}};
    if(mode==='bare404')throw {response:{status:404}};
    if(mode==='invalid')return {};
    return value;
  }
  const version=(id,kind)=>({id:kind+'-ver-'+id,assetId:id,version:'v1',status:'READY',type:'CV',fileName:'sample.zip',sizeBytes:12});
  const asset=(id,kind)=>({id,name:(kind==='model'?'模型 ':'数据集 ')+id,type:'CV',versions:[version(id,kind)],latestVersion:version(id,kind),currentVersionId:kind+'-ver-'+id,defaultVersionId:kind+'-ver-'+id,...(kind==='dataset'?{workspaceId:'ws-'+id,hasDraft:true}:{})});
  export const fetchModelAssetDetail=id=>read('model',id,{data:asset(id,'model')});
  export const fetchDatasetDetail=id=>read('dataset',id,{data:asset(id,'dataset')});
  export const getDatasetWorkspace=id=>read('workspace',id,{workspaceId:id,datasetId:id.slice(3),workspaceRevision:2,status:'DRAFT',targetVersion:{versionId:'draft-'+id.slice(3),versionLabel:'v2'}});
  export const getModelVersion=id=>read('alias',id,{data:{id,assetId:'a'}});
  export const fetchModelVersionCodePreview=id=>read('code',id,{data:{id,codeFiles:[],codeContent:''}});
  export const fetchModelConsumerManifest=async id=>({modelVersionId:id});
  export const previewModelCode=async (id,path)=>({data:{path,content:'隔离文件 '+id}});
  export const extractActiveImportJobId=()=>undefined;
  const blocked=async()=>{qa().writes++;throw new Error('夹具禁止业务写入');};
  export const deleteModelAsset=blocked,deleteModelVersion=blocked,downloadModelVersion=blocked,switchModelCurrentVersion=blocked,updateModelAsset=blocked;
  export const abandonDatasetWorkspace=blocked,createDatasetVersion=blocked,createOrOpenDatasetWorkspace=blocked,deleteDataset=blocked,deleteDatasetVersion=blocked,downloadDatasetVersion=blocked,downloadObjectWithBrowser=blocked,getDatasetVersionAllocation=blocked,switchDatasetCurrentVersion=blocked,updateDatasetVersion=blocked,updateDatasetVersionStatus=blocked;
`;
const umi=`
  import React from 'react';
  const subscribe=listener=>{window.__qa.listeners.add(listener);return()=>window.__qa.listeners.delete(listener);};
  export const useParams=()=>({id:React.useSyncExternalStore(subscribe,()=>window.__qa.route)});
  export const useSearchParams=()=>[window.__qa.search];
  export const history={createHref:p=>p.pathname,push:p=>window.__qa.navigation.push(p),replace:p=>window.__qa.navigation.push(p)};
  export const request=async()=>{throw new Error('无真实接口');};
`;
const result=await build({absWorkingDir:root,stdin:{loader:'jsx',resolveDir:root,contents:`
  import React from 'react';import {createRoot} from 'react-dom/client';import '@ant-design/v5-patch-for-react-19';
  import Model from './src/pages/model/detail/[id]';import Dataset from './src/pages/dataset/detail/[id]';
  window.__qa={route:'a',search:new URLSearchParams(),listeners:new Set(),modes:{},calls:[],held:[],writes:0,navigation:[],
    setRoute(id){this.route=id;this.listeners.forEach(fn=>fn());},release(error){this.held.splice(0).forEach(item=>error?item.reject(new Error('old timeout')):item.resolve());}};
  const Page=new URLSearchParams(location.search).get('view')==='dataset'?Dataset:Model;
  function App(){const[mounted,setMounted]=React.useState(true);return <main style={{padding:24}}>
    <h1>隔离边界回归，不连接线上</h1><nav><button onClick={()=>window.__qa.setRoute('a')}>测试切换 A</button><button onClick={()=>window.__qa.setRoute('b')}>测试切换 B</button><button onClick={()=>setMounted(v=>!v)}>测试挂载切换</button></nav>
    {mounted&&<Page/>}</main>;}
  createRoot(document.getElementById('root')).render(<React.StrictMode><App/></React.StrictMode>);
`},bundle:true,write:false,outfile:'boundary.js',format:'iife',platform:'browser',define:{'process.env.NODE_ENV':'"development"'},plugins:[{
  name:'boundary-isolation',setup(builder){
    builder.onResolve({filter:/^@umijs\/max$/},()=>({path:'umi',namespace:'qa'}));
    builder.onResolve({filter:/^@\/services\/platform$/},()=>({path:'platform',namespace:'qa'}));
    // 仅主详情/错误与重试入口在本轮范围；重型预览及编辑面板不冒充真实验收。
    builder.onResolve({filter:/(?:ZipReadmePanel|CodePreview|PointCloudPreviewPanel|DatasetPreviewPanel|MultimodalPreviewPanel|MultimodalWorkspacePanel|MultimodalImportBanner)$/},()=>({path:'child',namespace:'qa'}));
    builder.onResolve({filter:/^@\//},args=>builder.resolve(path.resolve(root,'src',args.path.slice(2)),{resolveDir:root,kind:args.kind}));
    builder.onLoad({filter:/^platform$/,namespace:'qa'},()=>({contents:platform,loader:'js'}));
    builder.onLoad({filter:/^umi$/,namespace:'qa'},()=>({contents:umi,loader:'js',resolveDir:root}));
    builder.onLoad({filter:/^child$/,namespace:'qa'},()=>({contents:`import React from 'react';export default function Child(p){return <output data-testid="preview-child">{p.workspaceId||p.versionId||'预览夹具'}</output>;}`,loader:'jsx',resolveDir:root}));
  }
}]});
const app=express();app.get('/',(_req,res)=>res.type('html').send('<!doctype html><html lang="zh"><meta charset="utf-8"><div id="root"></div><script src="/bundle.js"></script></html>'));
app.get('/favicon.ico',(_req,res)=>res.status(204).end());app.get('/bundle.js',(_req,res)=>res.type('js').send(result.outputFiles.find(f=>f.path.endsWith('.js')).text));
app.use((_req,res)=>res.status(405).send('No real backend'));
app.listen(18895,'127.0.0.1',()=>console.log('three-boundary harness http://127.0.0.1:18895'));
