import { validateEnergyInput } from './labelingEnergy.mjs';
import { createGeneralTimeseriesTask } from './timeseriesInput.mjs';

export function createPlatformTimeseriesTask(input) {
  if (input?.inputFormat === 'timeseries-input-v1')
    return createGeneralTimeseriesTask(input);
  if (input?.inputFormat === 'energy-station-day-input-v1')
    return createLsTimeseriesTask(input);
  throw Error('未注册或不兼容的时序输入格式');
}

// LS-native editing contract. This does not enable the production energy template.
export const lsTimeseriesTemplate = Object.freeze({
  id: 'energy-ls-timeseries',
  version: '0.1.0',
  upstreamVersion: '1.23.2',
  status: 'experimental',
  objectName: 'load_curve',
});

function fail(message) {
  throw new Error(`LS_TIMESERIES_CONTRACT: ${message}`);
}

const xml = (value) =>
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

function validateLabels(labels) {
  if (!Array.isArray(labels) || !labels.length) fail('项目必须定义标签');
  const ids = new Set();
  const names = new Set();
  for (const label of labels) {
    if (
      !label ||
      typeof label.id !== 'string' ||
      !/^[A-Za-z][\w.-]*$/.test(label.id) ||
      typeof label.name !== 'string' ||
      !label.name.trim() ||
      ids.has(label.id) ||
      names.has(label.name)
    )
      fail('标签 ID 必须稳定、唯一且合法，名称不能为空');
    ids.add(label.id);
    names.add(label.name);
  }
}

/** Generates only trusted built-in tags; callers supply labels, never arbitrary XML. */
export function createLsTimeseriesConfig(labels) {
  validateLabels(labels);
  // LS alias is the serialized value, not the display name (Choice._resultValue).
  const choices = labels
    .map((l) => `    <Choice value="${xml(l.name)}" alias="${xml(l.id)}"/>`)
    .join('\n');
  const regions = labels
    .map((l) => `    <Label value="${xml(l.name)}" alias="${xml(l.id)}"/>`)
    .join('\n');
  return `<View>
  <Header value="站点日能耗标注 · 小时能耗（kWh）"/>
  <TimeSeries name="load_curve" value="$series" valueType="json" timeColumn="hour" timeDisplayFormat=".0f" overviewWidth="100%">
    <Channel column="value_kwh" units="kWh" legend="小时能耗"/>
  </TimeSeries>
  <Header value="整日主标签"/>
  <Choices name="primary_label" toName="load_curve" choice="single-radio">
${choices}
  </Choices>
  <Header value="补充标签（可多选）"/>
  <Choices name="secondary_labels" toName="load_curve" choice="multiple">
${choices}
  </Choices>
  <Header value="异常定位：选择标签后双击标点、拖动标记区间"/>
  <TimeSeriesLabels name="event_labels" toName="load_curve">
${regions}
  </TimeSeriesLabels>
  <Header value="标注备注"/>
  <TextArea name="notes" toName="load_curve" rows="3" maxSubmissions="1"/>
</View>`;
}

/** No network, no prediction auto-accept, no fabricated task identity. */
export function createLsTimeseriesTask(input) {
  validateEnergyInput(input);
  if (typeof input.sampleId !== 'string' || !input.sampleId)
    fail('缺少平台 sampleId');
  return {
    id: input.sampleId,
    data: {
      series: {
        hour: Array.from({ length: 24 }, (_, h) => h),
        value_kwh: [...input.payload.series.values],
      },
      station_key: input.payload.stationKey,
      date_local: input.payload.dateLocal,
      timezone: input.payload.timezone,
      daily_kwh: input.payload.dailyKwh,
      input_hash: input.inputHash,
    },
    annotations: [],
    predictions: [],
  };
}
