import type {
  ActionType,
  ParamsType,
  ProColumns,
} from '@ant-design/pro-components';
import { PageContainer, ProTable } from '@ant-design/pro-components';
import { history, useAccess } from '@umijs/max';
import { Button, message } from 'antd';
import type { SortOrder } from 'antd/es/table/interface';
import dayjs from 'dayjs';
import React, { useEffect, useRef, useState } from 'react';
import {
  downloadLogListAsCsv,
  exportSystemLogs,
  getLogList,
  getOperationTypeValueEnum,
  type LogItem,
  type LogListParams,
} from '@/services/system/log';
import { notifyRequestError } from '../notifyRequestError';

/**
 * 日志管理页（操作日志）
 * GET /api/system/log/list；不传 currentUsername（超管/普管看全部）
 * IP 列仅超管展示；导出 GET /api/system/log/export（仅超管）
 */
const LogManagement: React.FC = () => {
  const access = useAccess();
  const actionRef = useRef<ActionType>(null);
  const lastQueryRef = useRef<LogListParams>({ pageNum: 1, pageSize: 10 });
  const [operateTypeEnum, setOperateTypeEnum] = useState<
    Record<string, { text: string }>
  >({});
  const [exporting, setExporting] = useState(false);

  const isSuperAdmin = access.canLogViewAdminAndIp;
  const canExport = access.canLogExport;

  useEffect(() => {
    if (!access.canAccessSystemLog) {
      history.replace('/403');
    }
  }, [access.canAccessSystemLog]);

  useEffect(() => {
    void getOperationTypeValueEnum().then(setOperateTypeEnum);
  }, []);

  const fetchLogList = async (
    params: ParamsType & {
      current?: number;
      pageSize?: number;
      username?: string;
      operateType?: string;
      operateTime?: [string, string];
      ip?: string;
      result?: string;
      content?: string;
    },
    _sort: Record<string, SortOrder>,
    _filter: Record<string, (string | number)[] | null>,
  ) => {
    try {
      const {
        current = 1,
        pageSize = 10,
        username,
        operateType,
        operateTime,
        ip,
        result,
        content,
      } = params as {
        current?: number;
        pageSize?: number;
        username?: string;
        operateType?: string;
        operateTime?: [string, string];
        ip?: string;
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

      // 管理端：不要传 currentUsername；普管不传 ip
      const requestParams: LogListParams = {
        pageNum: current,
        pageSize,
        username: username ?? '',
        operateType: operateType ?? '',
        operateTime: operateTimeRange,
        ip: isSuperAdmin ? (ip ?? '') : '',
        result: result ?? '',
        content: content ?? '',
      };
      lastQueryRef.current = requestParams;

      const response = await getLogList(requestParams);

      if (response.code === 200) {
        const list = (response.data?.list ?? []).map(
          (item: LogItem, index: number) => ({
            ...item,
            _index: ((current ?? 1) - 1) * (pageSize ?? 10) + index + 1,
          }),
        );
        return {
          data: list,
          success: true,
          total: response.data?.total ?? 0,
        };
      }
      message.error(response.msg ?? '查询失败');
      return { data: [], success: false, total: 0 };
    } catch (error: unknown) {
      notifyRequestError(error, '查询失败');
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
      title: '操作人',
      dataIndex: 'username',
      key: 'username',
      align: 'center',
      fieldProps: {
        placeholder: '请输入操作人',
        onPressEnter: () => actionRef.current?.reload(),
      },
      render: (_, record) => record.username || '-',
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
            fieldProps: {
              placeholder: '请输入IP地址',
              onPressEnter: () => actionRef.current?.reload(),
            },
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

  const handleExport = async () => {
    if (!canExport) {
      message.warning('暂无导出权限');
      return;
    }
    setExporting(true);
    try {
      const response = await exportSystemLogs({
        ...lastQueryRef.current,
        pageNum: 1,
        pageSize: 10000,
      });
      if (response.code !== 200) {
        message.error(response.msg || '导出失败');
        return;
      }
      const list = response.data?.list ?? [];
      if (!list.length) {
        message.warning('暂无数据可导出');
        return;
      }
      downloadLogListAsCsv(list);
      message.success(response.msg || `导出成功（共 ${list.length} 条）`);
    } catch (error: unknown) {
      notifyRequestError(error, '导出失败');
    } finally {
      setExporting(false);
    }
  };

  return (
    <PageContainer
      title="日志管理"
      subTitle="用户操作日志查询"
      extra={
        canExport ? (
          <Button
            type="primary"
            loading={exporting}
            onClick={() => void handleExport()}
          >
            导出日志
          </Button>
        ) : undefined
      }
    >
      <ProTable<LogItem>
        actionRef={actionRef}
        columns={columns}
        request={fetchLogList}
        rowKey="id"
        search={{ labelWidth: 'auto' }}
        pagination={{
          defaultPageSize: 10,
          showSizeChanger: true,
        }}
        toolBarRender={false}
        dateFormatter="string"
      />
    </PageContainer>
  );
};

export default LogManagement;
