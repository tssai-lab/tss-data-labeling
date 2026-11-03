import { MoreOutlined } from '@ant-design/icons';
import type { ActionType, ProColumns } from '@ant-design/pro-components';
import { PageContainer, ProTable } from '@ant-design/pro-components';
import { history, useSearchParams } from '@umijs/max';
import {
  Alert,
  Button,
  Dropdown,
  message,
  Popconfirm,
  Progress,
  Space,
  Tag,
  Tooltip,
} from 'antd';
import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  deleteTask,
  fetchTaskList as fetchTaskListService,
  stopTask,
} from '@/services/platform';
import { enrichTaskItemsWithDisplayNames } from '@/utils/taskDisplayNames';
import {
  getTrainingProgressStatus,
  getTrainingStatusTagColor,
  getTrainingStatusText,
  normalizeTrainingProgress,
} from '@/utils/trainingStatusDisplay';

function readListPageFromSearch(searchParams: URLSearchParams) {
  return {
    current: Math.max(1, Number(searchParams.get('current')) || 1),
    pageSize: Math.max(1, Number(searchParams.get('pageSize')) || 10),
  };
}

/**
 * 训练任务列表页 - Page 层
 * 调用 Services 层接口，适配 ProTable 的 request 格式
 */
const TaskList: React.FC = () => {
  const actionRef = useRef<ActionType | null>(null);
  const [loadError, setLoadError] = useState('');
  const [searchParams, setSearchParams] = useSearchParams();
  const [listCurrent, setListCurrent] = useState(
    () => readListPageFromSearch(searchParams).current,
  );
  const [listPageSize, setListPageSize] = useState(
    () => readListPageFromSearch(searchParams).pageSize,
  );
  /** 避免翻页写 URL 与「从详情返回读 URL」互相打架 */
  const lastSyncedQueryRef = useRef(searchParams.toString());

  // 仅在 URL 被外部改写时同步（例如详情返回 /task/list?current=3）
  useEffect(() => {
    const query = searchParams.toString();
    if (query === lastSyncedQueryRef.current) return;
    lastSyncedQueryRef.current = query;
    const { current, pageSize } = readListPageFromSearch(searchParams);
    setListCurrent(current);
    setListPageSize(pageSize);
  }, [searchParams]);

  const syncListPage = useCallback(
    (page: number, pageSize: number) => {
      setListCurrent(page);
      setListPageSize(pageSize);
      const next = new URLSearchParams(searchParams);
      next.set('current', String(page));
      next.set('pageSize', String(pageSize));
      lastSyncedQueryRef.current = next.toString();
      setSearchParams(next, { replace: true });
    },
    [searchParams, setSearchParams],
  );

  const buildDetailUrl = useCallback(
    (taskOrExperimentId: string, hash?: string) => {
      const qs = new URLSearchParams();
      qs.set('fromCurrent', String(listCurrent));
      qs.set('fromPageSize', String(listPageSize));
      const path = `/task/detail/${encodeURIComponent(taskOrExperimentId)}?${qs.toString()}`;
      return hash ? `${path}#${hash}` : path;
    },
    [listCurrent, listPageSize],
  );

  const fetchTaskList = async (params: any) => {
    try {
      const res = await fetchTaskListService(params);
      if (res?.success === false) throw new Error(res.errorMessage || '训练任务加载失败');
      const list = res?.data?.data ?? [];
      // 筛选和总数由后台在分页前计算，不能只过滤当前页。
      const total = res?.data?.total ?? list.length;
      const enriched = await enrichTaskItemsWithDisplayNames(list, {
        skipErrorHandler: true,
      });
      setLoadError('');
      return { data: enriched, success: true, total };
    } catch (error: any) {
      setLoadError(error?.message || '训练任务加载失败，请检查后端服务');
      return { data: [], success: false, total: 0 };
    }
  };

  const handleStop = async (taskId: string) => {
    try {
      const res = await stopTask(taskId);
      if (res?.success === false) throw new Error(res.errorMessage || '终止失败');
      message.success(`任务状态：${getTrainingStatusText(res.data.status)}`);
      actionRef.current?.reload();
    } catch (error: any) {
      message.error(error?.info?.message || error?.message || '终止失败');
    }
  };

  const handleDelete = async (taskId: string) => {
    try {
      await deleteTask(taskId);
      message.success('删除成功');
      actionRef.current?.reload();
    } catch (error: any) {
      message.error(error?.info?.message || error?.message || '删除失败');
    }
  };

  const getStatusTag = (status: string, errorMessage?: string) => {
    const text = getTrainingStatusText(status);
    const color = getTrainingStatusTagColor(status);
    const tag = <Tag color={color}>{text}</Tag>;
    if ((status === 'failed' || status === 'scheduled') && errorMessage?.trim()) {
      return (
        <Tooltip title={errorMessage} overlayStyle={{ maxWidth: 480 }}>
          {tag}
        </Tooltip>
      );
    }
    return tag;
  };

  const columns: ProColumns<API.TaskItem>[] = [
    {
      title: '训练编号',
      dataIndex: 'experimentId',
      key: 'experimentId',
      width: 220,
      ellipsis: true,
      copyable: true,
    },
    {
      title: '版本号',
      dataIndex: 'versionNo',
      key: 'versionNo',
      width: 90,
      hideInSearch: true,
    },
    {
      title: '任务名称',
      dataIndex: 'name',
      key: 'name',
    },
    {
      title: '模型名称',
      dataIndex: 'modelName',
      key: 'modelName',
      render: (_, record) => record.modelName || '-',
    },
    {
      title: '数据集名称',
      dataIndex: 'datasetName',
      key: 'datasetName',
      render: (_, record) => record.datasetName || '-',
    },
    {
      title: '创建时间',
      dataIndex: 'createTime',
      key: 'createTime',
      valueType: 'dateTime',
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      render: (_, record) => getStatusTag(record.status, record.errorMessage),
    },
    {
      title: '进度',
      dataIndex: 'progress',
      key: 'progress',
      width: 160,
      hideInSearch: true,
      render: (_, record) => {
        const percent = normalizeTrainingProgress(record.progress);
        return (
          <Progress
            percent={percent}
            size="small"
            status={getTrainingProgressStatus(record.status)}
            format={(value) => `${value ?? 0}%`}
          />
        );
      },
    },
    {
      title: '操作',
      key: 'action',
      hideInSearch: true,
      fixed: 'right',
      width: 160,
      render: (_, record) => (
        <Space size={4} wrap={false}>
          <Button
            type="link"
            style={{ paddingLeft: 0 }}
            onClick={() =>
              history.push(
                buildDetailUrl(record.id || record.experimentId || ''),
              )
            }
          >
            查看详情
          </Button>
          <Dropdown
            trigger={['click']}
            menu={{
              items: [
                ...(record.experimentId
                  ? [
                      {
                        key: 'trace-history',
                        label: '追溯历史版本',
                        onClick: () =>
                          history.push(
                            buildDetailUrl(
                              record.experimentId || '',
                              'version-history',
                            ),
                          ),
                      },
                    ]
                  : []),
                ...(['queued', 'scheduled', 'running'].includes(record.status)
                  ? [
                      {
                        key: 'stop',
                        label: (
                          <Popconfirm
                            title="确定要终止任务吗？"
                            onConfirm={() => handleStop(record.id)}
                          >
                            <span style={{ color: '#ff4d4f' }}>终止任务</span>
                          </Popconfirm>
                        ),
                      },
                    ]
                  : []),
                {
                  key: 'delete',
                  label: (
                    <Popconfirm
                      title="确定要删除吗？"
                      onConfirm={() => handleDelete(record.id)}
                    >
                      <span style={{ color: '#ff4d4f' }}>删除</span>
                    </Popconfirm>
                  ),
                },
              ],
            }}
          >
            <Button type="text" size="small" icon={<MoreOutlined />} />
          </Dropdown>
        </Space>
      ),
    },
  ];

  return (
    <PageContainer
      title="训练任务"
      subTitle="管理所有训练任务；列表展示各实验最新版本，可进入详情追溯历史"
      extra={[
        <Button key="compare" onClick={() => history.push('/task/compare')}>
          模型性能对比
        </Button>,
        <Button
          key="create"
          type="primary"
          onClick={() => history.push('/task/create')}
        >
          发起训练
        </Button>,
      ]}
    >
      {loadError && (
        <Alert
          type="error"
          showIcon
          message={loadError}
          style={{ marginBottom: 16 }}
        />
      )}
      <ProTable
        actionRef={actionRef}
        columns={columns}
        request={fetchTaskList}
        polling={3000}
        rowKey="id"
        search={{
          labelWidth: 'auto',
        }}
        pagination={{
          current: listCurrent,
          pageSize: listPageSize,
          showSizeChanger: true,
          onChange: syncListPage,
          onShowSizeChange: syncListPage,
        }}
      />
    </PageContainer>
  );
};

export default TaskList;
