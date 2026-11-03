import type { ActionType, ProColumns } from '@ant-design/pro-components';
import { PageContainer, ProTable } from '@ant-design/pro-components';
import { history } from '@umijs/max';
import { Button, message, Popconfirm, Space } from 'antd';
import React, { useRef } from 'react';
import {
  MODEL_BACKEND_TYPE_OPTIONS,
  MODEL_TYPE_VALUE_ENUM,
} from '@/constants/model';
import {
  deleteModelAsset,
  deleteModelVersion,
  downloadModelVersion,
  fetchModelList as fetchModelListService,
} from '@/services/platform';
import { beginDownloadProgress } from '@/utils/downloadProgressToast';
import { formatDisplayDateTime } from '@/utils/formatDateTime';

const ModelList: React.FC = () => {
  const actionRef = useRef<ActionType>(null);

  const fetchModelList = async (params: any, sort: any) => {
    try {
      const sortEntry = Object.entries(sort || {})[0] as
        | [string, 'ascend' | 'descend']
        | undefined;
      const query: Record<string, unknown> = { ...params };
      if (sortEntry) {
        const [field, order] = sortEntry;
        query.sortDirection = order === 'ascend' ? 'ASC' : 'DESC';
        if (field === 'name') query.sortBy = 'NAME';
        else if (field === 'version') query.sortBy = 'VERSION';
        else if (field === 'uploadTime' || field === 'updatedAt')
          query.sortBy = 'UPDATED_AT';
        else query.sortBy = 'UPDATED_AT';
      }
      const res = await fetchModelListService(query);
      return {
        data: res?.data || [],
        success: true,
        total: res?.total ?? (res?.data?.length || 0),
      };
    } catch (error: any) {
      // 主接口失败不得用 Mock 假通过（A-COMMON-12 / A-MODEL-16）
      message.error(
        error?.info?.message || error?.message || '加载模型列表失败',
      );
      return { data: [], success: false, total: 0 };
    }
  };

  const handleDeleteAsset = async (record: API.ModelItem) => {
    const assetId = record.assetId || record.id;
    try {
      if (record.assetId) {
        await deleteModelAsset(assetId);
      } else {
        await deleteModelVersion(record.id);
      }
      message.success('删除成功');
      actionRef.current?.reload();
    } catch (error: any) {
      message.error(error?.info?.message || error?.message || '删除失败');
    }
  };

  const handleDownload = async (record: API.ModelItem) => {
    const progress = beginDownloadProgress();
    try {
      await downloadModelVersion(record.id, record.fileName, {
        skipErrorHandler: true,
        onProgress: progress.update,
      });
      progress.close();
      message.success('已交给浏览器下载');
    } catch (error: any) {
      progress.close();
      const tip = error?.info?.message || error?.message || '下载失败';
      if (tip === '已取消下载') return;
      message.error(tip);
    }
  };

  const columns: ProColumns<API.ModelItem>[] = [
    {
      title: '模型名称',
      dataIndex: 'name',
      key: 'name',
      width: 160,
      ellipsis: true,
      sorter: true,
    },
    {
      title: '版本号',
      dataIndex: 'version',
      key: 'version',
      width: 100,
      sorter: true,
    },
    {
      title: '类型',
      dataIndex: 'type',
      key: 'type',
      width: 88,
      valueType: 'select',
      fieldProps: {
        options: MODEL_BACKEND_TYPE_OPTIONS.map((item) => ({
          label: item.label,
          value: item.value,
        })),
      },
      render: (_, record) =>
        MODEL_TYPE_VALUE_ENUM[record.type]?.text ?? record.type,
      sorter: true,
    },
    {
      title: '上传时间',
      dataIndex: 'uploadTime',
      key: 'uploadTime',
      width: 180,
      hideInSearch: true,
      sorter: true,
      render: (_, record) => formatDisplayDateTime(record.uploadTime),
    },
    {
      title: '大小',
      dataIndex: 'size',
      key: 'size',
      width: 100,
      hideInSearch: true,
    },
    {
      title: '备注',
      dataIndex: 'remark',
      key: 'remark',
      width: 200,
      ellipsis: true,
      hideInSearch: true,
    },
    {
      title: '操作',
      key: 'action',
      width: 180,
      align: 'left',
      hideInSearch: true,
      render: (_, record) => (
        <Space size={0} wrap={false}>
          <Button
            type="link"
            style={{ paddingLeft: 0 }}
            onClick={() => {
              const assetId = record.assetId || record.id;
              const query =
                record.assetId && record.id !== record.assetId
                  ? `?versionId=${encodeURIComponent(record.id)}`
                  : '';
              history.push(`/model/detail/${assetId}${query}`);
            }}
          >
            详情
          </Button>
          <Button
            type="link"
            style={{ paddingInline: 4 }}
            onClick={() => handleDownload(record)}
          >
            下载
          </Button>
          <Popconfirm
            title={
              record.assetId
                ? '确认删除该模型资产及全部版本？'
                : '确认删除该模型版本？'
            }
            onConfirm={() => handleDeleteAsset(record)}
          >
            <Button type="link" danger style={{ paddingInline: 4 }}>
              删除
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <PageContainer
      title="模型管理"
      subTitle="浏览模型资产与版本（列表展示各资产最新版本）"
      extra={
        <Button type="primary" onClick={() => history.push('/model/upload')}>
          + 上传模型
        </Button>
      }
    >
      <ProTable
        actionRef={actionRef}
        columns={columns}
        request={fetchModelList}
        rowKey="id"
        search={{ labelWidth: 'auto' }}
        pagination={{ pageSize: 10, showSizeChanger: true }}
        tableLayout="fixed"
        scroll={{ x: 'max-content' }}
      />
    </PageContainer>
  );
};

export default ModelList;
