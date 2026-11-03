/**
 * 本地故障注入夹具：使用真实 React 页面和服务，只替换 Umi 网络/路由上下文。
 * node scripts/qa/read-errors-harness.mjs → http://127.0.0.1:18893
 * 绝不连接后端，所有写接口直接拒绝；不是线上业务验收或成功样例。
 * __qa 控制延迟/错误，用于验证刷新、恢复和迟到响应，不能编入生产入口。
 */
import { build } from 'esbuild';
import express from 'express';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../../', import.meta.url));
const result = await build({
  absWorkingDir: root,
  stdin: { contents: `
    import React from 'react';
    import '@ant-design/v5-patch-for-react-19';
    import { createRoot } from 'react-dom/client';
    import Metrics from './src/components/TrainingMetricsPanel';
    import CodeList from './src/pages/task/trainingCode/list';
    import TaskCompare from './src/pages/task/compare';
    import PendingCodes from './src/pages/task/trainingCode/pending';
    import AdminAssets from './src/pages/task/trainingCode/adminAssets';
    window.__qa = {
      metrics: 'success', code: 'success', calls: [], held: [],
      compare: {}, detailCalls: [],
      adminMode: 'success', adminCalls: [], navigation: [],
      assetMode: 'success', assetCalls: [],
      release() { this.held.splice(0).forEach(resolve => resolve()); },
    };
    function Harness() {
      const [runId, setRunId] = React.useState('run-a');
      const [input, setInput] = React.useState('');
      return <main style={{padding: 24}}>
        <h1>本地故障注入（无真实后端连接）</h1>
        <button onClick={() => setRunId('run-a')}>测试切换 A</button>
        <button onClick={() => setRunId('run-b')}>测试切换 B</button>
        <button onClick={() => setRunId(undefined)}>测试手动 Run ID</button>
        <section id="metrics"><Metrics runId={runId} taskStatus="running"
          runIdInput={input} onRunIdInputChange={setInput} onManualRunId={setRunId}/></section>
        <section id="code-list"><CodeList/></section>
      </main>;
    }
    function AssetHarness() {
      const [visible, setVisible] = React.useState(true);
      return <main style={{padding:24}}><h1>本地资产详情测试（无真实后端连接）</h1>
        <button onClick={() => setVisible(!visible)}>{visible ? '卸载测试页' : '挂载测试页'}</button>
        {visible && <AdminAssets/>}
      </main>;
    }
    const view = new URLSearchParams(location.search).get('view');
    createRoot(document.getElementById('root')).render(<React.StrictMode>{view === 'compare'
      ? <main style={{padding:24}}><h1>本地对比测试（无真实后端连接）</h1><TaskCompare/></main>
      : view === 'admin'
        ? <main style={{padding:24}}><h1>本地管理员队列测试（无真实后端连接）</h1><PendingCodes/></main>
        : view === 'assets' ? <AssetHarness/> : <Harness/>}</React.StrictMode>);
  `, resolveDir: root, loader: 'jsx' },
  bundle: true, write: false, format: 'iife', platform: 'browser',
  define: { 'process.env.NODE_ENV': '"development"', 'process.env.REACT_APP_MLFLOW_BASE_PATH': '"/qa-metrics"' },
  plugins: [{
    name: 'isolated-qa-context',
    setup(builder) {
      builder.onResolve({ filter: /^@umijs\/max$/ }, () => ({ path: 'umi', namespace: 'qa' }));
      builder.onResolve({ filter: /^@\/services\/platform$/ }, () => ({ path: 'platform', namespace: 'qa' }));
      builder.onResolve({ filter: /^@\// }, args => builder.resolve(path.resolve(root, 'src', args.path.slice(2)), { resolveDir: root, kind: args.kind }));
      builder.onLoad({ filter: /^platform$/, namespace: 'qa' }, () => ({
        contents: `export * from './src/services/code'; export * from './src/services/codeV2'; export * from './src/services/mlflow';
          export { fetchTaskDetail, fetchTaskList, listExperimentVersions, CONSISTENCY_TRAINING_PROFILE } from './src/services/task';`, resolveDir: root,
      }));
      builder.onLoad({ filter: /^umi$/, namespace: 'qa' }, () => ({
        contents: `
          const search = new URLSearchParams(location.search);
          export const useSearchParams = () => [search];
          export const useAccess = () => ({isAdmin: ['admin','assets'].includes(search.get('view')) && search.get('role') !== 'user'});
          export const history = {
            push: () => {throw new Error('夹具禁止业务导航');},
            replace: path => { window.__qa.navigation.push(path); },
          };
          export async function request(url, options = {}) {
            if (options.method && options.method !== 'GET') throw new Error('夹具禁止写接口');
            const qa = window.__qa;
            qa.calls.push(url);
            if (url === '/system/user/list') return {code:200, data:{list:[{id:1,username:'qa-owner',role:'普通用户',status:'启用'}],total:1}};
            if (search.get('view') === 'assets' && url.startsWith('/v2/admin/')) {
              qa.assetCalls.push({url, method:options.method, params: options.params});
              const assets = ['a','b'].map(id => ({id:'asset-' + id,name:'测试资产 ' + id.toUpperCase(),ownerUserId:1,assetRevision:2,trainingProfile:'cv'}));
              if (url === '/v2/admin/code-assets') {
                if (qa.assetMode === 'list-error') throw new Error('测试资产列表失败');
                if (qa.assetMode === 'list-invalid') return {errorCode:'DENIED',items:[]};
                return {items:assets,totalElements:2};
              }
              const match = url.match(new RegExp('^/v2/admin/code-assets/(asset-[ab])(/versions)?$'));
              const mode = qa.assetMode;
              if (match) {
                if (mode === 'held') await new Promise(resolve => qa.held.push(resolve));
                if (match[2]) {
                  if (mode === 'versions-error') throw new Error('测试版本列表不可用');
                  if (mode === 'versions-invalid') return {code:403,items:[]};
                  if (mode === 'empty') return [];
                  return [1,2].map(n => ({id:'v' + n + '-' + match[1],codeAssetId:match[1],versionLabel:'v' + n,trainingProfile:'cv',status:'READY',approvalStatus:'APPROVED',publishedAt:'2026-09-0' + n + 'T00:00:00Z'}));
                }
                if (mode === 'detail-error') throw new Error('测试资产详情不可用');
                if (mode === 'detail-invalid') return {success:false,id:match[1]};
                return assets.find(item => item.id === match[1]);
              }
              if (url.endsWith('/tree')) {
                if (mode === 'tree-error') throw new Error('测试目录读取失败');
                return [{path:'train.py',type:'FILE'}, {path:'README.md',type:'FILE'}];
              }
              if (url.endsWith('/files/content')) {
                if (mode === 'file-error') throw new Error('测试文件读取失败');
                if (mode === 'file-held') await new Promise(resolve => qa.held.push(resolve));
                return {content:'只读文件 ' + options.params.path + ' · ' + url.split('/')[4]};
              }
              throw new Error('资产夹具未声明接口: ' + url);
            }
            if (url === '/v2/admin/code-review-tasks') {
              const mode = qa.adminMode;
              const params = options.params;
              qa.adminCalls.push({url, params});
              if (mode === 'primary-error') throw new Error('测试审核队列不可用');
              if (mode === 'primary-invalid') return {success:false, items:[]};
              if (mode === 'held') await new Promise(resolve => qa.held.push(resolve));
              let items = ['success', 'held'].includes(mode) ? Array.from({length:13}, (_, index) => ({
                versionId:'review-' + index, assetId:'review-asset-' + index, codeName:'待审代码 ' + index,
                fileName:'train-' + index + '.py', trainingProfile:'cv', approvalStatus:'PENDING',
                riskLevel:'LOW', riskStatus:'COMPLETED', validationStatus:'PASSED', ownerUserId:1,
                submittedAt:'2026-09-09T00:00:00Z',
              })) : [];
              items = items.filter(item => (!params.keyword || item.codeName.includes(params.keyword) || item.fileName.includes(params.keyword))
                && (!params.approvalStatus || item.approvalStatus === params.approvalStatus)
                && (!params.riskLevel || item.riskLevel === params.riskLevel));
              return {items:items.slice(params.page * params.pageSize, (params.page + 1) * params.pageSize), totalElements:items.length};
            }
            if (url === '/v2/admin/code-assets') {
              qa.adminCalls.push({url, params:options.params});
              if (qa.adminMode === 'fallback-error') throw new Error('测试资产补查不可用');
              const items = ['partial', 'versions-error', 'truncated'].includes(qa.adminMode)
                ? [{id:'extra-a',name:'补查代码 A'}, {id:'extra-b',name:'补查代码 B'}] : [];
              return {items, totalElements:qa.adminMode === 'truncated' ? 51 : items.length};
            }
            const adminVersions = url.match(new RegExp('^/v2/admin/code-assets/(extra-[ab])/versions$'));
            if (adminVersions) {
              qa.adminCalls.push({url});
              if (qa.adminMode === 'versions-error' || (qa.adminMode === 'partial' && adminVersions[1] === 'extra-b')) throw new Error('测试补查版本不可用');
              return [{id:'version-' + adminVersions[1], fileName:'train-extra.py', trainingProfile:'cv', approvalStatus:'PENDING',
                riskLevel:'LOW', riskStatus:'COMPLETED', validationStatus:'PASSED'}];
            }
            const tasks = ['a', 'b', 'c'].map(id => ({id, name:'任务 ' + id.toUpperCase(),
              modelId:'model', datasetId:'dataset', modelName:'测试模型', datasetName:'测试数据集',
              status:'success', createTime:'2026-09-09T00:00:00Z'}));
            if (url === '/task/list') return {success:true, data:{data:tasks, total:tasks.length}};
            if (url === '/task/detail') {
              const id = options.params.id;
              qa.detailCalls.push(id);
              const mode = qa.compare[id];
              if (mode === 'error') throw new Error('测试任务详情读取失败');
              if (mode === 'held') await new Promise(resolve => qa.held.push(resolve));
              if (mode === 'invalid') return {success:true, data:{}};
              if (mode === 'business-error') return {success:false, data:null};
              return {success:true, data:{...tasks.find(t => t.id === id),
                runId: mode === 'no-run' ? undefined : id}};
            }
            if (url.startsWith('/qa-metrics')) {
              const mode = qa.metrics;
              const parsed = new URL(url, location.origin);
              const run = parsed.searchParams.get('run_id');
              if (search.get('view') === 'compare') {
                if (qa.compare[run] === 'metrics-error') throw new Error('测试对比指标读取失败');
                if (qa.compare[run] === 'empty') return {metrics:[]};
                const key = parsed.searchParams.get('metric_key');
                if (key === 'train_loss') return {metrics:[{step:0,value:2},{step:1,value:run === 'a' ? 1 : 0.5}]};
                if (key === 'val_accuracy') return {metrics:[{step:0,value:run === 'a' ? 0.8 : 0.9}]};
                return {metrics:[]};
              }
              if (mode === 'held') await new Promise(resolve => qa.held.push(resolve));
              // 故意允许已取消的迟到响应，检验页面防串数据，而不只依赖网络取消。
              if (mode === 'error') throw new Error('测试指标服务不可用');
              return {metrics: mode === 'empty' || parsed.searchParams.get('metric_key') !== 'train_loss' ? [] : [
                {step: 0, value: run === 'run-b' ? 20 : 2}, {step: 1, value: run === 'run-b' ? 10 : 1}
              ]};
            }
            if (url === '/v2/code-assets') {
              if (qa.code === 'error') throw new Error('测试代码接口不可用');
              if (qa.code === 'many' || qa.code === 'many-partial') return Array.from({length: 23}, (_, index) => ({
                id: 'page-' + String(index).padStart(2, '0'),
                name: (index < 12 ? 'MiniRBT ' : 'YOLO ') + String(index).padStart(2, '0'),
              }));
              return qa.code === 'empty' ? [] : [{id:'a',name:'代码 A'}, {id:'b',name:'代码 B'}];
            }
            const paged = url.match(new RegExp('^/v2/code-assets/(page-[0-9]{2})/versions$'));
            if (paged) {
              if (qa.code === 'many-partial' && paged[1] === 'page-11') throw new Error('测试部分版本失败');
              return [{id:'version-' + paged[1], codeAssetId:paged[1], version:1,
                fileName: 'train-' + paged[1] + '.zip', createdAt: '2026-09-' + String(Number(paged[1].slice(5)) + 1).padStart(2, '0'),
                trainingProfile:'cv', approvalStatus:'APPROVED', status:'READY', validationStatus:'VALID', riskLevel:'LOW'}];
            }
            const match = url.match(new RegExp('^/v2/code-assets/([ab])/versions$'));
            if (match) {
              if (qa.code === 'partial' && match[1] === 'b') throw new Error('测试部分版本失败');
              return [{id:'version-' + match[1], codeAssetId:match[1], version:1, fileName:'train.py',
                trainingProfile:'cv', approvalStatus:'APPROVED', status:'READY', validationStatus:'VALID', riskLevel:'LOW'}];
            }
            throw new Error('夹具未声明的只读接口: ' + url);
          }
        `,
        resolveDir: root,
      }));
    },
  }],
});
const app = express();
app.get('/favicon.ico', (_req, res) => res.status(204).end());
app.get('/app.js', (_req, res) => res.type('js').send(result.outputFiles[0].text));
app.get('/', (_req, res) => res.type('html').send('<!doctype html><meta charset="utf-8"><title>读取异常本地测试</title><div id="root"></div><script src="/app.js"></script>'));
app.listen(18893, '127.0.0.1', () => console.log('QA fixture: http://127.0.0.1:18893 (no backend)'));
