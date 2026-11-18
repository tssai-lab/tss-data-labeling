import { parseCsv } from './energyCsv.mjs';

export const TIMESERIES_INPUT_FORMAT = 'timeseries-input-v1';
export function validateTimeseriesInput(input) {
  if (
    input?.inputFormat !== TIMESERIES_INPUT_FORMAT ||
    input.schemaId !== 'timeseries-input@1.0.0' ||
    typeof input.sampleId !== 'string' ||
    !input.sampleId.trim() ||
    typeof input.inputHash !== 'string' ||
    !/^[0-9a-f]{64}$/.test(input.inputHash)
  )
    throw Error('时序输入缺少身份、版本或摘要');
  const { time, channels } = input.payload || {};
  if (
    !time ||
    !['number', 'timestamp'].includes(time.kind) ||
    typeof time.unit !== 'string' ||
    !time.unit.trim() ||
    !Array.isArray(time.values) ||
    time.values.length < 2 ||
    time.values.length > 50000
  )
    throw Error('时序输入需要 2—50000 个时间点以及明确的时间类型和单位');
  if (time.kind === 'timestamp' && time.unit !== 'ms')
    throw Error('绝对时间轴须使用 Unix 毫秒');
  for (let i = 0; i < time.values.length; i++) {
    const value = time.values[i];
    if (
      !Number.isFinite(value) ||
      (i > 0 && value <= time.values[i - 1]) ||
      (time.kind === 'timestamp' &&
        (!Number.isSafeInteger(value) || Math.abs(value) > 8640000000000000))
    )
      throw Error('时间必须为有效数值，严格递增且不能重复');
  }
  if (!Array.isArray(channels) || !channels.length || channels.length > 16)
    throw Error('请选择 1—16 个数值通道');
  const names = new Set(['time']);
  for (const channel of channels) {
    if (
      !channel ||
      !/^[A-Za-z_][\w.-]*$/.test(channel.column) ||
      names.has(channel.column) ||
      typeof channel.label !== 'string' ||
      !channel.label.trim() ||
      typeof channel.unit !== 'string'
    )
      throw Error('通道名称、显示名称或单位无效，通道不能重名');
    names.add(channel.column);
    if (
      !Array.isArray(channel.values) ||
      channel.values.length !== time.values.length ||
      channel.values.some((v) => v !== null && !Number.isFinite(v))
    )
      throw Error('通道点数必须与时间一致，读数须为有限数值或明确 null');
  }
  return input;
}

export function createGeneralTimeseriesTask(input) {
  validateTimeseriesInput(input);
  const { time, channels } = input.payload;
  return {
    id: input.sampleId,
    data: {
      series: {
        time: [...time.values],
        ...Object.fromEntries(channels.map((c) => [c.column, [...c.values]])),
      },
      input_hash: input.inputHash,
    },
    platformTimeseries: {
      kind: time.kind,
      unit: time.unit,
      timeColumn: 'time',
      channels: channels.map(({ column, label, unit }) => ({
        column,
        label,
        unit,
      })),
    },
    annotations: [],
    predictions: [],
  };
}

