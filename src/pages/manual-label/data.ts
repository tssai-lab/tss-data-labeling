export type Mark = { label: string; start: number; end: number; y?: number; height?: number; details?: Record<string, string> };
export type Item = { id: string; name: string; kind: 'image' | 'series'; source?: string; columns?: string[]; rows?: string[][]; marks: Mark[]; done: boolean; revision?: number; loaded?: boolean };
export type Project = { version: 1; items: Item[] };

export function parseCsv(text: string): { columns: string[]; rows: string[][] } {
  const rows: string[][] = [];
  let row: string[] = [], field = '', quoted = false;
  const input = text.replace(/^\uFEFF/, '').replace(/\r\n/g, '\n');
  for (let i = 0; i < input.length; i++) {
    const c = input[i];
    if (c === '"') {
      if (quoted && input[i + 1] === '"') { field += '"'; i++; }
      else quoted = !quoted;
    } else if (!quoted && (c === ',' || c === '\n')) {
      row.push(field); field = '';
      if (c === '\n') { if (row.some(v => v.trim())) rows.push(row); row = []; }
    } else field += c;
  }
  if (quoted) throw new Error('CSV 引号未闭合');
  row.push(field); if (row.some(v => v.trim())) rows.push(row);
  const columns = rows.shift()?.map(c => c.trim()) || [];
  if (columns.length < 2 || !rows.length) throw new Error('CSV 需包含表头、时间列及至少一列数值');
  if (rows.length > 50000) throw new Error('演示版单个 CSV 最多支持 50,000 行');
  if (rows.some(r => r.length !== columns.length)) throw new Error('CSV 各行列数必须与表头一致');
  if (!columns.slice(1).some((_, i) => rows.some(r => r[i + 1].trim() && Number.isFinite(Number(r[i + 1]))))) throw new Error('未找到可绘制的数值列');
  return { columns, rows };
}

export function parseTep(text: string): { columns: string[]; rows: string[][] } {
  let values = text.trim().split(/\r?\n/).filter(line => line.trim()).map(line => line.trim().split(/\s+/));
  if (values.length === 52 && values.every(row => row.length === 500)) {
    values = values[0].map((_, column) => values.map(row => row[column]));
  }
  if (!values.length || values.length > 50000 || values.some(row => row.length !== 52 || row.some(v => !Number.isFinite(Number(v))))) {
    throw new Error('TEP DAT 需为每行 52 个数值；也支持 d00 的 52×500 转置格式');
  }
  return {
    columns: ['sample_index', ...Array.from({ length: 41 }, (_, i) => `XMEAS_${i + 1}`), ...Array.from({ length: 11 }, (_, i) => `XMV_${i + 1}`)],
    rows: values.map((row, i) => [String(i), ...row]),
  };
}

export function validateProject(value: unknown): Project {
  const p = value as Project;
  if (p?.version !== 1 || !Array.isArray(p.items) || p.items.length > 500) throw new Error('不是有效的演示项目文件');
  const ids = new Set<string>();
  for (const item of p.items) {
    if (!item || typeof item.id !== 'string' || ids.has(item.id) || typeof item.name !== 'string' || typeof item.done !== 'boolean' || !Array.isArray(item.marks)) throw new Error('项目条目格式错误');
    ids.add(item.id);
    if (item.kind === 'image') {
      if (typeof item.source !== 'string' || !/^data:image\/(png|jpeg|webp);base64,/.test(item.source)) throw new Error('图片格式错误');
    } else if (item.kind === 'series') {
      if (!Array.isArray(item.columns) || item.columns.length < 2 || !item.columns.every(c => typeof c === 'string') || !Array.isArray(item.rows) || !item.rows.length || item.rows.length > 50000 || !item.rows.every(r => Array.isArray(r) && r.length === item.columns!.length && r.every(v => typeof v === 'string'))) throw new Error('时序格式错误');
    } else throw new Error('不支持的条目类型');
    for (const m of item.marks) {
      if (m.details !== undefined && (!m.details || typeof m.details !== 'object' || Array.isArray(m.details) || Object.values(m.details).some(v => typeof v !== 'string'))) throw new Error('标签属性格式错误');
      const max = item.kind === 'image' ? 1000 : item.rows!.length - 1;
      if (item.kind === 'series' && (!Number.isInteger(m.start) || !Number.isInteger(m.end))) throw new Error('采样点编号必须为整数');
      if (typeof m.label !== 'string' || !m.label.trim() || !Number.isFinite(m.start) || !Number.isFinite(m.end) || m.start < 0 || m.end < m.start || m.end > max) throw new Error('标注范围错误');
      if (item.kind === 'image' && (m.y !== undefined || m.height !== undefined) && (!Number.isFinite(m.y) || !Number.isFinite(m.height) || m.y! < 0 || m.height! <= 0 || m.y! + m.height! > 1000)) throw new Error('标注框格式错误');
    }
  }
  return p;
}

export async function storage(key: string, project?: Project): Promise<Project | undefined> {
  return new Promise((resolve, reject) => {
    const open = indexedDB.open('tss-manual-label-demo-v1', 1);
    open.onupgradeneeded = () => open.result.createObjectStore('projects');
    open.onerror = () => reject(open.error);
    open.onsuccess = () => {
      const db = open.result;
      const tx = db.transaction('projects', project ? 'readwrite' : 'readonly');
      const request = project ? tx.objectStore('projects').put(project, key) : tx.objectStore('projects').get(key);
      tx.oncomplete = () => { db.close(); resolve(project || request.result); };
      tx.onabort = tx.onerror = () => { db.close(); reject(tx.error || new Error('浏览器存储失败')); };
    };
  });
}
