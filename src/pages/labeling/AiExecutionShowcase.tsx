import {
  ArrowRightOutlined,
  LeftOutlined,
  RightOutlined,
  ScanOutlined,
} from '@ant-design/icons';
import { Table, Tag } from 'antd';
import type { CSSProperties } from 'react';
import { useEffect, useState } from 'react';
import {
  describeLabelingError,
  labelingBlob,
  labelingJson,
  sampleBlob,
} from '@/services/labeling';
import type { NativeProject, NativeSample } from '@/services/nativeLabeling';
import ui from './aiExecution.less';
import type { DemoResult } from './NativeAi';
import type { NativeContext } from './NativeEditor';

function SamplePreview({
  project,
  sample,
  running,
}: {
  project: NativeProject;
  sample?: NativeSample;
  running: boolean;
}) {
  const [imageUrl, setImageUrl] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const isTimeSeries = ['TIME_SERIES', 'TIMESERIES_ANNOTATION'].includes(
    project.taskType,
  );
  useEffect(() => {
    setImageUrl('');
    setError('');
    if (!sample || isTimeSeries) return;
    const controller = new AbortController();
    let objectUrl = '';
    setLoading(true);
    void labelingJson<NativeContext>(
      `/projects/${project.projectId}/samples/${sample.sampleId}/native-input`,
      { signal: controller.signal },
    )
      .then(async (context) => {
        const resource = context.resources?.find((item) =>
          item.mediaType.startsWith('image/'),
        );
        if (!resource && !sample.mediaType.startsWith('image/')) return;
        const blob = resource
          ? await labelingBlob(
              resource.url.replace('/api/labeling/v1', ''),
              controller.signal,
            )
          : await sampleBlob(
              project.projectId,
              sample.sampleId,
              controller.signal,
            );
        if (controller.signal.aborted) return;
        objectUrl = URL.createObjectURL(blob);
        setImageUrl(objectUrl);
      })
      .catch((reason: unknown) => {
        if (!controller.signal.aborted) setError(describeLabelingError(reason));
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => {
      controller.abort();
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [project.projectId, sample?.sampleId, isTimeSeries]);

  const occurrences = new Map<string, number>();
  const previewRows = sample?.metadata.previewRows?.slice(0, 5).map((row) => {
    const text = row.join('  /  ');
    const occurrence = (occurrences.get(text) ?? 0) + 1;
    occurrences.set(text, occurrence);
    return { key: JSON.stringify([text, occurrence]), text };
  });
  return (
    <div className={ui.sampleCanvas} data-running={running}>
      {imageUrl ? (
        <img
          src={imageUrl}
          alt={sample?.metadata.displayName || '本轮原始样本'}
        />
      ) : (
        <div className={ui.samplePlaceholder}>
          <ScanOutlined />
          <strong>
            {loading ? '正在读取样本' : isTimeSeries ? '时序样本' : '原始样本'}
          </strong>
          <span>
            {error
              ? '样本预览暂不可用，候选结果仍可查看'
              : '本轮所选数据 · AI 执行'}
          </span>
          {previewRows?.length ? (
            <div className={ui.rawRows}>
              {previewRows.map(({ key, text }) => (
                <code key={key}>{text}</code>
              ))}
            </div>
          ) : null}
        </div>
      )}
      {running && <div className={ui.scanLine} aria-hidden="true" />}
      <span className={ui.canvasBadge}>
        {running ? '分析中' : '原始数据预览'}
      </span>
      <div className={ui.canvasCorners} aria-hidden="true" />
    </div>
  );
}

export default function AiExecutionShowcase({
  project,
  samples,
  results,
  progress,
  running,
  modelName,
  executionStatus,
  instructions,
  versionNo,
  onReview,
}: {
  project: NativeProject;
  samples: NativeSample[];
  results: DemoResult[];
  progress: number;
  running: boolean;
  executionStatus?: string;
  modelName: string;
  instructions: string;
  versionNo?: number;
  onReview?: () => void;
}) {
  const [focusedId, setFocusedId] = useState('');
  const focused =
    samples.find((sample) => sample.sampleId === focusedId) || samples[0];
  const focusedIndex = focused ? samples.indexOf(focused) : -1;
  const stripStart = Math.floor(Math.max(focusedIndex, 0) / 6) * 6;
  const candidate = results.find(
    (row) => row.sample.sampleId === focused?.sampleId,
  );
  const success = results.filter((row) => row.status !== '执行失败');
  const failures = results.length - success.length;
  const scored = success.flatMap((row) =>
    row.confidence == null ? [] : [row.confidence],
  );
  const mean = scored.length
    ? scored.reduce((sum, score) => sum + score, 0) / scored.length
    : null;
  const high = scored.filter((score) => score >= 0.8).length;
  const labels = [...new Set(success.map((row) => row.label))];
  const statusText =
    (
      {
        QUEUED: '等待执行',
        RUNNING: '执行中',
        SUCCEEDED: '执行完成',
        PARTIAL: '部分完成',
        FAILED: '执行失败',
        CANCELLED: '已取消',
      } as Record<string, string>
    )[executionStatus || ''] ||
    executionStatus ||
    (running ? '执行中' : '执行已结束');
  const sampleName = (sample: NativeSample): string =>
    sample.metadata.displayName || sample.sourcePath;

  return (
    <div className={ui.showcase}>
      <section className={ui.mission} aria-label="AI 执行">
        <div className={ui.missionTop}>
          <span className={ui.consoleLabel}>
            <span className={ui.liveDot} data-running={running} />
            {statusText}
          </span>
          <span className={ui.modelName}>
            {modelName}
            <b>规范 v{versionNo || '—'}</b>
          </span>
        </div>
        <div className={ui.missionBody}>
          <section className={ui.inputPanel}>
            <div className={ui.panelHeading}>
              <span>01 / 样本输入</span>
              <small>{samples.length} 个样本</small>
            </div>
            <SamplePreview
              project={project}
              sample={focused}
              running={running}
            />
            <div className={ui.sampleCaption}>
              <span title={focused ? sampleName(focused) : ''}>
                {focused ? sampleName(focused) : '暂无样本'}
              </span>
              <small>当前聚焦样本</small>
            </div>
            <nav className={ui.sampleNavigation} aria-label="切换聚焦样本">
              <button
                type="button"
                disabled={focusedIndex <= 0}
                onClick={() => setFocusedId(samples[focusedIndex - 1].sampleId)}
              >
                <LeftOutlined />
                上一张
              </button>
              <span aria-live="polite">
                <strong>{focusedIndex + 1}</strong> / {samples.length}
              </span>
              <button
                type="button"
                disabled={
                  focusedIndex < 0 || focusedIndex >= samples.length - 1
                }
                onClick={() => setFocusedId(samples[focusedIndex + 1].sampleId)}
              >
                下一张
                <RightOutlined />
              </button>
            </nav>
            <div className={ui.sampleStrip}>
              {samples
                .slice(stripStart, stripStart + 6)
                .map((sample, offset) => {
                  const row = results.find(
                    (result) => result.sample.sampleId === sample.sampleId,
                  );
                  return (
                    <button
                      key={sample.sampleId}
                      type="button"
                      title={sampleName(sample)}
                      aria-pressed={sample.sampleId === focused?.sampleId}
                      onClick={() => setFocusedId(sample.sampleId)}
                    >
                      <strong>
                        {String(stripStart + offset + 1).padStart(2, '0')}
                      </strong>
                      <span>
                        {row?.status === '执行失败'
                          ? '失败'
                          : row
                            ? row.label
                            : '待生成'}
                      </span>
                    </button>
                  );
                })}
            </div>
          </section>

          <section className={ui.enginePanel}>
            <div className={ui.panelHeading}>
              <span>02 / 模型执行</span>
              <small>后台执行</small>
            </div>
            <div className={ui.engineTitle}>
              <ScanOutlined />
              <div>
                <strong>{statusText}</strong>
                <span>
                  {running
                    ? '进度按服务器返回的样本处理数更新'
                    : '已生成的候选可在结果明细中查看'}
                </span>
              </div>
            </div>
            <div className={ui.executionProgress}>
              <div>
                <span>已处理样本比例</span>
                <strong>
                  {progress}
                  <small>%</small>
                </strong>
              </div>
              <div className={ui.progressTrack}>
                <i style={{ width: `${progress}%` }} />
              </div>
            </div>
          </section>

          <section className={ui.outputPanel} aria-label="当前样本候选结果">
            <div className={ui.panelHeading}>
              <span>03 / 候选输出</span>
              <small>样本 {focusedIndex + 1}</small>
            </div>
            <span className={ui.outputKicker}>AI 候选标签</span>
            <strong className={ui.candidateLabel}>
              {candidate?.status === '执行失败'
                ? '执行失败'
                : candidate?.label || (running ? '等待结果' : '暂无候选')}
            </strong>
            <div
              className={ui.confidenceRing}
              style={
                {
                  '--confidence': `${(candidate?.confidence || 0) * 100}%`,
                } as CSSProperties
              }
            >
              <div>
                <strong>
                  {candidate?.confidence != null
                    ? (candidate.confidence * 100).toFixed(1)
                    : '—'}
                  <small>{candidate?.confidence != null ? '%' : ''}</small>
                </strong>
                <span>候选置信度</span>
              </div>
            </div>
            <div className={ui.outputNote}>
              <span />
              {candidate?.status === '执行失败'
                ? '该样本未生成候选，请在明细中查看'
                : candidate
                  ? '候选已生成，等待需求方核查'
                  : '执行过程中将逐步显示候选结果'}
            </div>
            <small className={ui.simulationNote}>
              模型未提供置信度时显示为未知
            </small>
          </section>
        </div>
        <section className={ui.resultSummary} aria-label="本轮结果概览">
          <div>
            <span>本轮样本</span>
            <strong>
              {samples.length}
              <small>个</small>
            </strong>
            <p>{results.length} 个已处理</p>
          </div>
          <div>
            <span>已生成候选</span>
            <strong>
              {success.length}
              <small>个</small>
            </strong>
            <p>{failures ? `${failures} 个执行失败` : '候选结果逐样本保留'}</p>
          </div>
          <div>
            <span>平均候选置信度</span>
            <strong>
              {mean == null ? '—' : (mean * 100).toFixed(1)}
              <small>{mean == null ? '' : '%'}</small>
            </strong>
            <p>仅统计有分数的候选</p>
          </div>
          <div>
            <span>高置信度候选</span>
            <strong>
              {high}
              <small>个</small>
            </strong>
            <p>候选置信度 ≥ 80%</p>
          </div>
        </section>
        <div className={ui.missionFooter}>
          <span>执行说明</span>
          <p>{instructions || '使用当前标注规范，生成本轮样本的候选标签。'}</p>
          <span>AI 执行 → 需求方核查</span>
        </div>
      </section>

      <footer className={ui.nextStep}>
        <div>
          <span>下一步 / 需求方核查</span>
          <strong>
            {running
              ? executionStatus === 'QUEUED'
                ? '任务已进入队列，等待执行。'
                : 'AI 正在处理样本。'
              : success.length
                ? '请核查已生成的候选结果。'
                : '本轮没有可核查的候选结果。'}
          </strong>
          <p>确认采用或筛出待修正样本，再决定下一轮执行方式。</p>
        </div>
        <button
          className={ui.reviewButton}
          type="button"
          disabled={running || !success.some((row) => row.reviewable)}
          onClick={onReview}
        >
          前往核查与重标
          <ArrowRightOutlined />
        </button>
      </footer>
      <details className={ui.resultDetails}>
        <summary>
          候选结果明细<span>{results.length} 个已处理样本 · 展开查看</span>
        </summary>
        <section className={ui.resultsSection}>
          <div className={ui.resultsHeading}>
            <div>
              <span className={ui.sectionIndex}>RESULTS /</span>
              <h2>每一个候选，都有迹可循</h2>
            </div>
            <span>点击样本名称，切换上方预览</span>
          </div>
          {labels.length > 0 && (
            <div className={ui.labelDistribution}>
              {labels.map((label, index) => {
                const count = success.filter(
                  (row) => row.label === label,
                ).length;
                return (
                  <div key={label}>
                    <span>
                      <i
                        style={{
                          background: [
                            '#2868eb',
                            '#19a58e',
                            '#ce8530',
                            '#8461cc',
                          ][index % 4],
                        }}
                      />
                      {label}
                      <b>{count}</b>
                    </span>
                    <div>
                      <i
                        style={{
                          width: `${(count / success.length) * 100}%`,
                          background: [
                            '#2868eb',
                            '#19a58e',
                            '#ce8530',
                            '#8461cc',
                          ][index % 4],
                        }}
                      />
                    </div>
                  </div>
                );
              })}
            </div>
          )}
          <Table<DemoResult>
            rowKey={(row) => row.sample.sampleId}
            dataSource={results}
            pagination={
              results.length > 8
                ? { pageSize: 8, showSizeChanger: false }
                : false
            }
            locale={{
              emptyText: running
                ? 'AI 正在处理样本，候选结果将在这里逐步出现。'
                : '本轮暂无候选结果。',
            }}
            scroll={{ x: 650 }}
            rowClassName={(row) =>
              row.sample.sampleId === focused?.sampleId ? ui.focusedRow : ''
            }
            columns={[
              {
                title: '样本',
                ellipsis: true,
                render: (_, row) => (
                  <button
                    className={ui.sampleLink}
                    type="button"
                    title={sampleName(row.sample)}
                    aria-pressed={row.sample.sampleId === focused?.sampleId}
                    onClick={() => setFocusedId(row.sample.sampleId)}
                  >
                    {sampleName(row.sample)}
                  </button>
                ),
              },
              {
                title: 'AI 候选标签',
                dataIndex: 'label',
                width: 160,
                render: (label: string) => <Tag color="blue">{label}</Tag>,
              },
              {
                title: '候选置信度',
                width: 240,
                render: (_, row) =>
                  row.confidence == null ? (
                    '—'
                  ) : (
                    <div className={ui.tableConfidence}>
                      <div>
                        <i style={{ width: `${row.confidence * 100}%` }} />
                      </div>
                      <strong>{(row.confidence * 100).toFixed(1)}%</strong>
                    </div>
                  ),
              },
              {
                title: '执行状态',
                width: 150,
                render: (_, row) =>
                  row.status === '执行失败' ? (
                    <Tag color="error">执行失败</Tag>
                  ) : (
                    <span className={ui.candidateStatus}>
                      <i />
                      候选已生成
                    </span>
                  ),
              },
            ]}
          />
        </section>
      </details>
    </div>
  );
}
