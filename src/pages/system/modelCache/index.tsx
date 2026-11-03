import { DeleteOutlined, ReloadOutlined } from '@ant-design/icons';
import { PageContainer } from '@ant-design/pro-components';
import { history, useAccess } from '@umijs/max';
import {
  Alert,
  Button,
  Card,
  Empty,
  Form,
  InputNumber,
  Modal,
  message,
  Progress,
  Space,
  Table,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  clearModelCache,
  fetchModelCacheOverview,
  type ModelCacheEntry,
  type ModelCacheNode,
  type ModelCacheOverview,
  updateModelCachePolicy,
} from '@/services/system/modelCache';

const GIB = 1024 ** 3;
const MIN_POLICY_GIB = 1;
const MAX_POLICY_GIB = 1024;

interface ModelCachePolicyForm {
  maxGiB: number;
  minFreeGiB: number;
  runtimeReserveGiB: number;
}

function formatBytes(value?: number): string {
  const bytes = Number(value || 0);
  if (!Number.isFinite(bytes) || bytes <= 0) return '0 B';
  const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB'];
  const index = Math.min(
    Math.floor(Math.log(bytes) / Math.log(1024)),
    units.length - 1,
  );
  return `${(bytes / 1024 ** index).toFixed(index === 0 ? 0 : 2)} ${units[index]}`;
}

function formatEpoch(value?: number): string {
  if (!value) return '-';
  return new Date(value * 1000).toLocaleString();
}

function formatUpdatedAt(value?: string | null): string {
  if (!value) return '尚未通过页面修改';
  const timestamp = new Date(value);
  return Number.isNaN(timestamp.getTime()) ? '-' : timestamp.toLocaleString();
}

function formatSignedBytes(value?: number): string {
  const bytes = Number(value || 0);
  if (bytes === 0) return '0 B';
  return `${bytes < 0 ? '-' : '+'}${formatBytes(Math.abs(bytes))}`;
}

