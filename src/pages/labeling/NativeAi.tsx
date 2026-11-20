import {
  Button,
  Card,
  Empty,
  Input,
  Select,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd';
import { useEffect, useMemo, useState } from 'react';
import type {
  NativeProject,
  NativeSample,
  NativeSpec,
} from '@/services/nativeLabeling';
import ui from './workspaceUi.less';
import AiExecutionShowcase from './AiExecutionShowcase';

export type DemoResult = {
  reason?: string;
  sample: NativeSample;
  label: string;
  confidence: number | null;
  status: '待核查' | '已确认' | '需重标' | '执行失败';
};

const taskTypeNames: Record<string, string> = {
  IMAGE_CLASSIFICATION: '图片分类',
  COMPUTER_VISION: '计算机视觉',
  TIME_SERIES: '时序分析',
  TIMESERIES_ANNOTATION: '时序标注',
};

export default function NativeAi({
  project,
  selectedSamples,
  version,
  execution,
  onPublish,
  onComplete,
  onReview,
}: {
  project: NativeProject;
  selectedSamples: NativeSample[];
  version?: NativeSpec;
  execution?: { modelId: string; instructions: string };
  onPublish?: (config: { modelId: string; instructions: string }) => void;
  onComplete?: (results: DemoResult[]) => void;
  onReview?: () => void;
}) {
  const [modelId, setModelId] = useState('demo-default');
  const [instructions, setInstructions] = useState('');
  const [results, setResults] = useState<DemoResult[]>([]);
  const scopeKey = useMemo(
    () => selectedSamples.map((sample) => sample.sampleId).join('|'),
    [selectedSamples],
  );
  const labels = version?.labels.map((label) => label.name).filter(Boolean) || [];
  const modelOptions =
    project.taskType === 'TIME_SERIES' ||
    project.taskType === 'TIMESERIES_ANNOTATION'
      ? [
          { value: 'demo-ts-1', label: '时序异常检测模型 · 演示版 1.2' },
          { value: 'demo-ts-2', label: '设备状态预测模型 · 演示版 0.9' },
        ]
      : [
          { value: 'demo-cv-1', label: '工业视觉质检模型 · 演示版 2.4' },
          { value: 'demo-cv-2', label: '通用图像识别模型 · 演示版 1.7' },
        ];
  useEffect(() => {
    setModelId(
      project.taskType === 'TIME_SERIES' ||
        project.taskType === 'TIMESERIES_ANNOTATION'
        ? 'demo-ts-1'
        : 'demo-cv-1',
    );
  }, [project.taskType]);

  function makeDemoResults(): DemoResult[] {
    const confidenceValues = [0.97, 0.91, 0.78, 0.64, 0.43, 0.86];
    return selectedSamples.map<DemoResult>((sample, index) => {
      const failed = index > 0 && index % 7 === 6;
      return {
        sample,
        label: failed
          ? '—'
          : labels[index % Math.max(labels.length, 1)] ||
            (index % 2 === 0 ? '正常' : '缺陷'),
        confidence: failed ? null : confidenceValues[index % confidenceValues.length],
        status: failed ? '执行失败' : '待核查',
      };
    });
  }

  useEffect(() => {
    if (!execution) return;
    const generated = makeDemoResults();
    setResults(generated);
    onComplete?.(generated);
  }, [execution, project.projectId, scopeKey, version?.specVersionId]);

  if (execution) {
    return <AiExecutionShowcase
      project={project}
      samples={selectedSamples}
      results={results}
      progress={results.length ? 100 : 0}
      running={false}
      modelName={modelOptions.find((model) => model.value === execution.modelId)?.label || execution.modelId}
      instructions={execution.instructions}
      versionNo={version?.versionNo}
      onReview={onReview}
    />;
  }

  return (
    <div className={ui.aiConsole}>
      <div className={ui.nativeAiGrid}>
        <Card className={ui.aiDemoCard} title="本轮执行范围" extra={<Tag color="blue">{selectedSamples.length} 个样本</Tag>}>
          {selectedSamples.length ? (
            <Table<NativeSample>
              rowKey="sampleId"
              size="small"
              dataSource={selectedSamples}
              pagination={{ pageSize: 5, showSizeChanger: false }}
              scroll={{ x: 420 }}
              columns={[
                {
                  title: '样本',
                  render: (_, sample) => sample.metadata.displayName || sample.sourcePath,
                  ellipsis: true,
                },
                { title: '类型', dataIndex: 'mediaType', width: 110, ellipsis: true },
              ]}
            />
          ) : (
            <Empty description="请先在上方选择本轮样本范围。" />
          )}
        </Card>

        <Card className={ui.aiDemoCard} title="执行配置">
          <Space direction="vertical" size="middle" style={{ width: '100%' }}>
            <div className={ui.aiModelBadge}>
              <span>任务类型</span>
              <strong>{taskTypeNames[project.taskType] || project.taskType}</strong>
              <small>规范 v{version?.versionNo || '—'}</small>
            </div>
            <Select
              aria-label="选择演示模型"
              value={modelId === 'demo-default' ? modelOptions[0].value : modelId}
              onChange={setModelId}
              options={modelOptions}
            />
            <Input.TextArea
              aria-label="本轮 AI 执行说明"
              placeholder="本轮执行说明（可选）"
              value={instructions}
              onChange={(event) => setInstructions(event.target.value)}
              maxLength={500}
              rows={3}
            />
            <Button
              className={ui.aiDemoCta}
              type="primary"
              block
              disabled={!selectedSamples.length}
              onClick={() => onPublish?.({ modelId: modelId === 'demo-default' ? modelOptions[0].value : modelId, instructions })}
            >
              <span style={{ color: '#fff', WebkitTextFillColor: '#fff' }}>确认发布并查看 AI 演示结果</span>
            </Button>
            <Typography.Text type="secondary">
              当前为前端演示，发布后模拟执行，不会创建真实 AI 任务。
            </Typography.Text>
          </Space>
        </Card>
      </div>
    </div>
  );
}
