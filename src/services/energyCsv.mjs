import { validateEnergyInput } from './labelingEnergy.mjs';

// RFC 4180 subset with escaped quotes, CRLF and quoted newlines. No numeric coercion of empty cells.
export function parseCsv(text) {
  const rows = [];
  let row = [],
    cell = '',
    quoted = false,
    closed = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (quoted) {
      if (c === '"') {
        if (text[i + 1] === '"') {
          cell += '"';
          i++;
        } else {
          quoted = false;
          closed = true;
        }
      } else cell += c;
    } else if (c === '"' && !cell && !closed) quoted = true;
    else if (c === ',' || c === '\n' || c === '\r') {
      row.push(cell);
      cell = '';
      closed = false;
      if (c !== ',') {
        if (c === '\r' && text[i + 1] === '\n') i++;
        if (row.some((v) => v !== '')) rows.push(row);
        row = [];
      }
    } else {
      if (closed || c === '"') throw new Error('CSV 引号格式错误');
      cell += c;
    }
  }
  if (quoted) throw new Error('CSV 引号未闭合');
  row.push(cell);
  if (row.some((v) => v !== '')) rows.push(row);
  if (!rows.length) throw new Error('CSV 为空');
  rows[0][0] = rows[0][0].replace(/^\uFEFF/, '');
  if (new Set(rows[0]).size !== rows[0].length) throw new Error('CSV 列名重复');
  if (rows.slice(1).some((r) => r.length !== rows[0].length))
    throw new Error('CSV 行列数不一致');
  return rows;
}
export async function importEnergyCsv(text) {
  const [headers, ...rows] = parseCsv(text);
  const required = [
    'task_id',
    'station_key',
    'date_local',
    'daily_kwh',
    ...Array.from({ length: 24 }, (_, h) => `h${String(h).padStart(2, '0')}`),
  ];
  for (const name of required)
    if (!headers.includes(name)) throw new Error(`缺少列：${name}`);
  if (!rows.length || rows.length > 5000)
    throw new Error('本地预览支持 1—5000 条站点日宽表样本');
  const seen = new Set(),
    days = new Set();
  const hash = async (value) =>
    Array.from(
      new Uint8Array(
        await crypto.subtle.digest('SHA-256', new TextEncoder().encode(value)),
      ),
      (b) => b.toString(16).padStart(2, '0'),
    ).join('');
  const sourceHash = await hash(text);
  const samples = [];
  for (const [index, row] of rows.entries()) {
    const r = Object.fromEntries(headers.map((key, i) => [key, row[i]]));
    const number = (key) => {
      if (!r[key].trim()) return null;
      const v = Number(r[key]);
      if (!Number.isFinite(v) || v < 0)
        throw new Error(`第 ${index + 2} 行 ${key} 不是有效非负数`);
      return v;
    };
    if (
      !r.task_id ||
      seen.has(r.task_id) ||
      days.has(`${r.station_key}/${r.date_local}`)
    )
      throw new Error(`第 ${index + 2} 行 task_id 为空或样本重复`);
    seen.add(r.task_id);
    days.add(`${r.station_key}/${r.date_local}`);
    const values = Array.from({ length: 24 }, (_, h) =>
      number(`h${String(h).padStart(2, '0')}`),
    );
    const payload = {
      stationKey: r.station_key,
      stationLevel: r.station_level,
      dateLocal: r.date_local,
      timezone: 'Asia/Shanghai',
      dailyKwh: number('daily_kwh'),
      ...(values.includes(null) ? { qualityReasons: ['SOURCE_MISSING'] } : {}),
      series: {
        name: 'load_curve',
        channel: 'value_kwh',
        unit: 'kWh',
        start: `${r.date_local}T00:00:00+08:00`,
        periodSeconds: 3600,
        values,
      },
    };
    const input = validateEnergyInput({
      sampleId: `energy:${r.task_id}`,
      inputFormat: 'energy-station-day-input-v1',
      schemaId: 'energy-station-day-input@1.0.0',
      inputHash: await hash(JSON.stringify(payload)),
      payload,
      context: { status: 'UNAVAILABLE', availableHistoryDays: 0 },
    });
    samples.push({
      input,
      source: {
        taskId: r.task_id,
        enterpriseKey: r.enterprise_key,
        sourceHash,
        row: index + 2,
      },
    });
  }
  return { sourceHash, samples };
}
