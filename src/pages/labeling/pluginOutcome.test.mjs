import assert from 'node:assert/strict';
import test from 'node:test';
import dayjs from 'dayjs';
import { renderHooks, flushHooks } from '../../../scripts/qa/render-hook-component.mjs';

test('historical plugin results use plugin formats, jobs and export API instead of the native converter', async () => {
  const calls=[];
  const version={specVersionId:'frozen',versionNo:1,schemaVersion:'annotation-record-v1',labels:[],editorConfig:{}};
  const props={ project:{projectId:'project',taskType:'TIMESERIES_ANNOTATION',status:'OPEN',specVersions:[version]}, version, onVersionChange(){}, reviews:[],snapshots:[{finalSnapshotId:'snapshot',specVersionId:'frozen',name:'确认成果',createdAt:'2026-11-19T00:00:00Z'}],run:async action=>action(),refresh(){},busy:false };
  const antd=Object.fromEntries(['Alert','Button','Card','Input','Modal','Select','Space','Table','Tag'].map(name=>[name,name]));
  antd.Typography={Paragraph:'Paragraph'};
  const r=renderHooks(new URL('./NativeOutcome.tsx',import.meta.url),props,{
    antd,dayjs:{default:dayjs},'./OutcomeDetails':{default:'OutcomeDetails'},'./outcome.less':{default:{}},
    '@/services/labeling':{describeLabelingError:e=>e.message,operationKey:()=> 'key',labelingJson:async path=>{calls.push(path);return {formats:[{name:'energy-jsonl-zip',enabled:true,title:'能耗 JSONL'}]};},post:async(path,body)=>{calls.push({path,body});return {}; }},
    '@/services/nativeLabeling':{collectPages:async path=>{calls.push(path);return [{exportId:'job',status:'SUCCEEDED',format:'energy-jsonl-zip'}];}},
  });
  r.render();await flushHooks();r.render();
  assert.ok(calls.includes('/final-snapshots/snapshot/plugin-export-formats'));
  assert.ok(calls.includes('/projects/project/exports?snapshotId=snapshot'));
  assert.equal(r.nodes('Select').find(node=>node.props['aria-label']==='导出格式').props.value,'energy-jsonl-zip');
  const specPicker=r.nodes('Select').find(node=>node.props['aria-label']==='选择标注规范版本');
  assert.equal(specPicker.props.value,'frozen');
  assert.match(specPicker.props.options[0].label,/第 1 版/);
  const snapshots=r.nodes('Table').find(node=>node.props.rowKey==='finalSnapshotId');
  assert.match(snapshots.props.columns[0].render(null,props.snapshots[0]),/^成果版本 · /);
  const jobs=r.nodes('Table').find(node=>node.props.rowKey==='exportId');
  assert.equal(jobs.props.dataSource[0].status,'READY');
  const generate=r.nodes('Button').find(node=>node.props.children.includes('生成导出文件'));
  assert.equal(generate.props.disabled,false);
  generate.props.onClick();await flushHooks();
  const created=calls.find(call=>call.path==='/projects/project/exports');
  assert.equal(created.body.format,'energy-jsonl-zip');
  assert.equal(created.body.finalSnapshotIds[0],'snapshot');
  assert.equal(created.body.includeOriginals,false);
  r.dispose();
});
