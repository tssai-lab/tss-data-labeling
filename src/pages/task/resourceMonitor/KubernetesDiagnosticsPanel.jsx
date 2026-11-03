import { ReloadOutlined } from '@ant-design/icons';
import { Alert, Button, Card, Space, Table, Tag, Typography } from 'antd';
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { fetchKubernetesDiagnostics } from '@/services/platform';

const { Text } = Typography;

const KubernetesDiagnosticsPanel = () => {
  const [diagnostics, setDiagnostics] = useState(null);
  const [loading, setLoading] = useState(false);
  const [errorMessage, setErrorMessage] = useState('');

  const loadDiagnostics = useCallback(async () => {
    setLoading(true);
    setErrorMessage('');
    try {
      const response = await fetchKubernetesDiagnostics({
        skipErrorHandler: true,
      });
      if (!response?.success || !response.data) {
        setDiagnostics(null);
        setErrorMessage(response?.errorMessage || 'Kubernetes 诊断读取失败');
        return;
      }
      setDiagnostics(response.data);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadDiagnostics();
  }, [loadDiagnostics]);

  const nodeColumns = useMemo(
    () => [
      { title: '节点', dataIndex: 'name', key: 'name' },
      {
        title: 'Ready',
        dataIndex: 'ready',
        key: 'ready',
        width: 100,
        render: (ready) => (
          <Tag
            color={
              ready === true ? 'success' : ready === false ? 'error' : 'default'
            }
          >
            {ready === true ? '是' : ready === false ? '否' : '未知'}
          </Tag>
        ),
      },
      {
        title: '调度',
        dataIndex: 'unschedulable',
        key: 'unschedulable',
        width: 120,
        render: (unschedulable) => (
          <Tag
            color={
              unschedulable === true
                ? 'warning'
                : unschedulable === false
                  ? 'success'
                  : 'default'
            }
          >
            {unschedulable === true
              ? '禁止调度'
              : unschedulable === false
                ? '可调度'
                : '未知'}
          </Tag>
        ),
      },
      { title: '状态说明', dataIndex: 'message', key: 'message' },
    ],
    [],
  );

  const issueColumns = useMemo(
    () => [
      {
        title: '命名空间',
        dataIndex: 'namespace',
        key: 'namespace',
        width: 130,
      },
      { title: 'Pod', dataIndex: 'podName', key: 'podName', ellipsis: true },
      { title: '节点', dataIndex: 'nodeName', key: 'nodeName', width: 130 },
      {
        title: '容器',
        dataIndex: 'containerName',
        key: 'containerName',
        width: 140,
      },
      {
        title: '原因',
        dataIndex: 'reason',
        key: 'reason',
        width: 150,
        render: (reason) => <Tag color="error">{reason}</Tag>,
      },
      {
        title: '现场摘要',
        dataIndex: 'message',
        key: 'message',
        ellipsis: true,
      },
    ],
    [],
  );

  const imageColumns = useMemo(
    () => [
      {
        title: '类型',
        dataIndex: 'workloadType',
        key: 'workloadType',
        width: 100,
      },
      { title: 'Pod', dataIndex: 'podName', key: 'podName', ellipsis: true },
      {
        title: '容器',
        dataIndex: 'containerName',
        key: 'containerName',
        width: 150,
      },
      {
        title: '声明镜像',
        dataIndex: 'declaredImage',
        key: 'declaredImage',
        ellipsis: true,
      },
      {
        title: '实际 image ID',
        dataIndex: 'imageId',
        key: 'imageId',
        ellipsis: true,
      },
      {
        title: '配置一致',
        dataIndex: 'configuredInferenceImageMatch',
        key: 'configuredInferenceImageMatch',
        width: 100,
        render: (matched) =>
          matched == null ? (
            '-'
          ) : (
            <Tag color={matched ? 'success' : 'error'}>
              {matched ? '是' : '否'}
            </Tag>
          ),
      },
    ],
    [],
  );

  return (
    <Card
      title="Kubernetes 故障与镜像诊断（仅超级管理员）"
      loading={loading}
      extra={
        <Button icon={<ReloadOutlined />} onClick={loadDiagnostics}>
          刷新诊断
        </Button>
      }
    >
      {errorMessage && <Alert type="error" showIcon message={errorMessage} />}
      {diagnostics && (
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          <Alert
            showIcon
            type={
              diagnostics.collectionStatus === 'healthy' ? 'success' : 'warning'
            }
            message={diagnostics.message || 'Kubernetes 诊断状态'}
            description={
              <Text type="secondary">
                当前配置的推理镜像：
                {diagnostics.configuredInferenceImage || '-'}
              </Text>
            }
          />
          <Table
            size="small"
            rowKey="name"
            columns={nodeColumns}
            dataSource={diagnostics.nodes || []}
            pagination={false}
          />
          <Table
            size="small"
            rowKey={(row) =>
              `${row.namespace}/${row.podName}/${row.containerType}/${row.containerName || 'pod'}/${row.reason}`
            }
            columns={issueColumns}
            dataSource={diagnostics.podIssues || []}
            pagination={false}
            locale={{ emptyText: '当前未发现异常 Pod' }}
            scroll={{ x: 900 }}
          />
          <Table
            size="small"
            rowKey={(row) =>
              `${row.namespace}/${row.podName}/${row.containerType}/${row.containerName}`
            }
            columns={imageColumns}
            dataSource={diagnostics.workloadImages || []}
            pagination={false}
            locale={{ emptyText: '当前没有存活的训练/推理 Pod 可核对镜像' }}
            scroll={{ x: 1000 }}
          />
        </Space>
      )}
    </Card>
  );
};

export default KubernetesDiagnosticsPanel;
