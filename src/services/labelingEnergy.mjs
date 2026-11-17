// Internal, versioned trial format. Not Label Studio native JSON or an industry standard.
export const energyLabels = Object.freeze([
  'normal',
  'idle_zero',
  'partial_zero',
  'low_load',
  'high_load',
  'spike',
  'dip',
  'missing',
  'meter_fault',
  'shift_change',
  'holiday',
]);
export const emptyEnergyValue = () => ({
  primary: '',
  secondary: [],
  points: [],
  intervals: [],
  notes: '',
  wholeDayReason: '',
  certainty: '',
});
const fail = (message) => {
  throw new Error(message);
};
const exact = (object, keys) => {
  if (
    !object ||
    typeof object !== 'object' ||
    Array.isArray(object) ||
    Object.keys(object).some((k) => !keys.includes(k))
  )
    fail('未知字段或损坏结构');
};
export function validateEnergyInput(input) {
  if (
    input?.inputFormat !== 'energy-station-day-input-v1' ||
    input.schemaId !== 'energy-station-day-input@1.0.0' ||
    !input.inputHash
  )
    fail('INPUT_VERSION_CONFLICT：输入格式、schema或摘要缺失');
  const p = input.payload,
    s = p?.series;
  if (
    !p?.stationKey ||
    !/^\d{4}-\d{2}-\d{2}$/.test(p.dateLocal) ||
    p.timezone !== 'Asia/Shanghai' ||
    s?.name !== 'load_curve' ||
    s.channel !== 'value_kwh' ||
    s.unit !== 'kWh' ||
    s.periodSeconds !== 3600 ||
    s.start !== `${p.dateLocal}T00:00:00+08:00` ||
    !Array.isArray(s.values) ||
    s.values.length !== 24
  )
    fail('站点日必须包含北京时间24个小时桶，单位kWh');
  if (
    new Date(`${p.dateLocal}T00:00:00Z`).toISOString().slice(0, 10) !==
    p.dateLocal
  )
    fail('非法业务日期');
  if (
    s.values.some((v) => v !== null && (!Number.isFinite(v) || v < 0)) ||
    (p.dailyKwh !== null && (!Number.isFinite(p.dailyKwh) || p.dailyKwh < 0))
  )
    fail('能耗必须为有限非负数或明确null，禁止补0');
  if (s.values.some((v) => v === null) && !p.qualityReasons?.length)
    fail('缺测须携带质量原因');
  return input;
}
export const hourTimestamp = (input, hour) => {
  if (!Number.isInteger(hour) || hour < 0 || hour > 24) fail('小时坐标越界');
  return hour === 24
    ? `${new Date(Date.parse(input.payload.series.start) + 86400000 + 8 * 3600000).toISOString().slice(0, 10)}T00:00:00+08:00`
    : `${input.payload.dateLocal}T${String(hour).padStart(2, '0')}:00:00+08:00`;
};
function hourOf(input, timestamp, end = false) {
  const hour =
    (Date.parse(timestamp) - Date.parse(input.payload.series.start)) / 3600000;
  if (
    !Number.isInteger(hour) ||
    hour < 0 ||
    hour > (end ? 24 : 23) ||
    timestamp !== hourTimestamp(input, hour)
  )
    fail('时间必须定位到实际小时桶，区间采用[start,end)');
  return hour;
}
export function encodeEnergyResult(value, input) {
  validateEnergyInput(input);
  const annotations = [];
  const add = (id, from_name, type, v) =>
    annotations.push({ id, from_name, to_name: 'load_curve', type, value: v });
  if (value.primary)
    add('primary', 'primary_label', 'choices', { labelIds: [value.primary] });
  if (value.secondary.length)
    add('secondary', 'secondary_labels', 'choices', {
      labelIds: [...value.secondary],
    });
  for (const p of value.points)
    add(p.id, 'point_labels', 'timeseries_point', {
      timestamp: hourTimestamp(input, p.hour),
      channel: 'value_kwh',
      labelIds: [p.labelId],
    });
  for (const p of value.intervals)
    add(p.id, 'interval_labels', 'timeseries_interval', {
      start: hourTimestamp(input, p.startHour),
      end: hourTimestamp(input, p.endHour),
      channel: 'value_kwh',
      labelIds: [p.labelId],
    });
  if (value.notes) add('notes', 'notes', 'text', { text: value.notes });
  if (value.wholeDayReason)
    add('whole-day', 'whole_day_reason', 'text', {
      text: value.wholeDayReason,
    });
  if (value.certainty)
    add('certainty', 'self_reported_certainty', 'choices', {
      labelIds: [value.certainty],
    });
  const result = { schemaVersion: '1.0.0', annotations };
  validateEnergyResult(result, input);
  return result;
}
export function decodeEnergyResult(result, input) {
  validateEnergyResult(result, input);
  const value = emptyEnergyValue();
  for (const a of result.annotations) {
    if (a.from_name === 'primary_label') value.primary = a.value.labelIds[0];
    if (a.from_name === 'secondary_labels')
      value.secondary = [...a.value.labelIds];
    if (a.from_name === 'self_reported_certainty')
      value.certainty = a.value.labelIds[0];
    if (a.from_name === 'notes') value.notes = a.value.text;
    if (a.from_name === 'whole_day_reason') value.wholeDayReason = a.value.text;
    if (a.type === 'timeseries_point')
      value.points.push({
        id: a.id,
        hour: hourOf(input, a.value.timestamp),
        labelId: a.value.labelIds[0],
      });
    if (a.type === 'timeseries_interval')
      value.intervals.push({
        id: a.id,
        startHour: hourOf(input, a.value.start),
        endHour: hourOf(input, a.value.end, true),
        labelId: a.value.labelIds[0],
      });
  }
  return value;
}
export function validateEnergyResult(result, input, complete = false) {
  validateEnergyInput(input);
  exact(result, ['schemaVersion', 'annotations']);
  if (
    result.schemaVersion !== '1.0.0' ||
    !Array.isArray(result.annotations) ||
    result.annotations.length > 100
  )
    fail('不支持的结果版本或条目过多');
  const ids = new Set(),
    singleton = new Set();
  for (const a of result.annotations) {
    exact(a, ['id', 'from_name', 'to_name', 'type', 'value']);
    if (!a.id || ids.has(a.id) || a.to_name !== 'load_curve')
      fail('结果项ID重复或目标不匹配');
    ids.add(a.id);
    const controlTypes = {
      primary_label: 'choices',
      secondary_labels: 'choices',
      point_labels: 'timeseries_point',
      interval_labels: 'timeseries_interval',
      notes: 'text',
      whole_day_reason: 'text',
      self_reported_certainty: 'choices',
    };
    if (controlTypes[a.from_name] !== a.type) fail('未知控件或类型不匹配');
    if (!['point_labels', 'interval_labels'].includes(a.from_name)) {
      if (singleton.has(a.from_name)) fail('单值控件重复');
      singleton.add(a.from_name);
    }
    if (a.type === 'text') {
      exact(a.value, ['text']);
      if (
        typeof a.value.text !== 'string' ||
        !a.value.text.trim() ||
        a.value.text.length > 2000
      )
        fail('说明须为1—2000字');
    } else {
      const keys =
        a.type === 'choices'
          ? ['labelIds']
          : a.type === 'timeseries_point'
            ? ['labelIds', 'channel', 'timestamp']
            : ['labelIds', 'channel', 'start', 'end'];
      exact(a.value, keys);
      const allowed =
        a.from_name === 'self_reported_certainty'
          ? ['HIGH', 'MEDIUM', 'LOW', 'UNKNOWN']
          : energyLabels;
      const labels = a.value.labelIds;
      if (
        !Array.isArray(labels) ||
        !labels.length ||
        new Set(labels).size !== labels.length ||
        labels.some((l) => !allowed.includes(l)) ||
        (a.from_name !== 'secondary_labels' && labels.length !== 1)
      )
        fail('非法标签或主标签数量错误');
      if (a.type !== 'choices') {
        if (a.value.channel !== 'value_kwh') fail('通道不匹配');
        if (a.type === 'timeseries_point') hourOf(input, a.value.timestamp);
        else if (
          hourOf(input, a.value.start) >= hourOf(input, a.value.end, true)
        )
          fail('区间必须满足start < end');
      }
    }
  }
  if (complete) {
    const primary = result.annotations.find(
      (a) => a.from_name === 'primary_label',
    )?.value.labelIds[0];
    if (!primary || !singleton.has('self_reported_certainty'))
      fail('正式完成需要主标签和人工自评（可选UNKNOWN）');
    if (
      primary !== 'normal' &&
      (!singleton.has('notes') ||
        (!singleton.has('whole_day_reason') &&
          !result.annotations.some((a) =>
            ['timeseries_point', 'timeseries_interval'].includes(a.type),
          )))
    )
      fail('试标异常需要备注以及点/区间或明确的整日疑似说明');
  }
  return true;
}
export function energySummary(input) {
  validateEnergyInput(input);
  const values = input.payload.series.values;
  const missing = values.filter((v) => v === null).length;
  const sum = values.reduce((s, v) => s + (v ?? 0), 0);
  return {
    missing,
    knownSum: sum,
    completeSum: missing ? null : sum,
    difference:
      missing || input.payload.dailyKwh === null
        ? null
        : sum - input.payload.dailyKwh,
  };
}