const escaped = (value) =>
  String(value).replace(
    /[&<>"']/g,
    (c) =>
      ({
        '&': '&amp;',
        '<': '&lt;',
        '>': '&gt;',
        '"': '&quot;',
        "'": '&apos;',
      })[c],
  );
export function createGeneralTimeseriesConfig(input) {
  validateTimeseriesInput(input);
  const { time, channels } = input.payload;
  const format =
    time.kind === 'timestamp'
      ? 'timeFormat="%Q" timeDisplayFormat="%Y-%m-%d %H:%M:%S"'
      : 'timeDisplayFormat=".6~g"';
  return `<View>
  <Header value="时序标注"/>
  <TimeSeries name="series_view" value="$series" valueType="json" timeColumn="time" ${format} overviewWidth="100%">
${channels.map((c) => `    <Channel column="${escaped(c.column)}" legend="${escaped(c.label)}" units="${escaped(c.unit)}"/>`).join('\n')}
  </TimeSeries>
  <Choices name="classification" toName="series_view" choice="single-radio">
    <Choice value="正常" alias="normal"/><Choice value="需关注" alias="attention"/>
  </Choices>
  <TimeSeriesLabels name="events" toName="series_view"><Label value="事件" alias="event"/></TimeSeriesLabels>
  <TextArea name="notes" toName="series_view" rows="3"/>
</View>`;
}

export const timeseriesHash = async (value) =>
  Array.from(
    new Uint8Array(
      await crypto.subtle.digest('SHA-256', new TextEncoder().encode(value)),
    ),
    (b) => b.toString(16).padStart(2, '0'),
  ).join('');
export async function importTimeseriesCsv(
  text,
  {
    timeColumn,
    channelColumns,
    groupColumn = '',
    kind = 'number',
    timeUnit = 's',
    units = {},
    filename = '',
  },
) {
  const [headers, ...rows] = parseCsv(text);
  if (!rows.length || rows.length > 50000)
    throw Error('小样本预览需要 1—50000 行数据，大数据集请使用正式导入');
  if (
    !headers.includes(timeColumn) ||
    !['number', 'timestamp'].includes(kind) ||
    !channelColumns?.length ||
    channelColumns.length > 16 ||
    new Set(channelColumns).size !== channelColumns.length ||
    channelColumns.some(
      (c) => !headers.includes(c) || c === timeColumn || c === groupColumn,
    ) ||
    (groupColumn &&
      (!headers.includes(groupColumn) || groupColumn === timeColumn))
  )
    throw Error('时间、分组和通道映射无效，不能重复使用同一列');
  const groups = new Map(),
    sourceHash = await timeseriesHash(text);
  for (const [i, row] of rows.entries()) {
    const get = (column) => row[headers.indexOf(column)].trim();
    const key = groupColumn ? get(groupColumn) : filename || '时序样本';
    if (!key) throw Error(`第 ${i + 2} 行的样本分组为空`);
    const raw = get(timeColumn);
    if (
      !raw ||
      (kind === 'timestamp' &&
        !/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,3})?(?:Z|[+-]\d\d:\d\d)$/.test(
          raw,
        ))
    )
      throw Error(`第 ${i + 2} 行时间无效，绝对时间须含 Z 或时区偏移`);
    if (kind === 'timestamp') {
      const date = Date.parse(`${raw.slice(0, 10)}T00:00:00Z`);
      if (
        !Number.isFinite(date) ||
        new Date(date).toISOString().slice(0, 10) !== raw.slice(0, 10) ||
        Number(raw.slice(11, 13)) > 23 ||
        Number(raw.slice(14, 16)) > 59 ||
        Number(raw.slice(17, 19)) > 59
      )
        throw Error(`第 ${i + 2} 行日期或时间不存在`);
    }
    const point = kind === 'timestamp' ? Date.parse(raw) : Number(raw);
    if (!Number.isFinite(point)) throw Error(`第 ${i + 2} 行时间无效`);
    if (!groups.has(key))
      groups.set(key, {
        time: [],
        values: channelColumns.map(() => []),
        sourceRows: [],
      });
    if (groups.size > 500) throw Error('小样本预览每批最多 500 个样本');
    const group = groups.get(key);
    group.time.push(point);
    group.sourceRows.push(i + 2);
    channelColumns.forEach((column, n) => {
      const cell = get(column),
        value = cell === '' ? null : Number(cell);
      if (value !== null && !Number.isFinite(value))
        throw Error(`第 ${i + 2} 行通道 ${column} 不是有效数值`);
      group.values[n].push(value);
    });
  }
  const samples = [];
  for (const [key, group] of groups) {
    const payload = {
      time: {
        kind,
        unit: kind === 'timestamp' ? 'ms' : timeUnit,
        values: group.time,
      },
      channels: channelColumns.map((column, n) => ({
        column: `channel_${n + 1}`,
        label: column,
        unit: units[column] || '',
        values: group.values[n],
      })),
    };
    const input = validateTimeseriesInput({
      inputFormat: TIMESERIES_INPUT_FORMAT,
      schemaId: 'timeseries-input@1.0.0',
      sampleId: `local-series:${await timeseriesHash(`${sourceHash}\n${key}`)}`,
      inputHash: await timeseriesHash(JSON.stringify(payload)),
      payload,
    });
    samples.push({
      id: input.sampleId,
      name: key,
      input,
      source: { filename, sourceHash, group: key, rows: group.sourceRows },
    });
  }
  return {
    samples,
    mapping: {
      sourceFile: filename,
      sourceHash,
      timeColumn,
      groupColumn,
      channelColumns: channelColumns.join(','),
      timeKind: kind,
      timeUnit: kind === 'timestamp' ? 'ms' : timeUnit,
      channelMapping: JSON.stringify(
        channelColumns.map((column, n) => ({
          source: column,
          target: `channel_${n + 1}`,
          unit: units[column] || '',
        })),
      ),
    },
  };
}
