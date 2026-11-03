import { RocketOutlined } from '@ant-design/icons';
import { Button, Card, Space, Typography } from 'antd';
import React from 'react';
import { useGuide } from './GuideContext';

const { Paragraph, Text } = Typography;

/** 首页引导入口卡片：开始引导 */
const GuideEntryCard: React.FC = () => {
  const { start } = useGuide();

  return (
    <Card
      size="small"
      data-tour="guide-entry"
      style={{ marginBottom: 12 }}
      styles={{ body: { padding: '14px 16px' } }}
    >
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          flexWrap: 'wrap',
          gap: 12,
        }}
      >
        <div style={{ flex: '1 1 280px', minWidth: 0 }}>
          <Paragraph style={{ marginBottom: 4, fontWeight: 600 }}>
            🚀 新用户快速上手
          </Paragraph>
          <Text type="secondary" style={{ fontSize: 13 }}>
            了解「资产上传 → 训练发起 → 推理发起」完整流程。引导只讲解不提交；
            系统已为你准备默认资产（见「用户手册」），可直接用于训练与推理。
          </Text>
        </div>
        <Space wrap>
          <Button
            type="primary"
            icon={<RocketOutlined />}
            onClick={() => start()}
          >
            开始引导
          </Button>
        </Space>
      </div>
    </Card>
  );
};

export default GuideEntryCard;
