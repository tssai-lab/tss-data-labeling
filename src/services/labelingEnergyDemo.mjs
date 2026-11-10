import {
  validateEnergyInput,
  validateEnergyResult,
} from './labelingEnergy.mjs';

const clone = (value) => structuredClone(value);
export async function contentHash(value) {
  const bytes = new TextEncoder().encode(
    typeof value === 'string' ? value : JSON.stringify(value),
  );
  return [...new Uint8Array(await crypto.subtle.digest('SHA-256', bytes))]
    .map((v) => v.toString(16).padStart(2, '0'))
    .join('');
}
export async function createEnergyDemo({
  enabled = false,
  production = true,
  serialized,
} = {}) {
  if (!enabled || production)
    throw new Error('MOCK_DISABLED：能耗试标仅限显式启用的开发模式');
  const inputs = [];
  for (let i = 0; i < 10; i++) {
    const values = Array.from({ length: 24 }, (_, h) =>
      i === 0
        ? 0
        : i === 1 && h === 8
          ? null
          : h < 6 || h >= 22
            ? 0
            : 8 + ((h * 3 + i) % 11),
    );
    const payload = {
      stationKey: `synthetic-station-${i + 1}`,
      stationLevel: 'SYNTHETIC_DEVICE',
      totalBasis: '合成设备口径，不与企业总表相加',
      dateLocal: '2026-01-02',
      timezone: 'Asia/Shanghai',
      dailyKwh: i === 1 ? null : values.reduce((sum, v) => sum + (v ?? 0), 0),
      qualityReasons: i === 1 ? ['合成缺测：08—09小时桶'] : [],
      series: {
        name: 'load_curve',
        channel: 'value_kwh',
        unit: 'kWh',
        start: '2026-01-02T00:00:00+08:00',
        periodSeconds: 3600,
        values,
      },
    };
    inputs.push(
      validateEnergyInput({
        sampleId: `synthetic-energy-${i + 1}`,
        inputFormat: 'energy-station-day-input-v1',
        schemaId: 'energy-station-day-input@1.0.0',
        inputHash: await contentHash(payload),
        payload,
        context: {
          status: 'BASELINE_INSUFFICIENT',
          availableHistoryDays: 0,
          contextVersion: 'synthetic-no-history-v1',
        },
      }),
    );
  }
  const initial = {
    version: 'energy-trial-v1',
    drafts: {},
    submitted: false,
    results: [],
    current: {},
    reviewRevisions: {},
    accepted: {},
    finals: [],
    sequence: 0,
  };
  const state = serialized ? JSON.parse(serialized) : initial;
  if (
    state.version !== initial.version ||
    !Array.isArray(state.results) ||
    !Array.isArray(state.finals) ||
    typeof state.drafts !== 'object'
  )
    throw new Error('合成缓存版本不兼容，请明确重置试标');
  const input = (id) => {
    const value = inputs.find((v) => v.sampleId === id);
    if (!value) throw new Error('不接受真实样本ID');
    return value;
  };
  for (const [id, draft] of Object.entries(state.drafts))
    validateEnergyResult(draft.result, input(id), draft.explicitDone);
  for (const r of state.results)
    validateEnergyResult(r.result, input(r.sampleId), true);
  const uid = (kind) => `synthetic-energy-${kind}-${++state.sequence}`;
  const draft = (id) => {
    input(id);
    return clone(
      state.drafts[id] || {
        revision: 0,
        explicitDone: false,
        resultFormat: 'annotation-record-v1',
        result: { schemaVersion: '1.0.0', annotations: [] },
      },
    );
  };
  const currentResult = (id) =>
    state.results.find((r) => r.resultId === state.current[id]);
  return {
    inputs: clone(inputs),
    draft,
    snapshot: () => clone(state),
    serialize: () => JSON.stringify(state),
    save(id, hash, body) {
      if (state.submitted) throw new Error('ASSIGNMENT_CLOSED：已交回包只读');
      const source = input(id);
      if (hash !== source.inputHash)
        throw new Error('INPUT_VERSION_CONFLICT：输入已变更');
      if (body.baseRevision !== draft(id).revision)
        throw new Error('REVISION_CONFLICT：草稿版本冲突');
      if (body.resultFormat !== 'annotation-record-v1')
        throw new Error('TEMPLATE_MISMATCH');
      validateEnergyResult(body.result, source, body.explicitDone);
      state.drafts[id] = {
        revision: body.baseRevision + 1,
        explicitDone: body.explicitDone,
        resultFormat: body.resultFormat,
        result: clone(body.result),
      };
      return draft(id);
    },
    submit(items) {
      if (state.submitted) throw new Error('ASSIGNMENT_CLOSED');
      if (
        items.length !== inputs.length ||
        new Set(items.map((i) => i.sampleId)).size !== inputs.length
      )
        throw new Error('INCOMPLETE_PACKAGE：必须包含当前包全部10项');
      for (const item of items) {
        const saved = draft(item.sampleId);
        if (saved.revision !== item.revision || !saved.explicitDone)
          throw new Error('INCOMPLETE_PACKAGE：存在未完成或过期草稿');
        validateEnergyResult(saved.result, input(item.sampleId), true);
      }
      for (const item of items) {
        const r = {
          sampleId: item.sampleId,
          resultId: uid('result'),
          sourceKind: 'HUMAN',
          sourceActor: 'synthetic-annotator-a',
          confidence: null,
          ruleVersion: null,
          specVersionId: 'synthetic-energy-spec-v1',
          inputHash: input(item.sampleId).inputHash,
          result: draft(item.sampleId).result,
        };
        state.results.push(r);
        state.current[item.sampleId] = r.resultId;
        state.reviewRevisions[item.sampleId] = 0;
      }
      state.submitted = true;
      return clone(state);
    },
    revise(id, baseReviewRevision, result, reason) {
      const old = currentResult(id);
      if (!old || state.reviewRevisions[id] !== baseReviewRevision)
        throw new Error('REVIEW_CONFLICT');
      if (!reason?.trim()) throw new Error('修订须填写原因');
      validateEnergyResult(result, input(id), true);
      const r = {
        ...old,
        resultId: uid('owner'),
        sourceKind: 'OWNER',
        sourceActor: 'synthetic-owner',
        parentResultId: old.resultId,
        reason,
        confidence: null,
        result: clone(result),
      };
      state.results.push(r);
      state.current[id] = r.resultId;
      state.reviewRevisions[id]++;
      delete state.accepted[id];
      return clone(r);
    },
    review(items) {
      for (const item of items)
        if (
          !currentResult(item.sampleId) ||
          state.current[item.sampleId] !== item.resultId ||
          state.reviewRevisions[item.sampleId] !== item.reviewRevision
        )
          throw new Error('REVIEW_CONFLICT：固定清单已变化，整体拒绝');
      if (
        !items.length ||
        new Set(items.map((i) => i.sampleId)).size !== items.length
      )
        throw new Error('EMPTY_OR_DUPLICATE_SELECTION');
      for (const item of items) {
        state.accepted[item.sampleId] = item.resultId;
        state.reviewRevisions[item.sampleId]++;
      }
      return clone(state);
    },
    finalize() {
      if (
        inputs.some(
          (i) =>
            !state.current[i.sampleId] ||
            state.accepted[i.sampleId] !== state.current[i.sampleId],
        )
      )
        throw new Error('FINAL_RESULTS_INCOMPLETE：10项都需显式确认');
      const final = {
        finalSnapshotId: uid('final'),
        rows: inputs.map((i) => ({
          ...clone(currentResult(i.sampleId)),
          reviewRevision: state.reviewRevisions[i.sampleId],
          reviewedBy: 'synthetic-owner',
          taskId: `synthetic-task-${i.sampleId}`,
          stationKey: i.payload.stationKey,
          dateLocal: i.payload.dateLocal,
          datasetVersion: 'synthetic-only-v1',
        })),
      };
      state.finals.push(final);
      return clone(final);
    },
    async export(finalId) {
      const final = state.finals.find((f) => f.finalSnapshotId === finalId);
      if (!final) throw new Error('只允许已确认成果快照导出');
      const rows = clone(final.rows);
      const files = {
        'annotations.jsonl': `${rows
          .map((row) =>
            JSON.stringify({
              ...row,
              resultFormat: 'annotation-record-v1',
              templateId: 'energy-station-day',
              templateVersion: '1.0.0',
              resultSchemaId: 'energy-station-day-result@1.0.0',
            }),
          )
          .join('\n')}\n`,
      };
      const fields = [
        'task_id',
        'station_key',
        'date_local',
        'label',
        'label_detail',
        'notes',
        'self_reported_certainty',
        'confidence',
        'result_id',
      ];
      const csvEscape = (value) =>
        `"${String(value ?? '').replaceAll('"', '""')}"`;
      files['station-days.csv'] =
        '\uFEFF' +
        fields.join(',') +
        '\r\n' +
        rows
          .map((r) => {
            const find = (name) =>
              r.result.annotations.find((a) => a.from_name === name)?.value;
            return [
              r.taskId,
              r.stationKey,
              r.dateLocal,
              find('primary_label')?.labelIds[0],
              JSON.stringify(
                r.result.annotations.filter(
                  (a) =>
                    ![
                      'primary_label',
                      'notes',
                      'self_reported_certainty',
                    ].includes(a.from_name),
                ),
              ),
              find('notes')?.text,
              find('self_reported_certainty')?.labelIds[0],
              r.confidence,
              r.resultId,
            ]
              .map(csvEscape)
              .join(',');
          })
          .join('\r\n');
      files['manifest.json'] = JSON.stringify(
        {
          synthetic: true,
          finalSnapshotId: finalId,
          sampleCount: rows.length,
          sourceFormat: 'annotation-record-v1',
          inputSchemaId: 'energy-station-day-input@1.0.0',
          resultSchemaId: 'energy-station-day-result@1.0.0',
          originalAssetsIncluded: false,
          files: await Promise.all(
            Object.entries(files).map(async ([name, content]) => ({
              name,
              sha256: await contentHash(content),
            })),
          ),
        },
        null,
        2,
      );
      return files;
    },
  };
}
