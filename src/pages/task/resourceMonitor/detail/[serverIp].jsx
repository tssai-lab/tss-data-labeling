import { CloseOutlined } from '@ant-design/icons';
import { PageContainer } from '@ant-design/pro-components';
import { history, useAccess, useParams } from '@umijs/max';
import {
  Alert,
  Button,
  Card,
  Col,
  Descriptions,
  Empty,
  message,
  Popconfirm,
  Progress,
  Row,
  Segmented,
  Space,
  Spin,
  Statistic,
  Switch,
  Table,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import dayjs from 'dayjs';
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  cancelResourceQueueTask,
  deleteResourceMonitorServer,
  fetchResourceMonitorMetrics,
  fetchResourceMonitorServerDetail,
  updateResourceMonitorServerEnabled,
} from '@/services/platform';
import {
  getIntervalSpanLabel,
  getUsageColor,
  TIME_INTERVAL_OPTIONS,
} from '../constants';
import { formatMetric, isMetricAvailable } from '../metricDisplay.mjs';
import { getMetricsStatusMeta, getNodeWarnings } from '../monitorStatus.mjs';
import ResourceTrendChart from '../ResourceTrendChart';

const { Text } = Typography;

const ServerDetail = () => {
  const { canManageResourceNodes, canManageResourceQueue } = useAccess();
  const { serverIp: encodedIp } = useParams();
  const serverIp = decodeURIComponent(encodedIp || '');
  const [server, setServer] = useState(null);
  const [queuedTasks, setQueuedTasks] = useState([]);
  const [historyData, setHistoryData] = useState([]);
  const [historyMetricsState, setHistoryMetricsState] = useState(null);
  const [timeInterval, setTimeInterval] = useState('1hour');
  const [loading, setLoading] = useState(true);
  const [detailError, setDetailError] = useState('');

  const loadMetrics = useCallback(
    async (interval) => {
      const metricsRes = await fetchResourceMonitorMetrics(serverIp, interval);
      if (!metricsRes?.success) {
        setHistoryMetricsState({
          metricsStatus: 'unavailable',
          metricsMessage: metricsRes?.errorMessage || '趋势指标读取失败',
        });
        message.error(metricsRes?.errorMessage || '加载趋势数据失败');
        return;
      }
      setHistoryData(metricsRes.data?.points ?? []);
      setHistoryMetricsState(metricsRes.data ?? null);
    },
    [serverIp],
  );

  const loadServerDetail = useCallback(async () => {
    setLoading(true);
    setDetailError('');
    try {
      const detailRes = await fetchResourceMonitorServerDetail(serverIp);
      if (!detailRes?.success) {
        setServer(null);
        setQueuedTasks([]);
        setDetailError(detailRes?.errorMessage || '服务器详情读取失败');
        return;
      }
      setServer(detailRes.data);
      setQueuedTasks(
        detailRes.data?.queuedTasks ? [...detailRes.data.queuedTasks] : [],
      );
    } finally {
      setLoading(false);
    }
  }, [serverIp]);

  useEffect(() => {
    if (serverIp) {
      loadServerDetail();
    }
  }, [serverIp, loadServerDetail]);

  useEffect(() => {
    if (serverIp) {
      loadMetrics(timeInterval);
    }
  }, [serverIp, timeInterval, loadMetrics]);

  const handleCancelQueueTask = useCallback(
    async (taskId) => {
      const res = await cancelResourceQueueTask(serverIp, taskId);
      if (!res?.success) {
        message.error(res?.errorMessage || '取消失败');
        return;
      }
      setQueuedTasks(res.data?.queuedTasks ?? []);
      message.success('已取消排队');
    },
    [serverIp],
  );

  const handleDeleteServer = async () => {
    const res = await deleteResourceMonitorServer(serverIp);
    if (!res?.success) {
      message.error(res?.errorMessage || '删除失败');
      return;
    }
    message.success('服务器已删除');
    history.push('/task/resourceMonitor');
  };

  const handleToggleEnabled = async (enabled) => {
    const res = await updateResourceMonitorServerEnabled(serverIp, enabled);
    if (!res?.success) {
      message.error(res?.errorMessage || '操作失败');
      return;
    }
    if (res.data) setServer(res.data);
    message.success(
      enabled ? '服务器已启用' : '服务器已禁用（不再分配新任务）',
    );
  };

  const runningColumns = useMemo(
    () => [
      { title: '任务名称', dataIndex: 'name', key: 'name', ellipsis: true },
      { title: '模型', dataIndex: 'model', key: 'model', width: 120 },
      { title: '数据集', dataIndex: 'dataset', key: 'dataset', width: 120 },
      {
        title: '开始时间',
        dataIndex: 'startTime',
        key: 'startTime',
        width: 170,
      },
      {
        title: '进度',
        dataIndex: 'progress',
        key: 'progress',
        width: 140,
        render: (val) => <Progress percent={val} size="small" />,
      },
      {
        title: 'CPU',
        dataIndex: 'cpuUsage',
        key: 'cpuUsage',
        width: 80,
        render: (val) => `${val}%`,
      },
      {
        title: '内存',
        dataIndex: 'memUsage',
        key: 'memUsage',
        width: 90,
        render: (val) => `${val} GB`,
      },
      {
        title: 'GPU',
        dataIndex: 'gpuUsage',
        key: 'gpuUsage',
        width: 80,
        render: (val) => `${val}%`,
      },
      {
        title: '操作',
        key: 'action',
        width: 90,
        render: (_, record) => (
          <a onClick={() => history.push(`/task/detail/${record.id}`)}>详情</a>
        ),
      },
    ],
    [],
  );

  const queuedColumns = useMemo(() => {
    const columns = [
      {
        title: '序号',
        key: 'queueOrder',
        width: 90,
        render: (_, __, index) => <Text strong>{index + 1}</Text>,
      },
      { title: '任务名称', dataIndex: 'name', key: 'name', ellipsis: true },
      { title: '模型', dataIndex: 'model', key: 'model', width: 120 },
      { title: '数据集', dataIndex: 'dataset', key: 'dataset', width: 120 },
      {
        title: '提交时间',
        dataIndex: 'submitTime',
        key: 'submitTime',
        width: 170,
      },
      {
        title: '优先级',
        dataIndex: 'priority',
        key: 'priority',
        width: 90,
        render: (val) => {
          const colorMap = { 高: 'red', 中: 'orange', 低: 'default' };
          return <Tag color={colorMap[val]}>{val}</Tag>;
        },
      },
    ];

    if (canManageResourceQueue) {
      columns.push({
        title: '操作',
        key: 'action',
        width: 80,
        align: 'center',
        render: (_, record) => (
          <Popconfirm
            title="确认取消该任务的排队？"
            description="取消后该任务将不会启动（已调度待启动任务）。"
            onConfirm={() => handleCancelQueueTask(record.id)}
          >
            <Tooltip title="取消排队（阻止启动）">
              <Button
                type="text"
                size="small"
                icon={<CloseOutlined style={{ color: '#ff4d4f' }} />}
              />
            </Tooltip>
          </Popconfirm>
        ),
      });
    }

    return columns;
  }, [canManageResourceQueue, handleCancelQueueTask]);

  if (loading) {
    return (
      <PageContainer
        title="服务器详情"
        onBack={() => history.push('/task/resourceMonitor')}
      >
        <div style={{ textAlign: 'center', padding: 80 }}>
          <Spin size="large" />
        </div>
      </PageContainer>
    );
  }

  if (!server) {
    return (
      <PageContainer
        title="服务器详情"
        onBack={() => history.push('/task/resourceMonitor')}
      >
        {detailError ? (
          <Alert
            type="error"
            showIcon
            message="真实监控数据读取失败"
            description={detailError}
          />
        ) : (
          <Empty description="未找到该服务器" />
        )}
      </PageContainer>
    );
  }

  const usageItems = [
    { label: 'CPU 使用率', value: server.cpuRate },
    { label: '内存使用率', value: server.memRate },
    { label: 'GPU 使用率', value: server.gpuRate },
    { label: 'GPU 显存', value: server.gpuMemRate },
    { label: '磁盘使用率', value: server.diskRate },
  ];
  const metricsMeta = getMetricsStatusMeta(server.metricsStatus);
  const nodeWarnings = getNodeWarnings(server);
  const historyMetricsMeta = historyMetricsState
    ? getMetricsStatusMeta(historyMetricsState.metricsStatus)
    : null;

  return (
    <PageContainer
      title={`服务器：${server.serverIp}`}
      subTitle={server.hostname}
      onBack={() => history.push('/task/resourceMonitor')}
      extra={
        <Space>
          <Tag color={server.status === 'online' ? 'success' : 'warning'}>
            {server.status === 'online' ? '在线' : '告警'}
          </Tag>
          {canManageResourceNodes && (
            <Tooltip
              title={
                server.enabled === false
                  ? '已禁用，点击启用恢复参与调度'
                  : '已启用，点击禁用后不再分配新任务'
              }
            >
              <Switch
                checked={server.enabled !== false}
                checkedChildren="启用"
                unCheckedChildren="禁用"
                onChange={handleToggleEnabled}
              />
            </Tooltip>
          )}
          {canManageResourceNodes && (
            <Popconfirm
              title="确认删除该服务器？"
              description={
                server.runTask > 0
                  ? '该服务器有运行中任务，无法删除。'
                  : '删除后将不再纳入监控，当前排队任务将一并清除。'
              }
              okText="删除"
              okButtonProps={{ danger: true, disabled: server.runTask > 0 }}
              cancelText="取消"
              onConfirm={handleDeleteServer}
            >
              <Button danger disabled={server.runTask > 0}>
                删除服务器
              </Button>
            </Popconfirm>
          )}
        </Space>
      }
    >
      {server.enabled === false && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 16 }}
          message="该服务器已禁用，不会分配新的训练任务"
          description="已在其上运行的任务不受影响；如需恢复，请点击右上角开关启用。"
        />
      )}
      {server.metricsStatus !== 'fresh' && (
        <Alert
          type={metricsMeta.alertType}
          showIcon
          style={{ marginBottom: 16 }}
          message={`指标状态：${metricsMeta.label}`}
          description={`${server.metricsMessage || '当前不是实时指标'}；最近成功：${
            server.metricsLastSuccessAt
              ? dayjs(server.metricsLastSuccessAt).format('YYYY-MM-DD HH:mm:ss')
              : '从未成功'
          }`}
        />
      )}
      {nodeWarnings.length > 0 && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 16 }}
          message="Kubernetes 节点状态异常"
          description={nodeWarnings.join('；')}
        />
      )}
      <Card title="硬件信息" style={{ marginBottom: 16 }}>
        <Descriptions column={4}>
          <Descriptions.Item label="主机名">
            {server.hostname || '-'}
          </Descriptions.Item>
          <Descriptions.Item label="CPU">
            {server.specs?.cpu || '-'}
          </Descriptions.Item>
          <Descriptions.Item label="内存">
            {server.specs?.memory || '-'}
          </Descriptions.Item>
          <Descriptions.Item label="GPU">
            {server.specs?.gpu || '-'}
          </Descriptions.Item>
          <Descriptions.Item label="操作系统">
            {server.specs?.os || '-'}
          </Descriptions.Item>
          <Descriptions.Item label="GPU 温度">
            {formatMetric(server.gpuTemp, ' °C')}
          </Descriptions.Item>
          <Descriptions.Item label="网络入站">
            {formatMetric(server.networkIn, ' MB/s')}
          </Descriptions.Item>
          <Descriptions.Item label="网络出站">
            {formatMetric(server.networkOut, ' MB/s')}
          </Descriptions.Item>
          <Descriptions.Item label="节点 Ready">
            {server.nodeReady === true
              ? '是'
              : server.nodeReady === false
                ? '否'
                : '未知'}
          </Descriptions.Item>
          <Descriptions.Item label="调度状态">
            {server.nodeUnschedulable === true
              ? '禁止调度'
              : server.nodeUnschedulable === false
                ? '可调度'
                : '未知'}
          </Descriptions.Item>
        </Descriptions>
      </Card>

      {!!server.gpuDevices?.length && (
        <Card title="物理 GPU 明细" style={{ marginBottom: 16 }}>
          <Table
            rowKey={(item) => item.uuid || item.hostGpuIndex}
            pagination={false}
            dataSource={server.gpuDevices}
            columns={[
              {
                title: '宿主机编号',
                dataIndex: 'hostGpuIndex',
                render: (value) => (value === null || value === undefined ? '-' : `GPU ${value}`),
              },
              { title: '型号', dataIndex: 'model', render: (value) => value || '-' },
              { title: 'UUID（绑定依据）', dataIndex: 'uuid', render: (value) => value || '-' },
              {
                title: '使用率',
                dataIndex: 'utilizationRate',
                render: (value) => formatMetric(value, '%'),
              },
              {
                title: '空闲 / 总显存',
                render: (_, item) => `${item.freeMemoryMiB ?? '-'} / ${item.totalMemoryMiB ?? '-'} MiB`,
              },
              {
                title: '温度',
                dataIndex: 'temperatureCelsius',
                render: (value) => formatMetric(value, ' °C'),
              },
            ]}
          />
        </Card>
      )}

      <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
        {usageItems.map((item) => (
          <Col xs={24} sm={12} md={8} lg={4} key={item.label}>
            <Card size="small">
              <Text type="secondary">{item.label}</Text>
              <Progress
                percent={isMetricAvailable(item.value) ? item.value : 0}
                strokeColor={
                  isMetricAvailable(item.value)
                    ? getUsageColor(item.value)
                    : '#d9d9d9'
                }
                format={() => formatMetric(item.value, '%')}
                style={{ marginTop: 8 }}
              />
            </Card>
          </Col>
        ))}
      </Row>

      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col span={12}>
          <Card>
            <Statistic
              title="平台运行中任务"
              value={server.runTask}
              suffix="个"
            />
          </Card>
        </Col>
        <Col span={12}>
          <Card>
            <Statistic
              title="平台待启动任务"
              value={server.waitTask}
              suffix="个"
            />
          </Card>
        </Col>
      </Row>

      <Card
        title="资源使用趋势"
        extra={
          <Space size="middle">
            <Text type="secondary" style={{ fontSize: 12 }}>
              {getIntervalSpanLabel(timeInterval)}
            </Text>
            <Segmented
              value={timeInterval}
              onChange={setTimeInterval}
              options={TIME_INTERVAL_OPTIONS}
            />
          </Space>
        }
        style={{ marginBottom: 16 }}
      >
        {historyMetricsState &&
          historyMetricsState.metricsStatus !== 'fresh' && (
            <Alert
              type={historyMetricsMeta?.alertType || 'warning'}
              showIcon
              style={{ marginBottom: 16 }}
              message={
                historyMetricsState.metricsMessage || '趋势数据当前不是实时值'
              }
            />
          )}
        <ResourceTrendChart
          data={historyData}
          height={400}
          showSlider
          interval={timeInterval}
        />
      </Card>

      {canManageResourceQueue ? (
        <>
          <Card
            title={`正在运行的任务（${server.runningTasks?.length ?? 0}）`}
            style={{ marginBottom: 16 }}
          >
            <Table
              rowKey="id"
              columns={runningColumns}
              dataSource={server.runningTasks}
              pagination={false}
              locale={{ emptyText: '当前无运行中任务' }}
              scroll={{ x: 1100 }}
            />
          </Card>

          <Card
            title={`已调度待启动的任务（${queuedTasks.length}）`}
            extra={
              <Text type="secondary" style={{ fontSize: 12 }}>
                已绑定节点、等待启动；启动顺序由调度器按优先级与提交时间决定
              </Text>
            }
          >
            <Table
              rowKey="id"
              columns={queuedColumns}
              dataSource={queuedTasks}
              pagination={false}
              locale={{ emptyText: '当前无待启动任务' }}
            />
          </Card>
        </>
      ) : (
        <Alert
          type="info"
          showIcon
          message="任务明细仅超级管理员可查看"
          description="当前账号仍可查看节点状态、资源指标和任务数量汇总，但不会显示其他用户的任务名称、模型或数据集。"
        />
      )}
    </PageContainer>
  );
};

export default ServerDetail;
