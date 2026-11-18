import { ConfigProvider } from 'antd';
import zhCN from 'antd/es/locale/zh_CN';
import React, { useEffect, useMemo, useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';
import Registry from '../../vendor/label-studio/libs/editor/src/core/Registry';
import { LabelStudio } from '../../vendor/label-studio/libs/editor/src/LabelStudio';
import { PlatformConfigContext } from '../../vendor/label-studio/platform-config/Bridge';
import { ConfigPage } from '../../vendor/label-studio/platform-config/Config';
import { createEditorSession } from './editorSession.mjs';

export function validateLabelConfig(config) {
  if (
    typeof config !== 'string' ||
    config.length > 250000 ||
    /<!DOCTYPE|<!ENTITY/i.test(config)
  )
    throw Error('配置为空、过大或包含不支持的声明');
  const doc = new DOMParser().parseFromString(config, 'application/xml');
  if (
    doc.querySelector('parsererror') ||
    doc.documentElement.tagName !== 'View'
  )
    throw Error('XML 格式错误：根节点必须为 View');
  const names = new Set();
  for (const el of doc.querySelectorAll('*')) {
    if (!Registry.tags.includes(el.tagName.toLowerCase()))
      throw Error(`未知控件：${el.tagName}`);
    for (const a of el.attributes)
      if (/^on/i.test(a.name) || /javascript:/i.test(a.value))
        throw Error('配置不支持可执行脚本属性');
    const name = el.getAttribute('name');
    if (name) {
      if (names.has(name)) throw Error(`控件名称重复：${name}`);
      names.add(name);
    }
    if (
      [
        'Choices',
        'Labels',
        'TimeSeriesLabels',
        'RectangleLabels',
        'PolygonLabels',
      ].includes(el.tagName)
    ) {
      const values = new Set(),
        aliases = new Set();
      for (const label of el.children) {
        if (!['Label', 'Choice'].includes(label.tagName)) continue;
        const value = label.getAttribute('value')?.trim(),
          alias = label.getAttribute('alias') || value;
        if (!value || values.has(value) || aliases.has(alias))
          throw Error('标签不能为空，名称和结果 ID 不能重复');
        values.add(value);
        aliases.add(alias);
      }
      if (!el.children.length && !el.getAttribute('value'))
        throw Error('至少保留一个标签或动态标签来源');
    }
  }
  // Complete schema/binding validation belongs to the platform's LS-backed HTTP
  // endpoint. Instantiating a detached MST tree here corrupts shared choice-store
  // references owned by the live preview. The real editor validates its own tree.
  if (
    ![...doc.querySelectorAll('*')].some(
      (el) =>
        el.hasAttribute('value') && el.getAttribute('value').startsWith('$'),
    )
  )
    throw Error('请配置至少一个数据对象及数据字段');
  return config;
}

// Builds preview inputs without making requests to LS, business servers or remote sample sites.
export function previewData(config, task, urls, strict = false) {
  // Real platform tasks already carry native LS data. Keep every configured
  // field, channel and time representation; do not use demo/energy adapters.
  if (task?.platformNative) {
    const data = structuredClone(task.data),
      doc = new DOMParser().parseFromString(config, 'application/xml');
    for (const node of doc.querySelectorAll('TimeSeries')) {
      if (node.getAttribute('valueType') !== 'json') continue;
      const field = (node.getAttribute('value') || '').replace(/^\$/, '');
      let value = data;
      for (const key of field
        ? field.replace(/\[(\d+)\]/g, '.$1').split('.')
        : [])
        value = value?.[key];
      if (!value || typeof value !== 'object') continue;
      const keys = (
        Array.isArray(value) ? Object.keys(value[0] || {}) : Object.keys(value)
      ).map((key) => key.toLowerCase());
      const time = node.getAttribute('timeColumn');
      const columns = [...node.querySelectorAll('Channel')].map((channel) =>
        channel.getAttribute('column'),
      );
      for (const column of [time, ...columns].filter(Boolean))
        if (
          !keys.includes(column.toLowerCase()) &&
          !(/^\d+$/.test(column) && Number(column) < keys.length)
        )
          throw Error(
            `样本中没有列“${column}”，请按实际字段配置时间列和通道。`,
          );
    }
    return data;
  }
  const doc = new DOMParser().parseFromString(config, 'application/xml'),
    data = {};
  const real = String(task?.id || '').startsWith('energy:');
  const series = task?.data?.series;
  for (const el of doc.querySelectorAll('[value]')) {
    const value = el.getAttribute('value');
    if (!value.startsWith('$')) continue;
    const field = value.slice(1),
      tag = el.tagName;
    if (tag === 'TimeSeries') {
      if (task?.platformTimeseries) {
        const descriptor = task.platformTimeseries,
          source = task.data.series;
        const time = el.getAttribute('timeColumn') || 'time';
        const channels = [...el.querySelectorAll('Channel')].map((c) => ({
          column: c.getAttribute('column'),
          unit: c.getAttribute('units'),
        }));
        if (
          !channels.length ||
          channels.some(
            (c) => !descriptor.channels.some((d) => d.column === c.column),
          )
        )
          throw Error(
            `模板通道与实际输入不匹配。可用通道：${descriptor.channels.map((c) => `${c.column}（${c.label}）`).join('、')}`,
          );
        if (channels.some((c) => c.column === time))
          throw Error('时间列不能与数值通道重名');
        if (
          descriptor.kind === 'timestamp'
            ? el.getAttribute('timeFormat') !== '%Q'
            : !!el.getAttribute('timeFormat')
        )
          throw Error(
            descriptor.kind === 'timestamp'
              ? '当前绝对时间输入使用 Unix 毫秒，请设置 timeFormat="%Q"，或按当前时序字段生成配置'
              : '当前为数值时间轴，请移除 timeFormat',
          );
        for (const channel of channels) {
          const expected = descriptor.channels.find(
            (c) => c.column === channel.column,
          );
          if (channel.unit && channel.unit !== expected.unit)
            throw Error(
              `通道 ${channel.column} 的单位应为 ${expected.unit || '无单位'}；不能通过修改显示单位转换数值`,
            );
        }
        const mapped = {
          [time]: [...source[descriptor.timeColumn]],
          ...Object.fromEntries(
            channels.map((c) => [c.column, [...source[c.column]]]),
          ),
        };
        if (el.getAttribute('valueType') === 'json') data[field] = mapped;
        else {
          const sep = el.getAttribute('sep') || ',';
          if (![',', ';', '\t'].includes(sep))
            throw Error('当前时序输入导出支持逗号、分号或制表符分隔');
          const keys = [time, ...channels.map((c) => c.column)],
            rows = [keys.join(sep)];
          for (let i = 0; i < mapped[time].length; i++)
            rows.push(keys.map((key) => mapped[key][i] ?? '').join(sep));
          const url = URL.createObjectURL(
            new Blob([rows.join('\n')], { type: 'text/csv' }),
          );
          urls.push(url);
          data[field] = url;
        }
        continue;
      }
      if (strict && !series?.value_kwh) throw Error('样本缺少实际时序输入');
      const channels = [...el.querySelectorAll('Channel')].map((c) =>
        c.getAttribute('column'),
      );
      if (!channels.length) throw Error('请为时序配置至少一个 Channel');
      if ((real || strict) && channels.some((c) => c !== 'value_kwh'))
        throw Error(
          `当前能耗样本提供 value_kwh；模板需要 ${channels.join('、')}，请在 XML 中修改通道映射。`,
        );
      const time = el.getAttribute('timeColumn') || 'time';
      const count = series?.value_kwh?.length || 24;
      const numeric = Array.from({ length: count }, (_, h) =>
        series?.value_kwh
          ? series.value_kwh[h]
          : Math.round(30 + 20 * Math.sin(h / 3)),
      );
      if (el.getAttribute('valueType') === 'json') {
        data[field] = {
          [time]: Array.from({ length: count }, (_, h) => h),
          ...Object.fromEntries(
            channels.map((c, i) => [
              c,
              numeric.map((v) => (v === null ? null : v + i * 5)),
            ]),
          ),
        };
      } else {
        const rows = [[time, ...channels].join(el.getAttribute('sep') || ',')];
        for (let h = 0; h < count; h++) {
          const t = el.getAttribute('timeFormat')
            ? `${task?.data?.date_local || '2026-09-01'} ${String(h).padStart(2, '0')}:00:00.000000`
            : h;
          rows.push(
            [
              t,
              ...channels.map((_, i) =>
                numeric[h] === null ? '' : numeric[h] + i * 5,
              ),
            ].join(el.getAttribute('sep') || ','),
          );
        }
        const url = URL.createObjectURL(
          new Blob([rows.join('\n')], { type: 'text/csv' }),
        );
        urls.push(url);
        data[field] = url;
      }
    } else if (tag === 'Image') {
      if (strict && !task?.data?.image) throw Error('样本缺少实际图片输入');
      data[field] = task?.data?.image || '/ls-template-samples/sample.jpg';
    } else if (tag === 'Text' || tag === 'HyperText' || tag === 'Header') {
      if (strict && task?.data?.[field] === undefined)
        throw Error(`样本缺少实际字段：${field}`);
      data[field] = task?.data?.[field] || '本地示例文本：在这里预览标注界面。';
    } else if (['Choices', 'Labels'].includes(tag)) {
      if (strict && task?.data?.[field] === undefined)
        throw Error(`样本缺少实际字段：${field}`);
      data[field] = task?.data?.[field] || [{ value: '示例标签' }];
    } else
      throw Error(
        `当前未提供 ${tag} 的本地预览样本；模板与 XML 配置仍可编辑。`,
      );
  }
  return data;
}

let disposing = Promise.resolve();
export const waitForPreviewDisposal = () => disposing;
export function NativePreview({ config, data, error, loading }) {
  const context = React.useContext(PlatformConfigContext);
  const host = useRef(null),
    [failure, setFailure] = useState('');
  const session = useRef(null);
  useEffect(
    () => () => {
      const previous = session.current;
      session.current = null;
      if (previous) disposing = previous.destroy();
    },
    [],
  );
  useEffect(() => {
    let active = true;
    setFailure('');
    if (!data || error) return;
    void (async () => {
      await disposing;
      if (!active) return;
      if (!session.current)
        session.current = createEditorSession(LabelStudio, host.current, {
          instanceOptions: { reactVersion: 'v18' },
          interfaces: ['side-column'],
        });
      await session.current.update({
        config,
        task: {
          id: 'native-template-preview',
          data,
          annotations: context?.previewAnnotations || [],
          predictions: context?.previewPredictions || [],
        },
      });
    })().catch((e) => {
      if (active) setFailure(`预览失败：${e.message}`);
    });
    return () => {
      active = false;
    };
  }, [
    config,
    data,
    error,
    context?.previewAnnotations,
    context?.previewPredictions,
  ]);
  return (
    <div className="lsf-configure__preview">
      <h3>预览</h3>
      <p>
        {context?.previewExample ? '示例数据预览' : '项目样本预览'}
        ，操作不会保存为正式标注结果。
      </p>
      {loading && <p role="status">正在校验并生成预览…</p>}
      {(error || failure) && (
        <div role="alert" className="lsf-configure__preview-error">
          <p>{failure || error.detail}</p>
          {error?.validation_errors?.map?.((e) => (
            <p key={e}>{e}</p>
          ))}
        </div>
      )}
      <div ref={host} hidden={!!error} className="lsf-configure__preview-ui" />
    </div>
  );
}

function NativeConfig({
  config,
  task,
  category,
  request,
  onChange,
  onRecipe,
  onSave,
}) {
  const latest = useRef({
    task,
    category,
    request,
    onChange,
    onRecipe,
    onSave,
  });
  latest.current = { task, category, request, onChange, onRecipe, onSave };
  const [previewExample, setPreviewExample] = useState(true);
  const [previewAnswers, setPreviewAnswers] = useState({
    annotations: [],
    predictions: [],
  });
  const [dirty, setDirty] = useState(false),
    xml = useRef(config || '');
  const api = useMemo(
    () => ({
      callApi: async (name, options = {}) => {
        const result = await latest.current.request(name, options);
        if (name === 'createSampleTask' && !result?.error) {
          setPreviewExample(!!result.isExample);
          setPreviewAnswers({
            annotations: result.annotations || [],
            predictions: result.predictions || [],
          });
        }
        return result;
      },
    }),
    [],
  );
  const value = useMemo(
    () => ({
      api,
      previewExample,
      previewAnnotations: previewAnswers.annotations,
      previewPredictions: previewAnswers.predictions,
    }),
    [api, previewExample, previewAnswers],
  );
  return (
    <PlatformConfigContext.Provider value={value}>
      <ConfigPage
        config={config}
        project={{ id: 'platform-config' }}
        onUpdate={(value) => {
          xml.current = value;
          setDirty(true);
          let err = null;
          try {
            validateLabelConfig(value);
          } catch (e) {
            err = e.message;
          }
          latest.current.onChange(value, err);
        }}
        onRecipe={(recipe) => {
          if (
            dirty &&
            !window.confirm('当前配置尚未保存，是否放弃修改并选择其他模板？')
          )
            return false;
          latest.current.onRecipe?.(recipe);
          return true;
        }}
        onSaveClick={async () => {
          try {
            validateLabelConfig(xml.current);
            await latest.current.onSave?.();
            setDirty(false);
            return true;
          } catch (e) {
            return { detail: e.message };
          }
        }}
        hasChanges={dirty}
      />
    </PlatformConfigContext.Provider>
  );
}
export function mountNativeTemplateConfigurator(container, options) {
  const root = createRoot(container);
  root.render(
    <ConfigProvider locale={zhCN}>
      <NativeConfig {...options} />
    </ConfigProvider>,
  );
  return { destroy: () => root.unmount() };
}
