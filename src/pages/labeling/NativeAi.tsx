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
import { useEffect, useState } from 'react';
import type {
  NativeProject,
  NativeSample,
  NativeSpec,
} from '@/services/nativeLabeling';
import AiExecutionShowcase from './AiExecutionShowcase';
import ui from './workspaceUi.less';

export type DemoResult = {
  reason?: string;
  sample: NativeSample;
  label: string;
  confidence: number | null;
  reviewable?: boolean;
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
  results = [],
  progress = 0,
  running = false,
  submitting = false,
  onReview,
}: {
  project: NativeProject;
  selectedSamples: NativeSample[];
  version?: NativeSpec;
  execution?: { modelId: string; instructions: string };
  onPublish?: (config: { modelId: string; instructions: string }) => void;
  results?: DemoResult[];
  progress?: number;
  running?: boolean;
  submitting?: boolean;
  onReview?: () => void;
}) {
  const [modelId, setModelId] = useState('');
  const [instructions, setInstructions] = useState('');
  const modelOptions =
    project.taskType === 'TIME_SERIES' ||
    project.taskType === 'TIMESERIES_ANNOTATION'
      ? [{ value: 'qwen-timeseries', label: 'Qwen3-VL · 能耗时序' }]
      : [
          { value: 'qwen-image', label: 'Qwen3-VL · 图像分类' },
          { value: 'yolo-pcb', label: 'YOLO · PCB 缺陷检测' },
        ];
  useEffect(() => {
    setModelId(
      project.taskType === 'TIME_SERIES' ||
        project.taskType === 'TIMESERIES_ANNOTATION'
        ? 'qwen-timeseries'
        : 'qwen-image',
    );
  }, [project.taskType]);

  if (execution) {
    return (
      <AiExecutionShowcase
        project={project}
        samples={selectedSamples}
        results={results}
        progress={progress}
        running={running}
        modelName={
          modelOptions.find((model) => model.value === execution.modelId)
            ?.label || execution.modelId
        }
        instructions={execution.instructions}
        versionNo={version?.versionNo}
        onReview={onReview}
      />
    );
  }

  return (
    <div className={ui.aiConsole}>
      <div className={ui.nativeAiGrid}>
        <Card
          className={ui.aiDemoCard}
          title="本轮执行范围"
          extra={<Tag color="blue">{selectedSamples.length} 个样本</Tag>}
        >
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
                  render: (_, sample) =>
                    sample.metadata.displayName || sample.sourcePath,
                  ellipsis: true,
                },
                {
                  title: '类型',
                  dataIndex: 'mediaType',
                  width: 110,
                  ellipsis: true,
                },
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
              <strong>
                {taskTypeNames[project.taskType] || project.taskType}
              </strong>
              <small>规范 v{version?.versionNo || '—'}</small>
            </div>
            <Select
              aria-label="选择预标注模型"
              value={modelId || modelOptions[0].value}
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
              disabled={submitting || !selectedSamples.length}
              loading={submitting}
              onClick={() =>
                onPublish?.({
                  modelId: modelId || modelOptions[0].value,
                  instructions,
                })
              }
            >
              <span style={{ color: '#fff', WebkitTextFillColor: '#fff' }}>
                确认发布并查看 AI 结果
              </span>
            </Button>
            <Typography.Text type="secondary">
              提交后在后台生成候选，可在执行页查看进度。
            </Typography.Text>
          </Space>
        </Card>
      </div>
    </div>
  );
}
