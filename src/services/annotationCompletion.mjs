export const defaultCompletion = Object.freeze({
  allowEmpty: false,
  required: [],
});

// Compile the published XML once; execution uses the frozen rule, never a new draft.
export function compileCompletion(config, allowEmpty = false) {
  const doc = new DOMParser().parseFromString(config, 'application/xml');
  if (doc.querySelector('parsererror')) throw Error('标注配置 XML 无效');
  const choices = [...doc.querySelectorAll('Choices')].filter(
    (e) => e.getAttribute('perRegion') !== 'true',
  );
  const required = [...doc.querySelectorAll('[name][toName]')]
    .filter(
      (e) =>
        e.getAttribute('perRegion') !== 'true' &&
        (e.getAttribute('required') === 'true' || e === choices[0]),
    )
    .map((e) => ({
      name: e.getAttribute('name'),
      type: e.tagName.toLowerCase(),
    }));
  return { allowEmpty: !!allowEmpty, required };
}

const meaningful = (r) => {
  const v = r?.value;
  return (
    !!v &&
    Object.values(v).some((x) =>
      Array.isArray(x)
        ? x.length > 0
        : typeof x === 'string'
          ? x.trim().length > 0
          : typeof x === 'number' && Number.isFinite(x),
    )
  );
};
export function validateCompletion(
  value,
  rules = defaultCompletion,
  context = {},
) {
  if (!Array.isArray(value)) throw Error('标注结果必须为数组');
  for (const r of value) {
    if (
      !r ||
      typeof r !== 'object' ||
      !r.value ||
      !r.from_name ||
      !r.to_name ||
      !r.type
    )
      throw Error('标注结果结构无效，请检查或重新标注');
    const v = r.value;
    if (r.type === 'timeserieslabels') {
      if (context.input?.inputFormat === 'timeseries-input-v1') {
        const time = context.input.payload.time,
          start =
            typeof v.start === 'number'
              ? v.start
              : time.kind === 'timestamp' && /^-?\d+$/.test(v.start)
                ? Number(v.start)
                : NaN,
          end =
            typeof v.end === 'number'
              ? v.end
              : time.kind === 'timestamp' && /^-?\d+$/.test(v.end)
                ? Number(v.end)
                : NaN;
        if (
          ![start, end].every(Number.isFinite) ||
          start < time.values[0] ||
          end > time.values.at(-1) ||
          end < start
        )
          throw Error('时序标记超出当前样本的实际时间范围');
        if (v.instant && start !== end) throw Error('点标注的起止时间必须相同');
      }
      // Native numeric energy axis is 00:00–23:00, not a 25th value at 24:00.
      if (context.input?.inputFormat === 'energy-station-day-input-v1') {
        const axis = (time) => {
          if (typeof time === 'number') return time;
          if (
            typeof time !== 'string' ||
            !/^\d{4}-\d\d-\d\d[ T]\d\d:\d\d:\d\d(?:\.\d+)?$/.test(time)
          )
            return NaN;
          // LS formats wall-clock labels with UTC formatters. Compare calendar labels,
          // never parse them in the workstation's local timezone.
          return (
            (Date.parse(`${time.replace(' ', 'T')}Z`) -
              Date.parse(`${context.input.payload.dateLocal}T00:00:00Z`)) /
            3600000
          );
        };
        const start = axis(v.start),
          end = axis(v.end);
        if (
          ![start, end].every(Number.isFinite) ||
          start < 0 ||
          end > 23 ||
          end < start
        )
          throw Error('时序标记超出 0—23 小时的实际采样范围');
      }
    }
    if (
      ['rectanglelabels', 'rectangle', 'keypointlabels', 'keypoint'].includes(
        r.type,
      )
    ) {
      if (
        ![v.x, v.y].every(Number.isFinite) ||
        v.x < 0 ||
        v.x > 100 ||
        v.y < 0 ||
        v.y > 100
      )
        throw Error('图像坐标必须在图片范围内');
      if (
        r.type.startsWith('rectangle') &&
        (![v.width, v.height].every(Number.isFinite) ||
          v.width <= 0 ||
          v.height <= 0)
      )
        throw Error('标注框的宽高必须大于零');
    }
  }
  for (const required of rules.required || []) {
    if (!value.some((r) => r.from_name === required.name && meaningful(r)))
      throw Error(`请完成必填控件：${required.name}`);
  }
  if (!value.some(meaningful)) {
    if (!rules.allowEmpty)
      throw Error('当前规范不允许空结果，请完成标注后再标记完成');
    if (!context.emptyReason?.trim()) throw Error('请明确确认无目标并填写原因');
  }
  return true;
}
