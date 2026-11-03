import type {
  ActionType,
  ParamsType,
  ProColumns,
} from '@ant-design/pro-components';
import { PageContainer, ProTable } from '@ant-design/pro-components';
import { useAccess, useModel } from '@umijs/max';
import type { SortOrder } from 'antd/es/table/interface';
import dayjs from 'dayjs';
import React, { useEffect, useRef, useState } from 'react';
import {
  getLogList,
  getOperationTypeValueEnum,
  type LogItem,
  type LogListParams,
} from '@/services/system/log';
import { redirectToLogin } from '@/utils/loginRedirect';

/**
 * 个人中心 - 我的操作记录
 * GET /api/system/log/list，传 currentUsername=本人
 * IP 列仅超管展示
 */
const MyOperationLogs: React.FC = () => {
  const access = useAccess();
  const { initialState } = useModel('@@initialState');
  const currentUser = initialState?.currentUser;
  const actionRef = useRef<ActionType>(null);
  const [operateTypeEnum, setOperateTypeEnum] = useState<
    Record<string, { text: string }>
  >({});

  const isSuperAdmin = access.canLogViewAdminAndIp;
  const selfUsername = String(
    currentUser?.username ?? currentUser?.name ?? '',
  ).trim();

  useEffect(() => {
    if (!currentUser) {
      redirectToLogin({ replace: true });
    }
  }, [currentUser]);

  useEffect(() => {
    void getOperationTypeValueEnum().then(setOperateTypeEnum);
  }, []);

  const fetchMyLogList = async (
    params: ParamsType & {
      current?: number;
      pageSize?: number;
      operateType?: string;
      operateTime?: [string, string];
      result?: string;
      content?: string;
    },
    _sort: Record<string, SortOrder>,
    _filter: Record<string, (string | number)[] | null>,
  ) => {
    try {
      if (!selfUsername) {
        return { data: [], success: false, total: 0 };
      }

      const {
        current = 1,
        pageSize = 10,
        operateType,
        operateTime,
        result,
        content,
      } = params as {
        current?: number;
        pageSize?: number;
        operateType?: string;
        operateTime?: [string, string];
        result?: string;
        content?: string;
      };

      let operateTimeRange: string[] = [];
      if (
        operateTime &&
        Array.isArray(operateTime) &&
        operateTime.length === 2 &&
        operateTime[0] &&
        operateTime[1]
      ) {
        operateTimeRange = [
          dayjs(operateTime[0]).format('YYYY-MM-DD HH:mm:ss'),
          dayjs(operateTime[1]).format('YYYY-MM-DD HH:mm:ss'),
        ];
      }

      const requestParams: LogListParams = {
        pageNum: current,
        pageSize,
        operateType: operateType ?? '',
        operateTime: operateTimeRange,
        result: result ?? '',
        content: content ?? '',
        currentUsername: selfUsername,
      };

      const response = await getLogList(requestParams);

      if (response.code === 200) {
        const list = (response.data?.list ?? []).map(
          (item: LogItem, index: number) => ({
            ...item,
            _index: (current - 1) * pageSize + index + 1,
          }),
        );
        return {
          data: list,
          success: true,
          total: response.data?.total ?? 0,
        };
      }
      return { data: [], success: false, total: 0 };
    } catch {
      return { data: [], success: false, total: 0 };
    }
  };

  const columns: ProColumns<LogItem>[] = [
    {
      title: '序号',
      dataIndex: '_index',
      key: '_index',
      width: 80,
      align: 'center',
      hideInSearch: true,
    },
    {
      title: '操作类型',
      dataIndex: 'operateType',
      key: 'operateType',
      align: 'center',
      valueType: 'select',
      valueEnum: operateTypeEnum,
      fieldProps: { placeholder: '请选择操作类型', allowClear: true },
      render: (_, record) =>
        operateTypeEnum[record.operateType]?.text || record.operateType || '-',
    },
    {
      title: '操作时间',
      dataIndex: 'operateTime',
      key: 'operateTime',
      align: 'center',
      valueType: 'dateTimeRange',
      search: {
        transform: (value: [string, string]) => ({ operateTime: value }),
      },
      render: (_, record) => record.operateTime || '-',
    },
    ...(isSuperAdmin
      ? [
          {
            title: 'IP地址',
            dataIndex: 'ip',
            key: 'ip',
            align: 'center' as const,
            hideInSearch: true,
            render: (_: unknown, record: LogItem) => record.ip || '-',
          } as ProColumns<LogItem>,
        ]
      : []),
    {
      title: '操作内容',
      dataIndex: 'content',
      key: 'content',
      align: 'center',
      ellipsis: true,
      hideInSearch: false,
      fieldProps: { placeholder: '操作内容关键词' },
      render: (_, record) => record.content || '-',
    },
    {
      title: '结果',
      dataIndex: 'result',
      key: 'result',
      align: 'center',
      valueType: 'select',
      valueEnum: {
        success: { text: '成功', status: 'Success' },
        failed: { text: '失败', status: 'Error' },
      },
      fieldProps: { placeholder: '请选择结果' },
    },
  ];

  if (!currentUser) return null;

  return (
    <PageContainer
      title="我的操作记录"
      subTitle={`当前用户：${selfUsername || currentUser.userid || ''}`}
    >
      <ProTable<LogItem>
        actionRef={actionRef}
        columns={columns}
        request={fetchMyLogList}
        rowKey="id"
        search={{ labelWidth: 'auto' }}
        pagination={{ defaultPageSize: 10, showSizeChanger: true }}
        toolBarRender={false}
        dateFormatter="string"
      />
    </PageContainer>
  );
};

export default MyOperationLogs;