const ModelCachePage: React.FC = () => {
  const access = useAccess();
  const [overview, setOverview] = useState<ModelCacheOverview>();
  const [loading, setLoading] = useState(false);
  const [clearingNode, setClearingNode] = useState<string>();
  const [savingPolicy, setSavingPolicy] = useState(false);
  const [policyForm] = Form.useForm<ModelCachePolicyForm>();
  const [selectedByNode, setSelectedByNode] = useState<
    Record<string, React.Key[]>
  >({});

  useEffect(() => {
    if (!access.canAccessModelCache) {
      history.replace('/403');
    }
  }, [access.canAccessModelCache]);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const response = await fetchModelCacheOverview({
        skipErrorHandler: true,
      });
      if (response.code !== 200 || !response.data) {
        throw new Error(response.message || '模型缓存查询失败');
      }
      setOverview(response.data);
      policyForm.setFieldsValue({
        maxGiB: response.data.maxBytes / GIB,
        minFreeGiB: response.data.minFreeBytes / GIB,
        runtimeReserveGiB: response.data.runtimeReserveBytes / GIB,
      });
      setSelectedByNode({});
    } catch (error) {
      message.error(
        error instanceof Error ? error.message : '模型缓存查询失败',
      );
    } finally {
      setLoading(false);
    }
  }, [policyForm]);

  useEffect(() => {
    if (access.canAccessModelCache) {
      void load();
    }
  }, [access.canAccessModelCache, load]);

  const executeClear = useCallback(
    async (node: ModelCacheNode, clearAll: boolean) => {
      const sha256s = clearAll
        ? []
        : (selectedByNode[node.serverIp] || []).map(String);
      if (!clearAll && sha256s.length === 0) {
        message.warning('请先选择要清理的权重');
        return;
      }
      setClearingNode(node.serverIp);
      try {
        const response = await clearModelCache(
          {
            serverIps: [node.serverIp],
            sha256s,
            clearAll,
          },
          { skipErrorHandler: true },
        );
        if (response.code !== 200 || !response.data) {
          throw new Error(response.message || '缓存清理失败');
        }
        const result = response.data.nodes[0];
        if (!result || result.error) {
          throw new Error(result?.error || '缓存节点未返回结果');
        }
        const parts = [`已清理 ${result.cleared.length} 项`];
        if (result.inUse.length > 0) {
          parts.push(`${result.inUse.length} 项正在使用，已安全跳过`);
        }
        if (result.notFound.length > 0) {
          parts.push(`${result.notFound.length} 项已不存在`);
        }
        message.success(parts.join('；'));
        await load();
      } catch (error) {
        message.error(error instanceof Error ? error.message : '缓存清理失败');
      } finally {
        setClearingNode(undefined);
      }
    },
    [load, selectedByNode],
  );

  const confirmClear = useCallback(
    (node: ModelCacheNode, clearAll: boolean) => {
      const count = (selectedByNode[node.serverIp] || []).length;
      Modal.confirm({
        title: clearAll
          ? '清空该节点的模型缓存？'
          : `清理选中的 ${count} 项缓存？`,
        content:
          '这里只删除可重新下载的缓存副本，不删除对象存储中的原始模型。正在被训练或推理使用的权重会自动跳过。',
        okText: clearAll ? '确认清空' : '确认清理',
        okButtonProps: { danger: true },
        cancelText: '取消',
        onOk: () => executeClear(node, clearAll),
      });
    },
    [executeClear, selectedByNode],
  );

  const savePolicy = useCallback(
    async (values: ModelCachePolicyForm) => {
      setSavingPolicy(true);
      try {
        const response = await updateModelCachePolicy(
          {
            maxBytes: values.maxGiB * GIB,
            minFreeBytes: values.minFreeGiB * GIB,
            runtimeReserveBytes: values.runtimeReserveGiB * GIB,
          },
          { skipErrorHandler: true },
        );
        if (response.code !== 200 || !response.data) {
          throw new Error(response.message || '缓存保护策略保存失败');
        }
        setOverview(response.data);
        policyForm.setFieldsValue({
          maxGiB: response.data.maxBytes / GIB,
          minFreeGiB: response.data.minFreeBytes / GIB,
          runtimeReserveGiB: response.data.runtimeReserveBytes / GIB,
        });
        message.success(response.message || '缓存保护策略保存成功');
      } catch (error) {
        message.error(
          `${error instanceof Error ? error.message : '缓存保护策略保存失败'}；已重新读取服务器实际值`,
        );
        await load();
      } finally {
        setSavingPolicy(false);
      }
    },
    [load, policyForm],
  );

  const confirmPolicyUpdate = useCallback(async () => {
    let values: ModelCachePolicyForm;
    try {
      values = await policyForm.validateFields();
    } catch {
      return;
    }
    const current = overview
      ? {
          maxGiB: overview.maxBytes / GIB,
          minFreeGiB: overview.minFreeBytes / GIB,
          runtimeReserveGiB: overview.runtimeReserveBytes / GIB,
        }
      : undefined;
    const lowering = Boolean(
      current &&
        (values.maxGiB < current.maxGiB ||
          values.minFreeGiB < current.minFreeGiB ||
          values.runtimeReserveGiB < current.runtimeReserveGiB),
    );
    const total = values.maxGiB + values.minFreeGiB + values.runtimeReserveGiB;
    Modal.confirm({
      title: lowering ? '确认降低缓存磁盘保护门？' : '确认更新缓存磁盘保护门？',
      content: `空缓存节点至少需要 ${total} GiB 可用空间。保存前会检查所有缓存就绪节点；不满足条件则拒绝保存。运行中的任务不会被修改，新建任务和后续部署验收使用新值。`,
      okText: lowering ? '确认降低' : '确认更新',
      okButtonProps: lowering ? { danger: true } : undefined,
      cancelText: '取消',
      onOk: () => savePolicy(values),
    });
  }, [overview, policyForm, savePolicy]);

  const columns = useMemo<ColumnsType<ModelCacheEntry>>(
    () => [
      {
        title: '权重摘要',
        dataIndex: 'sha256',
        width: 180,
        render: (value: string) => (
          <Tooltip title={value}>
            <Typography.Text code copyable={{ text: value }}>
              {value.slice(0, 12)}…
            </Typography.Text>
          </Tooltip>
        ),
      },
      {
        title: '来源',
        dataIndex: 'storagePath',
        ellipsis: true,
        render: (value: string) => value || '-',
      },
      {
        title: '缓存占用',
        dataIndex: 'diskSizeBytes',
        width: 120,
        render: formatBytes,
      },
      {
        title: '最近使用',
        dataIndex: 'lastUsedAtEpochSeconds',
        width: 180,
        render: formatEpoch,
      },
      {
        title: '状态',
        width: 130,
        render: (_, entry) => {
          if (!entry.valid) return <Tag color="error">缓存异常</Tag>;
          if (entry.inUse) return <Tag color="processing">正在使用</Tag>;
          return <Tag color="success">可用</Tag>;
        },
      },
    ],
    [],
  );

  if (!access.canAccessModelCache) return null;

  return (
    <PageContainer
      title="模型缓存"
      subTitle="查看各物理计算节点上的训练/推理权重缓存"
      extra={[
        <Button
          key="refresh"
          icon={<ReloadOutlined />}
          onClick={() => void load()}
          loading={loading}
        >
          刷新
        </Button>,
      ]}
    >
      <Alert
        showIcon
        type={overview?.enabled ? 'info' : 'warning'}
        message={overview?.enabled ? '缓存保护已启用' : '模型缓存当前关闭'}
        description={
          overview?.enabled
            ? `缓存上限 ${formatBytes(overview.maxBytes)}，至少保留 ${formatBytes(
                overview.minFreeBytes,
              )} 空闲空间，并为运行镜像预留 ${formatBytes(
                overview.runtimeReserveBytes,
              )}；清理不会中断正在运行的任务。`
            : '请先完成计算节点缓存目录初始化和节点验证，再在维护窗口启用。保护参数可在下方设置。'
        }
        style={{ marginBottom: 16 }}
      />

      {access.canManageModelCachePolicy ? (
        <Card
          title="缓存磁盘保护门"
          style={{ marginBottom: 16 }}
          loading={loading}
        >
          <Alert
            showIcon
            type="warning"
            message="保存时按真实节点磁盘情况做安全校验"
            description="三个数值均按整数 GiB 设置。任一节点空间不足、检查失败或现有缓存超过新上限时，平台会拒绝保存。"
            style={{ marginBottom: 16 }}
          />
          <Form<ModelCachePolicyForm>
            form={policyForm}
            layout="vertical"
            disabled={loading || savingPolicy || !overview}
          >
            <Space size={24} wrap align="start">
              <Form.Item
                name="maxGiB"
                label="缓存最多使用"
                rules={[
                  { required: true, message: '请输入缓存上限' },
                  {
                    type: 'number',
                    min: MIN_POLICY_GIB,
                    max: MAX_POLICY_GIB,
                    message: '请输入 1～1024 之间的整数',
                  },
                ]}
              >
                <InputNumber
                  min={MIN_POLICY_GIB}
                  max={MAX_POLICY_GIB}
                  precision={0}
                  addonAfter="GiB"
                  style={{ width: 220 }}
                />
              </Form.Item>
              <Form.Item
                name="minFreeGiB"
                label="最低空闲空间"
                rules={[
                  { required: true, message: '请输入最低空闲空间' },
                  {
                    type: 'number',
                    min: MIN_POLICY_GIB,
                    max: MAX_POLICY_GIB,
                    message: '请输入 1～1024 之间的整数',
                  },
                ]}
              >
                <InputNumber
                  min={MIN_POLICY_GIB}
                  max={MAX_POLICY_GIB}
                  precision={0}
                  addonAfter="GiB"
                  style={{ width: 220 }}
                />
              </Form.Item>
              <Form.Item
                name="runtimeReserveGiB"
                label="运行镜像预留"
                rules={[
                  { required: true, message: '请输入运行镜像预留' },
                  {
                    type: 'number',
                    min: MIN_POLICY_GIB,
                    max: MAX_POLICY_GIB,
                    message: '请输入 1～1024 之间的整数',
                  },
                ]}
              >
                <InputNumber
                  min={MIN_POLICY_GIB}
                  max={MAX_POLICY_GIB}
                  precision={0}
                  addonAfter="GiB"
                  style={{ width: 220 }}
                />
              </Form.Item>
            </Space>
            <Typography.Paragraph type="secondary">
              当前空缓存保护门：{formatBytes(overview?.emptyCacheGateBytes)}
              ；最近更新：
              {formatUpdatedAt(overview?.policyUpdatedAt)}
            </Typography.Paragraph>
            <Space>
              <Button
                type="primary"
                loading={savingPolicy}
                onClick={() => void confirmPolicyUpdate()}
              >
                保存保护门
              </Button>
              <Button
                disabled={loading || savingPolicy}
                onClick={() => void load()}
              >
                恢复服务器当前值
              </Button>
            </Space>
          </Form>
        </Card>
      ) : null}

      {!overview?.nodes?.length ? (
        <Card loading={loading}>
          <Empty description="暂无计算节点" />
        </Card>
      ) : (
        <Space direction="vertical" size={16} style={{ width: '100%' }}>
          {overview.nodes.map((node) => {
            const percent = overview.maxBytes
              ? Math.min(
                  100,
                  Math.round((node.usedBytes / overview.maxBytes) * 100),
                )
              : 0;
            const selected = selectedByNode[node.serverIp] || [];
            const clearing = clearingNode === node.serverIp;
            return (
              <Card
                key={node.serverIp}
                loading={loading}
                title={
                  <Space>
                    <span>{node.hostname || node.serverIp}</span>
                    <Typography.Text type="secondary">
                      {node.k8sNodeName || node.serverIp}
                    </Typography.Text>
                    {node.cacheReady ? (
                      <Tag color="success">缓存就绪</Tag>
                    ) : (
                      <Tag>未就绪</Tag>
                    )}
                  </Space>
                }
                extra={
                  access.canClearModelCache ? (
                    <Space>
                      <Button
                        danger
                        icon={<DeleteOutlined />}
                        disabled={!node.cacheReady || selected.length === 0}
                        loading={clearing}
                        onClick={() => confirmClear(node, false)}
                      >
                        清理所选
                      </Button>
                      <Button
                        danger
                        disabled={!node.cacheReady || node.entries.length === 0}
                        loading={clearing}
                        onClick={() => confirmClear(node, true)}
                      >
                        清空节点缓存
                      </Button>
                    </Space>
                  ) : null
                }
              >
                {node.error ? (
                  <Alert
                    showIcon
                    type="warning"
                    message={node.error}
                    style={{ marginBottom: 16 }}
                  />
                ) : null}
                <Space direction="vertical" style={{ width: '100%' }}>
                  <Typography.Text>
                    已用 {formatBytes(node.usedBytes)} / 上限{' '}
                    {formatBytes(overview.maxBytes)}；磁盘可用{' '}
                    {formatBytes(node.diskFreeBytes)}
                  </Typography.Text>
                  {node.cacheReady && !node.error ? (
                    <Typography.Text
                      type={
                        node.policyHeadroomBytes < 0 ? 'danger' : 'secondary'
                      }
                    >
                      当前保护门要求可用{' '}
                      {formatBytes(node.requiredAvailableBytes)}；安全余量{' '}
                      {formatSignedBytes(node.policyHeadroomBytes)}
                    </Typography.Text>
                  ) : null}
                  <Progress
                    percent={percent}
                    status={percent >= 90 ? 'exception' : 'normal'}
                  />
                  <Table<ModelCacheEntry>
                    rowKey="sha256"
                    size="small"
                    pagination={false}
                    columns={columns}
                    dataSource={node.entries}
                    rowSelection={
                      access.canClearModelCache
                        ? {
                            selectedRowKeys: selected,
                            getCheckboxProps: (entry) => ({
                              disabled: entry.inUse,
                            }),
                            onChange: (keys) =>
                              setSelectedByNode((current) => ({
                                ...current,
                                [node.serverIp]: keys,
                              })),
                          }
                        : undefined
                    }
                  />
                </Space>
              </Card>
            );
          })}
        </Space>
      )}
    </PageContainer>
  );
};

export default ModelCachePage;
