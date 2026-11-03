import {
  ArrowDownOutlined,
  ArrowUpOutlined,
  CloseOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import { PageContainer } from '@ant-design/pro-components';
import { useAccess } from '@umijs/max';
import type { TableColumnsType } from 'antd';
import {
  Button,
  Card,
  Empty,
  message,
  Popconfirm,
  Space,
  Table,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  cancelResourceMonitorGlobalQueueTask,
  fetchResourceMonitorGlobalQueue,
  reorderResourceMonitorGlobalQueue,
} from '@/services/resourceMonitor';

const { Text } = Typography;

/** 池名展示：已知池给友好名称，新增池（如 h100）自动展示为「xxx 池」 */
const POOL_NAME_LABEL: Record<string, string> = {
  cpu: 'CPU 池',
  gpu: 'GPU 池',
  custom: '自定义标签',
};
const getPoolLabel = (pool: string) => POOL_NAME_LABEL[pool] || `${pool} 池`;

const STATUS_TAG = {
  queued: { color: 'blue', label: '排队中' },
  pending: { color: 'default', label: '待调度' },
};

const QueuePage = () => {
  const { canManageResourceQueue } = useAccess();
  const [queuedTasks, setQueuedTasks] = useState<
    API.ResourceMonitorGlobalQueuedTask[]
  >([]);
  const [loading, setLoading] = useState(false);

  const loadQueue = useCallback(async () => {
    setLoading(true);
    try {
      const res = await fetchResourceMonitorGlobalQueue();
      if (!res?.success) {
        message.error(res?.errorMessage || '加载全局排队失败');
        return;
      }
      setQueuedTasks(res.data ?? []);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadQueue();
  }, [loadQueue]);

  /** 按资源池分组，保持接口返回的池顺序 */
  const pools = useMemo(() => {
    const map = new Map<string, API.ResourceMonitorGlobalQueuedTask[]>();
    queuedTasks.forEach((t) => {
      if (!map.has(t.nodePool)) map.set(t.nodePool, []);
      map.get(t.nodePool)?.push(t);
    });
    return Array.from(map.entries()).map(([pool, tasks]) => ({ pool, tasks }));
  }, [queuedTasks]);

  const handleMove = useCallback(
    async (taskId: string, direction: 'up' | 'down') => {
      const res = await reorderResourceMonitorGlobalQueue({
        taskId,
        direction,
      });
      if (!res?.success) {
        message.error(res?.errorMessage || '调整失败');
        return;
      }
      setQueuedTasks(res.data ?? []);
      message.success(
        direction === 'up'
          ? '已上移（仅同资源池内调整）'
          : '已下移（仅同资源池内调整）',
      );
    },
    [],
  );

  const handleCancel = useCallback(async (taskId: string) => {
    const res = await cancelResourceMonitorGlobalQueueTask(taskId);
    if (!res?.success) {
      message.error(res?.errorMessage || '取消失败');
      return;
    }
    setQueuedTasks(res.data ?? []);
    message.success('已取消排队');
  }, []);

  const buildColumns = (
    tasks: API.ResourceMonitorGlobalQueuedTask[],
  ): TableColumnsType<API.ResourceMonitorGlobalQueuedTask> => {
    const columns: TableColumnsType<API.ResourceMonitorGlobalQueuedTask> = [
      {
        title: '池内序号',
        key: 'positionInPool',
        width: 90,
        render: (_: unknown, record: API.ResourceMonitorGlobalQueuedTask) => (
          <Text strong>{record.positionInPool}</Text>
        ),
      },
      {
        title: '任务名称',
        dataIndex: 'name',
        key: 'name',
        width: 240,
        ellipsis: true,
      },
      { title: '模型', dataIndex: 'model', key: 'model', width: 120 },
      { title: '数据集', dataIndex: 'dataset', key: 'dataset', width: 120 },
      {
        title: '状态',
        dataIndex: 'status',
        key: 'status',
        width: 90,
        render: (val: string) => {
          const cfg = STATUS_TAG[val as keyof typeof STATUS_TAG];
          return <Tag color={cfg?.color ?? 'default'}>{cfg?.label ?? val}</Tag>;
        },
      },
      {
        title: '提交时间',
        dataIndex: 'submitTime',
        key: 'submitTime',
        width: 170,
      },
    ];

    if (canManageResourceQueue) {
      columns.push({
        title: '操作',
        key: 'action',
        width: 100,
        align: 'center',
        render: (_: unknown, record: API.ResourceMonitorGlobalQueuedTask) => (
          <Space size={2}>
            <Tooltip title="上移（仅同资源池内调整）">
              <Button
                type="text"
                size="small"
                disabled={record.positionInPool === 1}
                icon={
                  <ArrowUpOutlined
                    style={{
                      color:
                        record.positionInPool === 1 ? undefined : '#1677ff',
                    }}
                  />
                }
                onClick={() => handleMove(record.id, 'up')}
              />
            </Tooltip>
            <Tooltip title="下移（仅同资源池内调整）">
              <Button
                type="text"
                size="small"
                disabled={record.positionInPool === tasks.length}
                icon={
                  <ArrowDownOutlined
                    style={{
                      color:
                        record.positionInPool === tasks.length
                          ? undefined
                          : '#1677ff',
                    }}
                  />
                }
                onClick={() => handleMove(record.id, 'down')}
              />
            </Tooltip>
            <Popconfirm
              title="确认取消该任务的排队？"
              description="取消后任务将退出全局排队队列。"
              onConfirm={() => handleCancel(record.id)}
            >
              <Tooltip title="取消排队">
                <Button
                  type="text"
                  size="small"
                  icon={<CloseOutlined style={{ color: '#ff4d4f' }} />}
                />
              </Tooltip>
            </Popconfirm>
          </Space>
        ),
      });
    }

    return columns;
  };

  return (
    <PageContainer
      title="全局排队"
      subTitle="跨服务器查看正在等待资源的任务，按所需资源池分组；组内顺序决定谁先获得该池空闲资源"
      extra={
        <Button icon={<ReloadOutlined />} loading={loading} onClick={loadQueue}>
          刷新
        </Button>
      }
    >
      <Text type="secondary" style={{ display: 'block', marginBottom: 16 }}>
        说明：不同资源池（CPU/GPU/其他）的任务互不竞争，手动调整只在同池内生效；任务获得资源后由调度器每
        10 秒一拍分配节点。
      </Text>

      {pools.length === 0 ? (
        <Card>
          <Empty description="当前无排队任务" />
        </Card>
      ) : (
        pools.map(({ pool, tasks }) => (
          <Card
            key={pool}
            title={`${getPoolLabel(pool)}（${tasks.length}）`}
            style={{ marginBottom: 16 }}
          >
            <Table
              rowKey="id"
              columns={buildColumns(tasks)}
              dataSource={tasks}
              pagination={false}
              locale={{ emptyText: '该池当前无排队任务' }}
              scroll={{ x: 900 }}
            />
          </Card>
        ))
      )}
    </PageContainer>
  );
};

export default QueuePage;
