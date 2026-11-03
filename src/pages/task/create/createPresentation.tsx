import { Alert, Space, Tag, Typography } from 'antd';
import React from 'react';
import type { TrainingPlan } from '@/services/trainingPlans';
export const FUSION_HYPER_PARAMS_DEFAULT = {
  model: 'logreg',
  threshold: 0.5,
  outputDir: 'outputs/fusion_baseline_logreg',
};

export type CheckState = {
  loading: boolean;
  passed?: boolean;
  reasons?: string[];
  approvalStatus?: string;
  validationStatus?: string;
};

export const isCodeApproved = (status?: string) => status === 'APPROVED';

const FORMAT_LABELS: Record<string, string> = {
  FOLDER_CLASSIFICATION: '文件夹分类式（每个子目录一个类别）',
  HF_MODEL_ARCHIVE: 'HuggingFace 模型包',
  LEGACY_WEIGHT_ARCHIVE: '权重文件包（兼容）',
  OTHER: '其他',
  WEIGHT_ARCHIVE: '权重文件包',
  YOLO: 'YOLO 标注格式',
};

const friendlyFormat = (f: string) => FORMAT_LABELS[f] || f;

const PRE_STYLE: React.CSSProperties = {
  background: '#f6f8fa',
  padding: 12,
  borderRadius: 6,
  fontSize: 12,
  margin: 0,
  overflowX: 'auto',
  whiteSpace: 'pre-wrap',
};

export const PlanFormatHint: React.FC<{ plan: TrainingPlan }> = ({ plan }) => {
  const dataset = plan.inputs?.dataset;
  const model = plan.inputs?.model;
  const code = plan.inputs?.code;
  const entrypoint = plan.execution?.entrypoint;
  const params = plan.parameters ?? [];

  return (
    <Alert
      type="info"
      showIcon
      style={{ marginBottom: 16 }}
      message={
        <Space direction="vertical" size={16} style={{ width: '100%' }}>
          {plan.description ? (
            <Typography.Paragraph style={{ marginBottom: 0 }}>
              {plan.description}
            </Typography.Paragraph>
          ) : null}

          <div>
            <Typography.Text strong>数据集要求</Typography.Text>
            <div style={{ marginTop: 8 }}>
              {dataset?.acceptedSpecIds?.length ? (
                <Space direction="vertical" size={6} style={{ width: '100%' }}>
                  <Typography.Text type="secondary">
                    平台会自动筛选兼容的数据集
                  </Typography.Text>
                  {dataset.formatGuide ? (
                    <pre style={PRE_STYLE}>{dataset.formatGuide.trim()}</pre>
                  ) : null}
                </Space>
              ) : dataset?.requiredEntries?.length ? (
                <Space direction="vertical" size={6} style={{ width: '100%' }}>
                  <Typography.Text>压缩包内必须包含以下路径：</Typography.Text>
                  <Space wrap>
                    {dataset.requiredEntries.map((e) => (
                      <Tag key={e} color="blue">
                        {e}
                      </Tag>
                    ))}
                  </Space>
                  {dataset.annotationFormats?.length ? (
                    <Typography.Text type="secondary">
                      标注格式：
                      {dataset.annotationFormats.map(friendlyFormat).join('、')}
                    </Typography.Text>
                  ) : null}
                  {dataset.formatGuide ? (
                    <pre style={PRE_STYLE}>{dataset.formatGuide.trim()}</pre>
                  ) : null}
                </Space>
              ) : (
                <Typography.Text type="secondary">
                  无特殊目录要求
                </Typography.Text>
              )}
            </div>
          </div>

          <div>
            <Typography.Text strong>模型要求</Typography.Text>
            <div style={{ marginTop: 8 }}>
              {model?.acceptedSpecIds?.length ? (
                <Space direction="vertical" size={6} style={{ width: '100%' }}>
                  <Typography.Text type="secondary">
                    平台会自动筛选兼容的模型
                  </Typography.Text>
                  {model.formatGuide ? (
                    <pre style={PRE_STYLE}>{model.formatGuide.trim()}</pre>
                  ) : null}
                </Space>
              ) : model?.requiredEntries?.length ? (
                <Space direction="vertical" size={6} style={{ width: '100%' }}>
                  <Typography.Text>压缩包内必须包含以下文件：</Typography.Text>
                  <Space wrap>
                    {model.requiredEntries.map((e) => (
                      <Tag key={e} color="purple">
                        {e}
                      </Tag>
                    ))}
                  </Space>
                  {model.formats?.length ? (
                    <Typography.Text type="secondary">
                      格式：{model.formats.map(friendlyFormat).join('、')}
                    </Typography.Text>
                  ) : null}
                  {model.formatGuide ? (
                    <pre style={PRE_STYLE}>{model.formatGuide.trim()}</pre>
                  ) : null}
                </Space>
              ) : (
                <Typography.Text type="secondary">
                  无特殊条目要求
                </Typography.Text>
              )}
            </div>
          </div>

          <div>
            <Typography.Text strong>训练代码要求</Typography.Text>
            <div style={{ marginTop: 8 }}>
              <Space direction="vertical" size={4}>
                <Space>
                  <Typography.Text>入口脚本必须为：</Typography.Text>
                  {entrypoint ? <Tag color="cyan">{entrypoint}</Tag> : null}
                </Space>
                {code?.runtime ? (
                  <Typography.Text type="secondary">
                    运行时：{code.runtime}
                  </Typography.Text>
                ) : null}
                {code?.approvalRequired ? (
                  <Typography.Text type="warning">
                    上传后需管理员审核通过方可使用
                  </Typography.Text>
                ) : null}
              </Space>
            </div>
          </div>

          {params.length ? (
            <div>
              <Typography.Text strong>训练参数</Typography.Text>
              <div style={{ marginTop: 8 }}>
                <Space direction="vertical" size={8} style={{ width: '100%' }}>
                  {params.map((p) => (
                    <div key={p.name}>
                      <Space wrap>
                        <Tag color="geekblue">{p.displayName || p.name}</Tag>
                        {p.defaultValue != null ? (
                          <Typography.Text type="secondary">
                            默认 {String(p.defaultValue)}
                          </Typography.Text>
                        ) : null}
                        {p.required ? (
                          <Typography.Text type="danger">必填</Typography.Text>
                        ) : null}
                      </Space>
                      {p.description ? (
                        <Typography.Paragraph
                          type="secondary"
                          style={{ margin: '2px 0 0', fontSize: 12 }}
                        >
                          {p.description}
                        </Typography.Paragraph>
                      ) : null}
                    </div>
                  ))}
                </Space>
              </div>
            </div>
          ) : null}
        </Space>
      }
    />
  );
};
