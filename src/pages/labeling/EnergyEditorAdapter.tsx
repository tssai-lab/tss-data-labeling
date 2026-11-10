import {
  Alert,
  Button,
  Card,
  Checkbox,
  Input,
  InputNumber,
  Select,
  Space,
  Tag,
  Typography,
} from 'antd';
import { useEffect, useId, useRef, useState } from 'react';
import {
  decodeEnergyResult,
  type EnergyDraft,
  type EnergyInput,
  type EnergyResult,
  type EnergyValue,
  emptyEnergyValue,
  encodeEnergyResult,
  energyLabels,
  energySummary,
  validateEnergyResult,
} from '@/services/labelingEnergy.mjs';
import type { EnergySave } from '@/services/labelingEnergyDemo.mjs';

export type EnergyEditorProps = {
  input: EnergyInput;
  draft: EnergyDraft;
  reference?: EnergyResult | null;
  readOnly: boolean;
  allowIncomplete?: boolean;
  onSave: (value: EnergySave) => Promise<EnergyDraft>;
  onStateChange: (value: {
    dirty: boolean;
    saving: boolean;
    valid: boolean;
  }) => void;
};
const options = energyLabels.map((value) => ({ value, label: value }));
export default function EnergyEditorAdapter({
  input,
  draft,
  reference,
  readOnly,
  allowIncomplete = true,
  onSave,
  onStateChange,
}: EnergyEditorProps) {
  const original = draft.revision === 0 && reference ? reference : draft.result;
  const controlId = useId();
  const [value, setValue] = useState<EnergyValue>(() =>
    decodeEnergyResult(original, input),
  );
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);
  const [uncertain, setUncertain] = useState(false);
  const [hour, setHour] = useState(0);
  const [end, setEnd] = useState(1);
  const [mark, setMark] = useState('');
  const [zoom, setZoom] = useState<[number, number]>([0, 23]);
  const drag = useRef<number | null>(null);
  const lock = useRef(false);
  const mounted = useRef(true);
  const notify = useRef(onStateChange);
  notify.current = onStateChange;
  let result: EnergyResult | undefined;
  let valid = false;
  try {
    result = encodeEnergyResult(value, input);
    valid = validateEnergyResult(result, input, true);
  } catch {
    /* Incomplete drafts stay editable. */
  }
  const dirty = JSON.stringify(result) !== JSON.stringify(draft.result);
  const disabled = readOnly || saving || uncertain;
  useEffect(() => {
    notify.current({ dirty, saving, valid });
  }, [dirty, saving, valid]);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);
  const patch = (change: Partial<EnergyValue>) =>
    setValue((old) => ({ ...old, ...change }));
  async function save(done: boolean) {
    if (lock.current || disabled) return;
    lock.current = true;
    setSaving(true);
    setError('');
    try {
      const next = encodeEnergyResult(value, input);
      validateEnergyResult(next, input, done);
      const saved = await onSave({
        baseRevision: draft.revision,
        explicitDone: done,
        resultFormat: 'annotation-record-v1',
        result: next,
      });
      if (!mounted.current) return;
      if (
        saved.revision <= draft.revision ||
        JSON.stringify(saved.result) !== JSON.stringify(next) ||
        saved.explicitDone !== done
      )
        throw new Error('保存读回不一致');
    } catch (e) {
      if (mounted.current) {
        setError(e instanceof Error ? e.message : '保存失败');
        setUncertain(true);
      }
    } finally {
      lock.current = false;
      if (mounted.current) setSaving(false);
    }
  }
  const summary = energySummary(input),
    series = input.payload.series.values;
  const max = Math.max(1, ...series.filter((v): v is number => v !== null));
  const x = (h: number) =>
    45 + ((h - zoom[0]) / Math.max(1, zoom[1] - zoom[0])) * 700;
  const y = (v: number) => 190 - (v / max) * 150;
  const segments: string[] = [];
  let segment: string[] = [];
  for (let h = zoom[0]; h <= zoom[1]; h++) {
    const v = series[h];
    if (v === null) {
      if (segment.length) segments.push(segment.join(' '));
      segment = [];
    } else segment.push(`${x(h)},${y(v)}`);
  }
  if (segment.length) segments.push(segment.join(' '));
  const eventHour = (event: React.PointerEvent<SVGSVGElement>) => {
    const rect = event.currentTarget.getBoundingClientRect();
    return Math.max(
      zoom[0],
      Math.min(
        zoom[1],
        Math.round(
          ((((event.clientX - rect.left) / rect.width) * 800 - 45) / 700) *
            (zoom[1] - zoom[0]) +
            zoom[0],
        ),
      ),
    );
  };
  return (
    <Card title="24小时能耗曲线与试标">
      <Space wrap>
        <Tag>{input.payload.stationKey}</Tag>
        <Tag>{input.payload.dateLocal} · Asia/Shanghai</Tag>
        <Tag>{input.payload.stationLevel}</Tag>
        <Tag>单位 kWh / 小时桶</Tag>
        <Tag>日值 {input.payload.dailyKwh ?? '未知'}</Tag>
        <Tag>
          小时合计{' '}
          {summary.completeSum ?? `不可计算（已知${summary.knownSum}）`}
        </Tag>
        <Tag>差异 {summary.difference ?? '未知'}</Tag>
      </Space>
      <Typography.Paragraph>
        {input.payload.totalBasis}。日值独立展示，不作为第25个点。
      </Typography.Paragraph>
      <Alert
        type="warning"
        showIcon
        message={`${input.context.status} · 可用历史 ${input.context.availableHistoryDays} 天；不推断30天规则。${summary.missing ? `缺测 ${summary.missing} 个小时，曲线断开。` : '零值是实际数值，不等于缺测。'}`}
      />
      <svg
        role="img"
        aria-label="北京时间24小时能耗曲线，点击选择小时，拖选区间"
        viewBox="0 0 800 235"
        style={{
          width: '100%',
          touchAction: 'pan-y',
          background: '#f6f8fc',
          marginTop: 12,
        }}
        onPointerDown={(event) => {
          if (!disabled) {
            drag.current = eventHour(event);
            event.currentTarget.setPointerCapture(event.pointerId);
          }
        }}
        onPointerUp={(event) => {
          if (drag.current === null) return;
          const last = eventHour(event);
          setHour(Math.min(drag.current, last));
          setEnd(Math.min(24, Math.max(drag.current, last) + 1));
          drag.current = null;
        }}
      >
        <title>能耗kWh；零值和缺测分别表示；日值不参与曲线</title>
        <path d="M45 30 V190 H750" fill="none" stroke="#667085" />
        {segments.map((points) => (
          <polyline
            key={points}
            points={points}
            fill="none"
            stroke="#1677ff"
            strokeWidth="2"
          />
        ))}
        {Array.from(
          { length: zoom[1] - zoom[0] + 1 },
          (_, i) => i + zoom[0],
        ).map((h) => (
          <g key={h}>
            {series[h] === null ? (
              <text x={x(h)} y="180" textAnchor="middle" fill="#d4380d">
                缺
              </text>
            ) : (
              <circle
                cx={x(h)}
                cy={y(series[h] ?? 0)}
                r={hour === h ? 6 : 3}
                fill={
                  value.points.some((p) => p.hour === h) ? '#d4380d' : '#1677ff'
                }
              />
            )}
            {(h === zoom[0] || h === zoom[1] || h % 3 === 0) && (
              <text x={x(h)} y="213" textAnchor="middle" fontSize="14">
                {h}:00
              </text>
            )}
          </g>
        ))}
        {value.intervals.map((p) => (
          <rect
            key={p.id}
            x={Math.max(45, x(p.startHour))}
            y="35"
            width={Math.max(
              0,
              Math.min(745, x(p.endHour)) - Math.max(45, x(p.startHour)),
            )}
            height="155"
            fill="#faad14"
            opacity="0.18"
          />
        ))}
        <text x="45" y="20" fontSize="14">
          {max} kWh
        </text>
      </svg>
      <Space wrap>
        <span>查看小时范围</span>
        <InputNumber
          aria-label="曲线起始小时"
          min={0}
          max={zoom[1] - 1}
          value={zoom[0]}
          onChange={(v) => v !== null && setZoom([v, zoom[1]])}
        />
        <InputNumber
          aria-label="曲线结束小时"
          min={zoom[0] + 1}
          max={23}
          value={zoom[1]}
          onChange={(v) => v !== null && setZoom([zoom[0], v])}
        />
        <Button onClick={() => setZoom([0, 23])}>查看全天</Button>
      </Space>
      <Space wrap style={{ marginTop: 12 }}>
        <span>
          当前小时 {hour}:00 ·{' '}
          {series[hour] === null ? '缺测' : `${series[hour]} kWh`}
        </span>
        <Select
          aria-label="选择小时"
          virtual={false}
          value={hour}
          style={{ width: 110 }}
          options={Array.from({ length: 24 }, (_, h) => ({
            value: h,
            label: `${h}:00`,
          }))}
          onChange={setHour}
          disabled={disabled}
        />
        <span>区间结束（不含）</span>
        <InputNumber
          aria-label="区间结束小时"
          min={hour + 1}
          max={24}
          value={end}
          onChange={(v) => v !== null && setEnd(v)}
          disabled={disabled}
        />
        <Select
          aria-label="定位标签"
          virtual={false}
          placeholder="选择定位标签"
          value={mark || undefined}
          options={options}
          onChange={setMark}
          disabled={disabled}
          style={{ width: 160 }}
        />
        <Button
          disabled={disabled || !mark}
          onClick={() =>
            patch({
              points: [
                ...value.points,
                { id: crypto.randomUUID(), hour, labelId: mark },
              ],
            })
          }
        >
          添加小时标记
        </Button>
        <Button
          disabled={disabled || !mark || end <= hour}
          onClick={() =>
            patch({
              intervals: [
                ...value.intervals,
                {
                  id: crypto.randomUUID(),
                  startHour: hour,
                  endHour: end,
                  labelId: mark,
                },
              ],
            })
          }
        >
          添加区间标记
        </Button>
      </Space>
      <div style={{ margin: '12px 0' }}>
        {value.points.map((p) => (
          <Tag
            key={p.id}
            closable={!disabled}
            onClose={() =>
              patch({ points: value.points.filter((v) => v.id !== p.id) })
            }
          >
            {p.hour}:00 · {p.labelId}
          </Tag>
        ))}
        {value.intervals.map((p) => (
          <Tag
            key={p.id}
            closable={!disabled}
            onClose={() =>
              patch({ intervals: value.intervals.filter((v) => v.id !== p.id) })
            }
          >
            [{p.startHour}:00,{p.endHour}:00) · {p.labelId}
          </Tag>
        ))}
      </div>
      <Space direction="vertical" style={{ width: '100%' }}>
        {reference && draft.revision === 0 && (
          <Alert type="info" message="固定参考，待人工确认；参考不自动完成" />
        )}
        <label htmlFor={`${controlId}-primary`}>
          主标签（候选体系，尚待专家确认）
          <Select
            id={`${controlId}-primary`}
            aria-label="能耗主标签"
            virtual={false}
            value={value.primary || undefined}
            placeholder="请选择，不默认正常"
            options={options}
            onChange={(primary) => patch({ primary })}
            disabled={disabled}
            style={{ width: '100%' }}
          />
        </label>
        <label htmlFor={`${controlId}-secondary`}>
          补充标签
          <Select
            id={`${controlId}-secondary`}
            aria-label="能耗补充标签"
            virtual={false}
            mode="multiple"
            value={value.secondary}
            options={options}
            onChange={(secondary) => patch({ secondary })}
            disabled={disabled}
            style={{ width: '100%' }}
          />
        </label>
        <label htmlFor={`${controlId}-notes`}>
          备注
          <Input.TextArea
            id={`${controlId}-notes`}
            aria-label="能耗备注"
            value={value.notes}
            maxLength={2000}
            onChange={(event) => patch({ notes: event.target.value })}
            disabled={disabled}
          />
        </label>
        <Checkbox
          checked={Boolean(value.wholeDayReason)}
          disabled={disabled}
          onChange={(event) =>
            patch({
              wholeDayReason: event.target.checked
                ? '整日疑似：请补充依据'
                : '',
            })
          }
        >
          异常无法精确定位，明确记录整日疑似
        </Checkbox>
        {value.wholeDayReason && (
          <Input
            aria-label="整日疑似依据"
            value={value.wholeDayReason}
            maxLength={2000}
            onChange={(event) => patch({ wholeDayReason: event.target.value })}
            disabled={disabled}
          />
        )}
        <label htmlFor={`${controlId}-certainty`}>
          人工自评（不写入模型 confidence）
          <Select
            id={`${controlId}-certainty`}
            aria-label="人工自评"
            value={value.certainty || undefined}
            options={['HIGH', 'MEDIUM', 'LOW', 'UNKNOWN'].map((v) => ({
              value: v,
              label: v,
            }))}
            onChange={(certainty) => patch({ certainty })}
            disabled={disabled}
            style={{ width: '100%' }}
          />
        </label>
        {error && (
          <Alert
            type="error"
            message={error}
            description="保留本地内容。请使用宿主的重新读取操作核对，不自动重放。"
          />
        )}
        <Space wrap>
          {allowIncomplete && (
            <Button
              disabled={disabled}
              loading={saving}
              onClick={() => void save(false)}
            >
              保存未完成试标草稿
            </Button>
          )}
          <Button
            type="primary"
            disabled={disabled || !valid}
            loading={saving}
            onClick={() => void save(true)}
          >
            {allowIncomplete
              ? '保存并完成当前试标'
              : '保存需求方修订（仍待确认）'}
          </Button>
          <Button
            disabled={disabled}
            onClick={() => setValue(emptyEnergyValue())}
          >
            清空试标
          </Button>
        </Space>
        <Typography.Text type="secondary">
          {readOnly
            ? '只读结果'
            : `revision ${draft.revision} · ${dirty ? '尚未保存' : '与平台草稿一致'}`}
          。仅当前样本；完整交包与owner确认由宿主分别操作。
        </Typography.Text>
      </Space>
    </Card>
  );
}
