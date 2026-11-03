import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useModel, useParams } from '@umijs/max';
import { Button, Input, Select, message } from 'antd';
import { redirectToLogin } from '@/utils/loginRedirect';
import { parseCsv, parseTep, storage, validateProject, type Item, type Mark } from './data';
import styles from './index.less';
import { expandFiles } from './archive';
import { serverJson, serverRequest } from './serverApi';

export default function ManualLabel() {
  const { initialState } = useModel('@@initialState');
  const user = initialState?.currentUser;
  const { projectId } = useParams<{ projectId?: string }>();
  const [serverPage, setServerPage] = useState(0);
  const [serverTotal, setServerTotal] = useState(0);
  const [assetLoading, setAssetLoading] = useState(false);
  const [assetError, setAssetError] = useState('');
  const key = String(user?.id || user?.username || '');
  const [items, setItems] = useState<Item[]>([]);
  const [active, setActive] = useState('');
  const [product, setProduct] = useState('其他产品');
  const [quality, setQuality] = useState('待确认');
  const [defect, setDefect] = useState('其他异常');
  const [runState, setRunState] = useState('待确认');
  const [fault, setFault] = useState('故障类型待确认');
  const [affected, setAffected] = useState<string[]>([]);
  const [note, setNote] = useState('');
  const [channel, setChannel] = useState(1);
  const [ready, setReady] = useState(false);
  const [busy, setBusy] = useState(false);
  const [importStatus, setImportStatus] = useState('');
  const [dirty, setDirty] = useState(false);
  const [status, setStatus] = useState('尚未保存');
  const [ratio, setRatio] = useState(1.5);
  const [draft, setDraft] = useState<Mark>();
  const [selectedMark, setSelectedMark] = useState<number | null>(null);
  const [loadVersion, setLoadVersion] = useState(0);
  const anchor = useRef<{ x: number; y: number } | undefined>(undefined);
  const fileInput = useRef<HTMLInputElement>(null);
  const found = items.find(i => i.id === active);
  const item = projectId && !found?.loaded ? undefined : found;
  const channels = affected.length ? affected : item?.columns?.[channel] ? [item.columns[channel]] : [];
  const label = item?.kind === 'image'
    ? [product, quality, quality === '异常' ? defect : ''].filter(Boolean).join(' · ')
    : [runState, runState === '异常' ? fault : '', channels.join('、')].filter(Boolean).join(' · ');
  useEffect(() => {
    setChannel(1);
    setProduct('其他产品'); setQuality('待确认'); setDefect('其他异常');
    setRunState('待确认'); setFault('故障类型待确认'); setAffected([]); setNote('');
    setDraft(undefined); setSelectedMark(null); anchor.current = undefined;
  }, [active]);
  useEffect(() => {
    if (!user) { redirectToLogin({ replace: true }); return; }
    if (projectId) { void openServerPage(0); return; }
    setItems([]); setActive(''); setDirty(false);
    let cancelled = false;
    storage(key).then(p => { if (!cancelled && p) { const valid = validateProject(p); setItems(valid.items); setActive(valid.items[0]?.id || ''); setStatus('已恢复本机保存'); } }).catch(e => message.error(`读取失败：${e.message}`)).finally(() => { if (!cancelled) setReady(true); });
    return () => { cancelled = true; };
  }, [key, projectId]);
  useEffect(() => {
    const warn = (e: BeforeUnloadEvent): void => { if (dirty) { e.preventDefault(); e.returnValue = ''; } };
    window.addEventListener('beforeunload', warn); return () => window.removeEventListener('beforeunload', warn);
  }, [dirty]);
  async function openServerPage(page: number): Promise<void> {
    if (dirty && !window.confirm('有未保存的标注。切换页面会丢失这些修改，是否继续？')) return;
    setBusy(true);
    try {
      const result = await serverJson<{ items: Item[]; total: number; name: string }>(`/projects/${projectId}?page=${page}`);
      setLoadVersion(value => value + 1);
      setItems(result.items); setServerTotal(result.total); setServerPage(page);
      setActive(result.items[0]?.id || ''); setDirty(false); setStatus(`当前项目：${result.name}`); setReady(true);
    } catch (e) { message.error((e as Error).message); }
    finally { setBusy(false); }
  }
  useEffect(() => {
    if (!projectId || !active) return;
    let cancelled = false, objectUrl = '';
    setAssetLoading(true); setAssetError('');
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 120000);
    void (async () => {
      try {
        const response = await serverRequest(`/assets/${active}/content`, { signal: controller.signal });
        const content: Partial<Item> = found?.kind === 'image'
          ? { source: objectUrl = URL.createObjectURL(await response.blob()) }
          : await response.json();
        if (!cancelled) setItems(previous => previous.map(i => i.id === active ? { ...i, ...content, loaded: true } : { ...i, source: undefined, rows: undefined, columns: undefined, loaded: false }));
      } catch (e) { if (!cancelled) setAssetError(`读取文件失败：${(e as Error).message}`); }
      finally { clearTimeout(timer); if (!cancelled) setAssetLoading(false); }
    })();
    return () => { cancelled = true; controller.abort(); clearTimeout(timer); if (objectUrl) URL.revokeObjectURL(objectUrl); };
  }, [projectId, active, loadVersion]);
  function update(next: Item[]): void { setItems(next); setDirty(true); setStatus('有未保存的修改'); }
  function patch(p: Partial<Item>): void { if (item) update(items.map(i => i.id === item.id ? { ...i, ...p } : i)); }
  function selectMark(index: number): void {
    if (!item?.marks[index]) return;
    const mark = item.marks[index], d = mark.details || {};
    setSelectedMark(index); setProduct(d.product || '其他产品');
    setQuality(d.quality_status || (mark.y !== undefined ? '异常' : '待确认'));
    setDefect(d.defect_type || '其他异常'); setRunState(d.state || '待确认');
    setFault(d.fault_type || '故障类型待确认');
    setAffected(d.affected_channels ? d.affected_channels.split(';') : []);
    setNote(d.note || (!mark.details ? `原标签：${mark.label}` : ''));
  }
  function labelDetails(): Record<string, string> {
    return item?.kind === 'image'
      ? { product, quality_status: quality, defect_type: quality === '异常' ? defect : '', note }
      : { state: runState, fault_type: runState === '异常' ? fault : '', affected_channels: channels.join(';'), note };
  }
  function applyLabel(): void {
    if (!item || selectedMark === null) return;
    if (item.kind === 'image' && item.marks[selectedMark].y !== undefined && quality === '正常') { message.warning('缺陷框不能标为正常，请选择异常或待确认'); return; }
    patch({ marks: item.marks.map((mark, index) => index === selectedMark ? { ...mark, label, details: labelDetails() } : mark), done: false });
    message.success('已更新所选标注，请点击顶部保存持久化');
  }
  function addMark(mark: Mark): void {
    if (!item) return;
    if (item.kind === 'image' && quality === '正常' && mark.y !== undefined) { message.warning('正常图片请添加整图分类，不需要缺陷框'); return; }
    patch({ marks: [...item.marks, { ...mark, label, details: labelDetails() }], done: false });
    setSelectedMark(item.marks.length);
  }
  async function importFiles(files: File[]): Promise<void> {
    if (!files.length) return;
    setBusy(true);
    setImportStatus('正在准备导入…');
    await new Promise(resolve => setTimeout(resolve, 0));
    try {
      if (files.reduce((sum, f) => sum + f.size, 0) > 100 * 1024 * 1024) throw new Error('每次导入总大小不能超过 100 MB');
      files = await expandFiles(files);
      if (files.some(f => /\.json$/i.test(f.name)) && files.length !== 1) throw new Error('项目 JSON 请单独导入，不要与图片或时序文件混合');
      if (files.length === 1 && files[0].name.toLowerCase().endsWith('.json')) {
        const p = validateProject(JSON.parse(await files[0].text()));
        if (items.length && !window.confirm('恢复项目将替换当前工作区，未保存的修改会丢失。是否继续？')) return;
        update(p.items); setActive(p.items[0]?.id || ''); setChannel(1); return;
      }
      if (items.length + files.length > 500) throw new Error('演示版最多支持 500 个文件');
      const imported: Item[] = [];
      for (const [index, f] of files.entries()) {
        setImportStatus(`正在导入 ${index + 1}/${files.length}：${f.name}`);
        await new Promise(resolve => setTimeout(resolve, 0));
        if (f.size > 20 * 1024 * 1024) throw new Error(`${f.name} 超过 20 MB`);
        const base = { id: `${Date.now()}-${Math.random().toString(36).slice(2)}`, name: f.name, marks: [], done: false };
        if (/\.(png|jpe?g|webp)$/i.test(f.name)) {
          const source = await new Promise<string>((resolve, reject) => { const r = new FileReader(); r.onload = () => resolve(String(r.result)); r.onerror = () => reject(new Error('图片读取失败')); r.readAsDataURL(f); });
          await new Promise<void>((resolve, reject) => { const img = new Image(); const timer = window.setTimeout(() => { img.onload = null; img.onerror = null; img.src = ''; reject(new Error(`${f.name} 读取超时，请重新选择图片`)); }, 15000); img.onload = () => { clearTimeout(timer); resolve(); }; img.onerror = () => { clearTimeout(timer); reject(new Error(`${f.name} 无法解码`)); }; img.src = source; });
          imported.push({ ...base, kind: 'image', source });
        } else if (/\.csv$/i.test(f.name)) imported.push({ ...base, kind: 'series', ...parseCsv(await f.text()) });
        else if (/\.dat$/i.test(f.name)) imported.push({ ...base, kind: 'series', ...parseTep(await f.text()) });
        else throw new Error(`暂不支持 ${f.name}，请选择图片、CSV、TEP DAT、ZIP 或项目 JSON`);
      }
      update([...items, ...imported]); setActive(imported[0]?.id || active); setChannel(1); message.success(`已导入 ${imported.length} 个文件`);
    } catch (e) { message.error((e as Error).message); } finally { setBusy(false); setImportStatus(''); if (fileInput.current) fileInput.current.value = ''; }
  }
  async function save(): Promise<void> {
    setBusy(true);
    try {
      if (projectId) {
        // 逐条保存并更新版本号；中途失败时已经保存的条目仍可继续重试。
        for (const entry of items) {
          const result = await serverJson<{ revision: number }>(`/assets/${entry.id}/annotation`, { method: 'PUT', body: JSON.stringify({ marks: entry.marks, done: entry.done, revision: entry.revision }) });
          setItems(previous => previous.map(i => i.id === entry.id ? { ...i, revision: result.revision } : i));
        }
        setDirty(false); setStatus('标注已保存到当前项目'); message.success('已保存到当前项目'); return;
      }
      await storage(key, { version: 1, items }); setDirty(false); setStatus(`已保存到本机 · ${new Date().toLocaleTimeString()}`); message.success('已保存到当前浏览器'); }
    catch (e) { message.error(`保存失败：${(e as Error).message}。当前修改仍保留在页面中，请勿关闭。`); } finally { setBusy(false); }
  }
  async function exportProject(): Promise<void> {
    if (projectId) {
      if (dirty) { message.warning('请先保存标注，再导出项目结果'); return; }
      try {
        const response = await serverRequest(`/projects/${projectId}/export`);
        const result = await response.json() as { project_name: string; source_filename: string; file_count: number; annotated_file_count: number };
        const safePart = (value: string): string => Array.from(value.replace(/[\u0000-\u001f<>:"/\\|?*]/g, '-').replace(/[. ]+$/g, '').trim()).slice(0, 60).join('') || '项目';
        const projectName = safePart(result.project_name);
        const sourceName = safePart(result.source_filename.replace(/\.[^.]+$/, ''));
        const name = sourceName.toLowerCase() === projectName.toLowerCase() ? projectName : `${projectName}-${sourceName}`;
        const now = new Date();
        const date = `${now.getFullYear()}${String(now.getMonth() + 1).padStart(2, '0')}${String(now.getDate()).padStart(2, '0')}`;
        const url = URL.createObjectURL(new Blob([JSON.stringify(result)], { type: 'application/json' }));
        const a = document.createElement('a'); a.href = url; a.download = `${name}-标注结果-${date}.json`; a.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
        message.success(`已导出项目全部 ${result.file_count} 个文件，其中 ${result.annotated_file_count} 个文件含标注`);
      } catch (e) { message.error((e as Error).message); }
      return;
    }
    const url = URL.createObjectURL(new Blob([JSON.stringify({ version: 1, items })], { type: 'application/json' }));
    const a = document.createElement('a'); a.href = url; a.download = `数据标注项目-${new Date().toISOString().slice(0, 10)}.json`; a.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
  function point(e: React.PointerEvent<SVGSVGElement>): { x: number; y: number } {
    const r = e.currentTarget.getBoundingClientRect();
    return { x: Math.max(0, Math.min(1000, (e.clientX - r.left) / r.width * 1000)), y: Math.max(0, Math.min(1000, (e.clientY - r.top) / r.height * 1000)) };
  }
  function drag(e: React.PointerEvent<SVGSVGElement>): Mark | undefined {
    if (!anchor.current || !item) return;
    const p = point(e), a = anchor.current;
    if (item.kind === 'image') return { label, start: Math.min(a.x, p.x), end: Math.max(a.x, p.x), y: Math.min(a.y, p.y), height: Math.abs(a.y - p.y) };
    const max = item.rows!.length - 1;
    return { label, start: Math.round(Math.min(a.x, p.x) / 1000 * max), end: Math.round(Math.max(a.x, p.x) / 1000 * max) };
  }
  const { values, min, max, paths } = useMemo(() => {
    const values = item?.kind === 'series' ? item.rows!.map(r => r[channel]?.trim() ? Number(r[channel]) : NaN) : [];
  const finite = values.filter(Number.isFinite);
  const min = finite.length ? Math.min(...finite) : 0, max = finite.length ? Math.max(...finite) : 1;
  const paths: string[] = []; let path = '';
  values.forEach((v, i) => { if (!Number.isFinite(v)) { if (path) paths.push(path); path = ''; } else path += `${path ? 'L' : 'M'}${i / Math.max(1, values.length - 1) * 1000},${900 - (v - min) / (max - min || 1) * 800} `; });
  if (path) paths.push(path);
    return { values, min, max, paths };
  }, [item?.rows, item?.kind, channel]);
  if (!user) return null;
  return <main className={styles.page}>
    <header className={styles.header}>
      <div className={styles.identity}>
        <div className={styles.university}>
          <img className={styles.universityEmblem} src="/seu-emblem-navy.png" alt="东南大学校徽" />
          <div className={styles.universityName}><strong>东南大学</strong><span>SOUTHEAST UNIVERSITY</span></div>
        </div>
        <div className={styles.platformTitle}><h1>数据标注平台</h1></div>
      </div>
      <div className={styles.headerTools}>
        <Link className={styles.backLink} to="/platforms" onClick={(e: React.MouseEvent<HTMLAnchorElement>) => { if (dirty && !window.confirm('有未保存的修改，仍要离开吗？')) e.preventDefault(); }}>← 返回平台选择</Link>
        <div className={styles.actions}>
          <Link to="/label-projects" onClick={(e: React.MouseEvent<HTMLAnchorElement>) => { if (dirty && !window.confirm('有未保存的修改，仍要离开吗？')) e.preventDefault(); }}><Button>项目管理</Button></Link>
          {!projectId && <Button disabled={!ready || busy} onClick={() => fileInput.current?.click()}>本机导入 / 恢复项目</Button>}
          <Button disabled={!items.length || busy} onClick={exportProject}>{projectId ? '导出标注' : '导出项目'}</Button>
          <Button type="primary" loading={busy} disabled={!ready || !items.length} onClick={save}>保存</Button>
        </div>
      </div>
    </header>
    <input hidden ref={fileInput} type="file" multiple accept=".png,.jpg,.jpeg,.webp,.csv,.dat,.json,.zip" onChange={e => void importFiles(Array.from(e.target.files || []))} />
    <div className={styles.notice}>{!projectId && '本机工作区'}<span role="status" aria-live="polite">{importStatus || status}</span></div>
    <div className={styles.workspace} aria-busy={busy} style={busy ? { pointerEvents: 'none', opacity: 0.6 } : undefined}>
      <aside className={styles.sidebar}><h2>数据文件 <span>{projectId ? serverTotal : items.length}</span></h2>{projectId && <div><Button size="small" disabled={busy || serverPage === 0} onClick={() => void openServerPage(serverPage - 1)}>上一页</Button><span> {serverPage + 1} / {Math.max(1, Math.ceil(serverTotal / 50))} </span><Button size="small" disabled={busy || (serverPage + 1) * 50 >= serverTotal} onClick={() => void openServerPage(serverPage + 1)}>下一页</Button></div>}<p>{items.filter(i => i.done).length} 个已完成</p><div className={styles.files}>{items.map(i => <button key={i.id} className={i.id === active ? styles.selected : ''} onClick={() => { setActive(i.id); setChannel(1); setDraft(undefined); anchor.current = undefined; }}><small>{i.kind === 'image' ? '图像' : '时序'} · {i.done ? '已完成' : '待标注'}</small><strong>{i.name}</strong><span>{i.marks.length} 条标注</span></button>)}</div></aside>
      <section className={styles.center}>{projectId && !item ? <div className={styles.empty}><h2>{assetLoading ? '正在读取当前文件…' : '项目数据'}</h2><p role="status">{assetError || '从左侧选择文件开始标注'}</p>{assetError && <Button onClick={() => void openServerPage(serverPage)}>重新读取本页</Button>}</div> : !item ? <div className={styles.empty}><h2>暂无数据</h2><Button type="primary" disabled={!ready || busy} onClick={() => fileInput.current?.click()}>上传第一份数据</Button><p>PNG / JPG / WebP / CSV / DAT / ZIP</p><p>单数据文件 ≤ 20 MB；本机 ZIP ≤ 100 MB。大文件或嵌套归档请使用顶部“项目管理”。</p></div> : <><div className={styles.toolbar}><div><h2>{item.name}</h2><p>{item.kind === 'image' ? '拖动画框后，点击框或右侧标注记录，选择标签并点击“应用到所选标注”。' : '选择右侧状态、故障和涉及通道后，拖动选择区间。'}</p></div>{item.kind === 'series' && <Select aria-label="选择时序通道" value={channel} onChange={setChannel} options={item.columns!.slice(1).map((c, i) => ({ value: i + 1, label: c }))} />}</div>
      <div className={styles.canvas}>
        {item.kind === 'image' && <img hidden src={item.source} alt="" onLoad={e => setRatio(e.currentTarget.naturalWidth / e.currentTarget.naturalHeight)} />}
        <svg role="img" aria-label={item.kind === 'image' ? '拖动添加矩形标注' : '拖动添加时序区间标注'} viewBox="0 0 1000 1000" preserveAspectRatio="none" style={{ aspectRatio: item.kind === 'image' ? ratio : 2.2, touchAction: 'none' }} onPointerDown={e => { if (!label.trim()) { message.warning('请先填写右侧标签'); return; } anchor.current = point(e); e.currentTarget.setPointerCapture(e.pointerId); }} onPointerMove={e => setDraft(drag(e))} onPointerCancel={() => { anchor.current = undefined; setDraft(undefined); }} onPointerUp={e => { const m = drag(e); if (m && (item.kind === 'series' || (m.end - m.start > 3 && m.height! > 3))) addMark(m); anchor.current = undefined; setDraft(undefined); }}>
          {item.kind === 'image' ? <image href={item.source} width="1000" height="1000" preserveAspectRatio="none" /> : <><rect width="1000" height="1000" fill="#f8fbff" />{[100, 300, 500, 700, 900].map(y => <line key={y} x1="0" x2="1000" y1={y} y2={y} stroke="#dce5f0" />)}{paths.map((d, i) => <path key={i} d={d} stroke="#2878dc" fill="none" strokeWidth="2" vectorEffect="non-scaling-stroke" />)}</>}
          {[...item.marks, ...(draft ? [draft] : [])].map((m, i) => {
            if (item.kind === 'image' && m.y === undefined) return null;
            const x = item.kind === 'image' ? m.start : m.start / Math.max(1, values.length - 1) * 1000;
            const width = item.kind === 'image' ? m.end - m.start : (m.end - m.start) / Math.max(1, values.length - 1) * 1000;
            return <rect onPointerDown={e => { if (i < item.marks.length) { e.stopPropagation(); selectMark(i); } }} style={{ cursor: i < item.marks.length ? 'pointer' : 'crosshair' }} key={i} x={x} y={m.y || 0} width={Math.max(width, 2)} height={m.height || 1000} stroke={selectedMark === i ? '#2878dc' : '#e38b23'} fill={selectedMark === i ? '#2878dc30' : '#e38b2328'} strokeWidth={selectedMark === i ? 4 : 2} vectorEffect="non-scaling-stroke"><title>{m.label}</title></rect>;
          })}
        </svg>
        {item.kind === 'series' && <><div className={styles.axis}><span>{item.rows![0][0]}</span><span>{item.rows!.at(-1)![0]}</span></div><p>数值范围：{min} ～ {max} · {values.length} 个采样点 · 横轴按采样顺序排列</p></>}
      </div></>}</section>
      <aside className={styles.inspector}><h2>{selectedMark === null ? '新建标注' : `编辑标注 ${selectedMark + 1}`}</h2>
      {selectedMark !== null && <Button block onClick={() => setSelectedMark(null)}>结束编辑 / 新建标注</Button>}
      {item?.kind === 'image' && <>
        <label htmlFor="product">产品类别</label><Select id="product" value={product} onChange={setProduct} options={['PCB 电路板', '蜡烛', '腰果', '其他产品'].map(value => ({ value, label: value }))} />
        <label htmlFor="quality">图像状态</label><Select id="quality" value={quality} onChange={setQuality} options={['正常', '异常', '待确认'].map(value => ({ value, label: value }))} />
        {quality === '异常' && <><label htmlFor="defect">缺陷类型（人工判断）</label><Select id="defect" value={defect} onChange={setDefect} options={['缺损', '表面斑点', '异物/附着物', '划痕', '其他异常'].map(value => ({ value, label: value }))} /></>}
        <p>正常图片添加整图分类，异常区域画框标注。</p>
      </>}
      {item?.kind === 'series' && <>
        <label htmlFor="run-state">区间状态</label><Select id="run-state" value={runState} onChange={setRunState} options={['正常', '异常', '待确认'].map(value => ({ value, label: value }))} />
        {runState === '异常' && <><label htmlFor="fault">故障类型</label><Select id="fault" value={fault} onChange={setFault} options={['故障类型待确认', 'IDV01：进料 A/C 比例阶跃变化', 'IDV04：反应器冷却水入口温度阶跃变化', 'IDV11：反应器冷却水入口温度随机变化', 'IDV14：反应器冷却水阀门卡滞', 'IDV18：未知故障', '其他故障'].map(value => ({ value, label: value }))} /></>}
        <label htmlFor="channels">涉及通道（可多选）</label><Select id="channels" mode="multiple" value={affected} onChange={setAffected} placeholder={`默认当前通道 ${item.columns?.[channel] || ''}`} options={item.columns!.slice(1).map(value => ({ value, label: value }))} />
        <p>未选择通道时，记录当前通道。</p>
      </>}
      {item && <><label htmlFor="annotation-note">备注（选填）</label><Input id="annotation-note" value={note} maxLength={200} placeholder="例如：右下方凹坑、流量明显抬升" onChange={e => setNote(e.target.value)} /><div className={styles.labelPreview}><small>{selectedMark === null ? '将添加的标签' : '所选标注的新标签'}</small><strong>{label}</strong></div></>}
      {item && selectedMark !== null && <Button type="primary" block onClick={applyLabel}>应用到所选标注</Button>}
      {item?.kind === 'image' && <Button block onClick={() => addMark({ label, start: 0, end: 1000 })}>添加整图分类</Button>}
      {item?.kind === 'series' && <p>区间端点对应原始 CSV 行。可拖动框选，或输入采样点编号（从 0 开始）。</p>}
      {item?.kind === 'series' && <form key={item.id} className={styles.range} onSubmit={e => { e.preventDefault(); const f = new FormData(e.currentTarget); const start = Number(f.get('start')), end = Number(f.get('end')); if (end < start) { message.warning('结束点不能早于起点'); return; } addMark({ label, start, end }); }}><input aria-label="起始采样点" name="start" type="number" min="0" max={values.length - 1} required placeholder="起点" /><input aria-label="结束采样点" name="end" type="number" min="0" max={values.length - 1} required placeholder="终点" /><Button htmlType="submit">添加区间</Button></form>}
      <div className={styles.marks}>{item?.marks.map((m, i) => <div key={i} className={selectedMark === i ? styles.selectedMark : undefined}><button className={styles.selectMark} onClick={() => selectMark(i)} aria-pressed={selectedMark === i}><strong>{i + 1}. {m.label}</strong><span>点击编辑标签</span></button>{m.details?.note && <p>备注：{m.details.note}</p>}<p>{item.kind === 'series' ? `${item.rows![m.start][0]} → ${item.rows![m.end][0]}` : m.y === undefined ? '整图分类' : `矩形框 ${i + 1}`}</p><button onClick={() => { patch({ marks: item.marks.filter((_, n) => n !== i), done: false }); setSelectedMark(null); }}>移除此标注</button></div>)}</div>
      {item && <Button block type={item.done ? 'default' : 'primary'} onClick={() => patch({ done: !item.done })}>{item.done ? '重新标注' : '标记完成'}</Button>}
      {!item?.marks.length && <p>尚无标注。选择上方标签后开始标注。</p>}</aside>
    </div>
    <footer className={styles.dataSource}><a href="/demo-data/SOURCES.txt" target="_blank" rel="noreferrer">数据来源、许可与引用</a></footer>
  </main>;
}
