import { PageContainer } from '@ant-design/pro-components';
import { history, useParams } from '@umijs/max';
import { Card, Empty } from 'antd';
import React from 'react';
import DatasetPreviewPanel from '../components/DatasetPreviewPanel';

const DatasetPreviewPage: React.FC = () => {
  const { versionId } = useParams<{ versionId: string }>();

  if (!versionId) {
    return (
      <PageContainer title="数据集预览" onBack={() => history.back()}>
        <Empty description="缺少数据集版本 ID" />
      </PageContainer>
    );
  }

  return (
    <PageContainer title="数据集预览" onBack={() => history.back()}>
      <Card title="文件预览">
        <DatasetPreviewPanel versionId={versionId} />
      </Card>
    </PageContainer>
  );
};

export default DatasetPreviewPage;
