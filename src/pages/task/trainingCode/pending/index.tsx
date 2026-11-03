import { DownloadOutlined } from '@ant-design/icons';
import type { ActionType, ProColumns } from '@ant-design/pro-components';
import { PageContainer, ProTable } from '@ant-design/pro-components';
import { history, useAccess } from '@umijs/max';
import {
  Alert,
  Button,
  Col,
  Descriptions,
  Drawer,
  Dropdown,
  Empty,
  Form,
  Input,
  Modal,
  message,
  Popconfirm,
  Row,
  Space,
  Spin,
  Table,
  Tag,
  Tree,
  Typography,
} from 'antd';
import type { DataNode } from 'antd/es/tree';
import React, { useEffect, useMemo, useRef, useState } from 'react';
import { isTrainingCodeAutoApproveEnabled } from '@/constants/trainingCode';
import type { CodeVersionDetail, CodeVersionListItem } from '@/services/code';
import type { V2AdminCodeReviewTaskDetail } from '@/services/platform';
import {
  approveCodeVersion,
  CONSISTENCY_TRAINING_PROFILE,
  checkCodeVersionForTraining,
  downloadAdminCodeVersionFile,
  fetchAdminCodeFindings,
  fetchPendingCodeReviewTasks,
  getAdminCodeReviewDetail,
  getCodeUserDisplayName,
  listAdminCodeReviewFiles,
  previewAdminCodeReviewFile,
  rejectCodeVersion,
  rescanCodeReviewTask,
  revokeCodeVersion,
  upgradeCodeArtifact,
} from '@/services/platform';
import { getApiErrorMessage } from '@/utils/apiError';
import {
  buildCodeFileTreeData,
  collectCodeFileTreeExpandedKeys,
} from '@/utils/codeFileTree';
import { formatDisplayDateTime } from '@/utils/formatDateTime';
import {
  formatOwnerUserLabel,
  resolveOwnerUserIdFilter,
  useOwnerUsernameMap,
} from '@/utils/ownerUserLabel';
import type { PendingCodeVersionRecord } from '@/utils/pendingCodeVersions';
import {
  listPendingCodeVersions,
  markPendingCodeApproved,
  removePendingCodeVersion,
  upsertPendingCodeVersion,
} from '@/utils/pendingCodeVersions';

function approvalTag(status?: string) {
  if (status === 'APPROVED') {
    return <Tag color="success">已通过</Tag>;
  }
  if (status === 'PENDING') {
    return <Tag color="warning">待审核</Tag>;
  }
  if (status === 'REJECTED') {
    return <Tag color="error">已拒绝</Tag>;
  }
  if (status === 'REVOKED') {
    return <Tag color="default">已撤销</Tag>;
  }
  return <Tag>{status || '待审核'}</Tag>;
}

function riskLevelTag(level?: string) {
  const v = String(level || '').toUpperCase();
  if (v === 'HIGH') return <Tag color="error">HIGH</Tag>;
  if (v === 'MEDIUM') return <Tag color="warning">MEDIUM</Tag>;
  if (v === 'LOW') return <Tag color="success">LOW</Tag>;
  if (v === 'UNKNOWN') return <Tag>UNKNOWN</Tag>;
  return <Tag>{level || '-'}</Tag>;
}

type FindingRow = {
  id?: string;
  ruleId?: string;
  severity?: string;
  category?: string;
  filePath?: string;
  lineStart?: number;
  lineEnd?: number;
  description?: string;
};

function manualRecordToRow(
  record: PendingCodeVersionRecord,
): CodeVersionListItem {
  return {
    codeVersionId: record.codeVersionId,
    codeAssetId: record.codeAssetId?.trim() || '',
    codeAssetName: record.codeAssetName || '',
    version: '',
    fileName: record.fileName || '',
    trainingProfile: record.trainingProfile || CONSISTENCY_TRAINING_PROFILE,
    approvalStatus: record.approvalStatus || 'PENDING',
    status: 'READY',
    submittedAt: record.uploadedAt,
  };
}

const TrainingCodePending: React.FC = () => {
  const access = useAccess();
  const ownerUsernameMap = useOwnerUsernameMap();
  const actionRef = useRef<ActionType | null>(null);
  const listRequestSequence = useRef(0);
  const [listNotice, setListNotice] = useState<{
    type: 'error' | 'warning';
    text: string;
  }>();
  const [addOpen, setAddOpen] = useState(false);
  const [addForm] = Form.useForm();
  const [adding, setAdding] = useState(false);
  const [rejectingId, setRejectingId] = useState<string | null>(null);
  const [findingsOpen, setFindingsOpen] = useState(false);
  const [findingsLoading, setFindingsLoading] = useState(false);
  const [findings, setFindings] = useState<FindingRow[]>([]);
  const [findingsTitle, setFindingsTitle] = useState('');
  const [revokeOpen, setRevokeOpen] = useState(false);
  const [revokeTarget, setRevokeTarget] = useState<CodeVersionListItem | null>(
    null,
  );
  const [revokeForm] = Form.useForm();
  const [revoking, setRevoking] = useState(false);
  const [reviewOpen, setReviewOpen] = useState(false);
  const [reviewLoading, setReviewLoading] = useState(false);
  const [reviewDetail, setReviewDetail] = useState<CodeVersionDetail | null>(
    null,
  );
  const [reviewRaw, setReviewRaw] =
    useState<V2AdminCodeReviewTaskDetail | null>(null);
  const [reviewFiles, setReviewFiles] = useState<
    Array<{ path: string; fileName?: string }>
  >([]);
  const [reviewSelectedPath, setReviewSelectedPath] = useState<string>();
  const [reviewPreviewLoading, setReviewPreviewLoading] = useState(false);
  const [reviewPreviewContent, setReviewPreviewContent] = useState('');
  const [reviewExpandedKeys, setReviewExpandedKeys] = useState<React.Key[]>([]);
  const [reviewDownloading, setReviewDownloading] = useState(false);
  const [approvalStatusFilter, setApprovalStatusFilter] = useState('PENDING');

  useEffect(() => {
    if (!access.isAdmin) {
      history.replace('/403');
    }
  }, [access.isAdmin]);

  const requestList = async (params: {
    current?: number;
    pageSize?: number;
    riskLevel?: string;
    keyword?: string;
    approvalStatus?: string;
    ownerUserId?: string;
    submittedFrom?: string;
    submittedTo?: string;
    sortBy?: string;
    sortDirection?: string;
  }) => {
    const sequence = ++listRequestSequence.current;
    const current = params.current ?? 1;
    const pageSize = params.pageSize ?? 10;
    const approvalStatus =
      params.approvalStatus?.trim() || approvalStatusFilter || 'PENDING';

    let remote: CodeVersionListItem[] = [];
    let total = 0;
    try {
      const ownerUserIdNum = resolveOwnerUserIdFilter(
        params.ownerUserId,
        ownerUsernameMap,
      );
      const res = await fetchPendingCodeReviewTasks(
        {
          current,
          pageSize,
          approvalStatus,
          riskLevel: params.riskLevel?.trim() || undefined,
          keyword: params.keyword?.trim() || undefined,
          ownerUserId: ownerUserIdNum,
          submittedFrom: params.submittedFrom?.trim() || undefined,
          submittedTo: params.submittedTo?.trim() || undefined,
          sortBy: params.sortBy?.trim() || undefined,
          sortDirection:
            params.sortDirection === 'ASC' || params.sortDirection === 'DESC'
              ? params.sortDirection
              : undefined,
        },
        { skipErrorHandler: true },
      );
      if (!res || res.success === false || !Array.isArray(res.data)) {
        throw new Error('管理员待审核列表响应异常');
      }
      // 新查询开始后，迟到的旧结果不能覆盖列表或清除新告警。
      if (sequence !== listRequestSequence.current) {
        return { data: [], success: false, total: 0 };
      }
      setListNotice(res.incomplete
        ? { type: 'warning', text: res.warningMessage || '待审核列表未完整加载，请重试' }
        : undefined);
      remote = res.data;
      total = res?.total ?? remote.length;
    } catch (error: unknown) {
      if (sequence === listRequestSequence.current) {
        setListNotice({ type: 'error', text: getApiErrorMessage(error, '加载待审核队列失败') });
      }
      // ProTable 的 success=false 会保留旧表；本地登记不能代替远端读取成功。
      return { data: [], success: false, total: 0 };
    }

    const remoteIds = new Set(remote.map((item) => item.codeVersionId));
    const localOnly =
      current === 1 && approvalStatus === 'PENDING'
        ? listPendingCodeVersions()
            .filter((item) => {
              const status = String(
                item.approvalStatus || 'PENDING',
              ).toUpperCase();
              return status === 'PENDING' && !remoteIds.has(item.codeVersionId);
            })
            .map(manualRecordToRow)
        : [];

    const data = [...localOnly, ...remote];
    return {
      data,
      success: true,
      total: total + localOnly.length,
    };
  };

  const handleApprove = async (record: CodeVersionListItem) => {
    try {
      try {
        await checkCodeVersionForTraining(
          record.codeVersionId,
          record.trainingProfile || CONSISTENCY_TRAINING_PROFILE,
          { skipErrorHandler: true },
        );
      } catch {
        // 校验失败时仍尝试审核
      }
      const res = await approveCodeVersion(record.codeVersionId, {
        skipErrorHandler: true,
      });
      if (res?.success === false) {
        message.error(res?.errorMessage || '审核失败');
        return;
      }
      markPendingCodeApproved(record.codeVersionId);
      message.success('审核通过，已可在「训练代码」列表中查看');
      actionRef.current?.reload();
    } catch (error: unknown) {
      message.error(getApiErrorMessage(error, '审核失败'));
    }
  };

  const handleReject = async (record: CodeVersionListItem) => {
    setRejectingId(record.codeVersionId);
    try {
      const res = await rejectCodeVersion(record.codeVersionId, '已拒绝', {
        skipErrorHandler: true,
      });
      if (res?.success === false) {
        message.error(res?.errorMessage || '拒绝失败');
        return;
      }
      // 只清本机登记，勿写入 REJECTED 空壳（否则会混进管理员本人训练代码列表）
      removePendingCodeVersion(record.codeVersionId);
      message.success('已拒绝该训练代码版本');
      actionRef.current?.reload();
    } catch (error: unknown) {
      message.error(getApiErrorMessage(error, '拒绝失败'));
    } finally {
      setRejectingId(null);
    }
  };

  const openRevoke = (record: CodeVersionListItem) => {
    setRevokeTarget(record);
    revokeForm.resetFields();
    setRevokeOpen(true);
  };

  const handleRevoke = async () => {
    if (!revokeTarget) return;
    const values = await revokeForm.validateFields();
    setRevoking(true);
    try {
      const res = await revokeCodeVersion(
        revokeTarget.codeVersionId,
        String(values.reason || '').trim(),
        { skipErrorHandler: true },
      );
      if (res?.success === false) {
        message.error(res?.errorMessage || '撤销失败');
        return;
      }
      removePendingCodeVersion(revokeTarget.codeVersionId);
      message.success('已撤销该版本的批准');
      setRevokeOpen(false);
      setRevokeTarget(null);
      actionRef.current?.reload();
    } catch (error: unknown) {
      message.error(getApiErrorMessage(error, '撤销失败'));
    } finally {
      setRevoking(false);
    }
  };

  const loadReviewPreview = async (versionId: string, path: string) => {
    setReviewSelectedPath(path);
    setReviewPreviewLoading(true);
    try {
      const res = await previewAdminCodeReviewFile(versionId, path, {
        skipErrorHandler: true,
      });
      setReviewPreviewContent(res.data?.content || '');
    } catch (error: unknown) {
      setReviewPreviewContent('');
      message.error(getApiErrorMessage(error, '读取文件失败'));
    } finally {
      setReviewPreviewLoading(false);
    }
  };

  const openReviewDrawer = async (record: CodeVersionListItem) => {
    setReviewOpen(true);
    setReviewLoading(true);
    setReviewDetail(null);
    setReviewRaw(null);
    setReviewFiles([]);
    setReviewPreviewContent('');
    setReviewSelectedPath(undefined);
    try {
      const res = await getAdminCodeReviewDetail(record.codeVersionId, {
        skipErrorHandler: true,
      });
      setReviewDetail(res.data);
      setReviewRaw(res.raw || null);
      const filesRes = await listAdminCodeReviewFiles(record.codeVersionId, {
        skipErrorHandler: true,
      });
      const files = filesRes.data || [];
      setReviewFiles(files);
      const treeData = buildCodeFileTreeData(files);
      setReviewExpandedKeys(collectCodeFileTreeExpandedKeys(treeData));
      if (files[0]?.path) {
        await loadReviewPreview(record.codeVersionId, files[0].path);
      }
    } catch (error: unknown) {
      message.error(getApiErrorMessage(error, '加载审核详情失败'));
    } finally {
      setReviewLoading(false);
    }
  };

  const reviewTreeData = useMemo(
    () => buildCodeFileTreeData(reviewFiles),
    [reviewFiles],
  );

  const handleReviewDownloadFile = async () => {
    const versionId = reviewDetail?.codeVersionId;
    const path = reviewSelectedPath;
    if (!versionId || !path) {
      message.warning('请先选择要下载的文件');
      return;
    }
    setReviewDownloading(true);
    try {
      await downloadAdminCodeVersionFile(
        versionId,
        path,
        path.split('/').pop(),
        { skipErrorHandler: true },
      );
      message.success('已开始下载');
    } catch (error: unknown) {
      message.error(getApiErrorMessage(error, '下载失败'));
    } finally {
      setReviewDownloading(false);
    }
  };

  const handleRescan = async (record: CodeVersionListItem) => {
    try {
      await rescanCodeReviewTask(record.codeVersionId, {
        skipErrorHandler: true,
      });
      message.success('已触发风险重扫');
      actionRef.current?.reload();
    } catch (error: unknown) {
      message.error(getApiErrorMessage(error, '重扫失败'));
    }
  };

  const handleUpgrade = async (record: CodeVersionListItem) => {
    try {
      const res = await upgradeCodeArtifact(record.codeVersionId, {
        skipErrorHandler: true,
      });
      message.success(
        res?.data?.upgraded === false
          ? '制品已是规范路径，无需升级'
          : '制品升级完成，请重新审核',
      );
      actionRef.current?.reload();
    } catch (error: unknown) {
      message.error(getApiErrorMessage(error, '制品升级失败'));
    }
  };

  const handleOpenFindings = async (record: CodeVersionListItem) => {
    setFindingsTitle(record.codeAssetName || record.codeVersionId);
    setFindingsOpen(true);
    setFindingsLoading(true);
    setFindings([]);
    try {
      const res = await fetchAdminCodeFindings(record.codeVersionId, {
        skipErrorHandler: true,
      });
      setFindings(res.data || []);
    } catch (error: unknown) {
      message.error(getApiErrorMessage(error, 'Findings 加载失败'));
    } finally {
      setFindingsLoading(false);
    }
  };

  const handleManualAdd = async () => {
    const values = await addForm.validateFields();
    setAdding(true);
    try {
      const codeVersionId = String(values.codeVersionId || '').trim();
      upsertPendingCodeVersion({
        codeVersionId,
        codeAssetName: values.codeAssetName?.trim(),
        fileName: values.fileName?.trim(),
        trainingProfile:
          values.trainingProfile?.trim() || CONSISTENCY_TRAINING_PROFILE,
        approvalStatus: 'PENDING',
        source: 'manual',
        uploadedAt: new Date().toISOString(),
      });
      message.success('已加入待审核列表');
      setAddOpen(false);
      addForm.resetFields();
      actionRef.current?.reload();
    } finally {
      setAdding(false);
    }
  };

  const columns: ProColumns<CodeVersionListItem>[] = [
    {
      title: '关键词',
      dataIndex: 'keyword',
      hideInTable: true,
      fieldProps: { placeholder: '代码名称 / 文件名' },
    },
    {
      title: '审核状态',
      dataIndex: 'approvalStatus',
      hideInTable: true,
      valueType: 'select',
      initialValue: 'PENDING',
      valueEnum: {
        PENDING: { text: '待审核' },
        APPROVED: { text: '已通过' },
        REJECTED: { text: '已拒绝' },
        REVOKED: { text: '已撤销' },
      },
      fieldProps: {
        onChange: (v: string) => setApprovalStatusFilter(v || 'PENDING'),
      },
    },
    {
      title: '归属用户',
      dataIndex: 'ownerUserId',
      hideInTable: true,
      fieldProps: { placeholder: '用户名或 ownerUserId' },
    },
    {
      title: '提交起',
      dataIndex: 'submittedFrom',
      hideInTable: true,
      valueType: 'dateTime',
    },
    {
      title: '提交止',
      dataIndex: 'submittedTo',
      hideInTable: true,
      valueType: 'dateTime',
    },
    {
      title: '排序',
      dataIndex: 'sortBy',
      hideInTable: true,
      valueType: 'select',
      valueEnum: {
        SUBMITTED_AT: { text: '提交时间' },
        VERSION: { text: '版本' },
        RISK_LEVEL: { text: '风险等级' },
        OWNER_USER_ID: { text: 'owner' },
      },
    },
    {
      title: '排序方向',
      dataIndex: 'sortDirection',
      hideInTable: true,
      valueType: 'select',
      initialValue: 'DESC',
      valueEnum: {
        DESC: { text: '降序（新→旧）' },
        ASC: { text: '升序（旧→新）' },
      },
    },
    {
      title: '代码名称',
      dataIndex: 'codeAssetName',
      hideInSearch: true,
      ellipsis: false,
      onCell: () => ({ style: { whiteSpace: 'nowrap' } }),
      render: (_, r) => getCodeUserDisplayName(r),
    },
    {
      title: '归属用户',
      dataIndex: 'ownerUserId',
      width: 150,
      hideInSearch: true,
      ellipsis: true,
      render: (_, r) => formatOwnerUserLabel(r.ownerUserId, ownerUsernameMap),
    },
    {
      title: '文件名',
      dataIndex: 'fileName',
      hideInSearch: true,
      ellipsis: false,
      onCell: () => ({ style: { whiteSpace: 'nowrap' } }),
      render: (_, r) => r.fileName || '-',
    },
    {
      title: '训练方案',
      dataIndex: 'trainingProfile',
      hideInSearch: true,
      ellipsis: false,
      onCell: () => ({ style: { whiteSpace: 'nowrap' } }),
      render: (_, r) => r.trainingProfile || CONSISTENCY_TRAINING_PROFILE,
    },
    {
      title: '校验',
      dataIndex: 'validationStatus',
      width: 90,
      hideInSearch: true,
      render: (_, r) => r.validationStatus || '-',
    },
    {
      title: '风险等级',
      dataIndex: 'riskLevel',
      width: 100,
      valueType: 'select',
      valueEnum: {
        LOW: { text: 'LOW' },
        MEDIUM: { text: 'MEDIUM' },
        HIGH: { text: 'HIGH' },
        UNKNOWN: { text: 'UNKNOWN' },
      },
      render: (_, r) => riskLevelTag(r.riskLevel),
    },
    {
      title: '风险扫描',
      dataIndex: 'riskStatus',
      width: 110,
      hideInSearch: true,
      render: (_, r) => r.riskStatus || '-',
    },
    {
      title: '审核状态',
      dataIndex: 'approvalStatus',
      width: 110,
      hideInSearch: true,
      render: (_, r) => approvalTag(r.approvalStatus),
    },
    {
      title: '提交时间',
      dataIndex: 'submittedAt',
      width: 180,
      hideInSearch: true,
      render: (_, r) => formatDisplayDateTime(r.submittedAt),
    },
    {
      title: '版本编号',
      dataIndex: 'codeVersionId',
      hideInSearch: true,
      width: 220,
      render: (_, r) => (
        <Typography.Text copyable code style={{ fontSize: 12 }}>
          {r.codeVersionId}
        </Typography.Text>
      ),
    },
    {
      title: '操作',
      key: 'action',
      width: 220,
      fixed: 'right',
      hideInSearch: true,
      render: (_, record) => {
        const status = String(record.approvalStatus || '').toUpperCase();
        const isPending = status === 'PENDING' || !status;
        const isApproved = status === 'APPROVED';
        return (
          <Space size={0} style={{ whiteSpace: 'nowrap' }}>
            {isPending ? (
              <>
                <Button
                  type="link"
                  style={{ paddingLeft: 0 }}
                  onClick={() => void handleApprove(record)}
                >
                  通过
                </Button>
                <Popconfirm
                  title="确认拒绝该训练代码版本？"
                  okText="确认拒绝"
                  cancelText="取消"
                  okButtonProps={{
                    danger: true,
                    loading: rejectingId === record.codeVersionId,
                  }}
                  onConfirm={() => void handleReject(record)}
                >
                  <Button type="link" danger>
                    拒绝
                  </Button>
                </Popconfirm>
              </>
            ) : null}
            {isApproved ? (
              <Button type="link" danger onClick={() => openRevoke(record)}>
                撤销
              </Button>
            ) : null}
            <Button type="link" onClick={() => void openReviewDrawer(record)}>
              审核预览
            </Button>
            <Dropdown
              menu={{
                items: [
                  {
                    key: 'findings',
                    label: 'Findings',
                    onClick: () => void handleOpenFindings(record),
                  },
                  {
                    key: 'rescan',
                    label: '重扫',
                    onClick: () => {
                      Modal.confirm({
                        title: '触发风险重扫？',
                        content:
                          '会生成新风险证据，已批准版本可能回到 PENDING。',
                        okText: '重扫',
                        onOk: () => handleRescan(record),
                      });
                    },
                  },
                  {
                    key: 'upgrade',
                    label: '制品升级',
                    onClick: () => {
                      Modal.confirm({
                        title: '执行制品升级？',
                        content: '仅用于历史路径迁移，成功后需重新校验/审核。',
                        okText: '升级',
                        onOk: () => handleUpgrade(record),
                      });
                    },
                  },
                ],
              }}
            >
              <Button type="link">更多</Button>
            </Dropdown>
          </Space>
        );
      },
    },
  ];

  if (!access.isAdmin) {
    return null;
  }

  return (
    <PageContainer
      title="训练代码待审核"
      subTitle="处理需要人工审核的训练代码，并查看风险扫描结果"
      breadcrumb={{
        items: [
          {
            title: (
              <a onClick={() => history.push('/task/code/list')}>训练代码</a>
            ),
          },
          { title: '待审核' },
        ],
      }}
      extra={[
        <Button key="add" onClick={() => setAddOpen(true)}>
          手工登记版本
        </Button>,
        <Button
          key="list"
          type="primary"
          onClick={() => history.push('/task/code/list')}
        >
          训练代码
        </Button>,
      ]}
    >
      <Alert
        type={isTrainingCodeAutoApproveEnabled() ? 'warning' : 'info'}
        showIcon
        style={{ marginBottom: 16 }}
        message="当前审核方式"
        description={
          <span>
            {isTrainingCodeAutoApproveEnabled() ? (
              <>
                当前系统配置中「训练代码审核」为关闭：新上传/发布的代码在基础校验通过后直接批准。
                本页仍保留完整人工审核与运维能力；若要启用强制人工审核，请到{' '}
                <a onClick={() => history.push('/system/config')}>
                  系统管理 · 系统配置
                </a>{' '}
                打开该开关。{' '}
              </>
            ) : (
              <>当前已开启「训练代码审核」；需要人工处理的版本会进入本页。 </>
            )}
            如需查找已自动通过的版本，请切换审核状态，或到{' '}
            <a onClick={() => history.push('/task/code/admin-assets')}>
              代码资产管理
            </a>{' '}
            按名称搜索。重扫与制品升级仅用于异常处理。
          </span>
        }
      />
      {listNotice && (
        <Alert
          type={listNotice.type}
          showIcon
          style={{ marginBottom: 16 }}
          message={listNotice.text}
          description={listNotice.type === 'error'
            ? '本次查询未成功；如表格仍有记录，展示的是上次查询结果，不代表当前筛选条件下的最新队列。'
            : '当前展示已读取的记录，不能据此判断其它待审核代码不存在。'}
          action={<Button size="small" onClick={() => actionRef.current?.reload()}>重试加载</Button>}
        />
      )}
      <ProTable<CodeVersionListItem>
        actionRef={actionRef}
        columns={columns}
        request={requestList}
        rowKey="codeVersionId"
        search={{ labelWidth: 'auto' }}
        // 只设初始值，让用户选择的每页条数与实际请求、表格展示保持一致。
        pagination={{ defaultPageSize: 10, showSizeChanger: true }}
        tableLayout="auto"
        scroll={{ x: 'max-content' }}
        toolBarRender={() => [
          <Button key="reload" onClick={() => actionRef.current?.reload()}>
            刷新
          </Button>,
        ]}
      />

      <Modal
        title="手工登记待审核代码"
        open={addOpen}
        onCancel={() => setAddOpen(false)}
        onOk={handleManualAdd}
        confirmLoading={adding}
        destroyOnClose
      >
        <Form form={addForm} layout="vertical">
          <Form.Item
            name="codeVersionId"
            label="版本编号"
            rules={[{ required: true, message: '请输入版本编号' }]}
          >
            <Input placeholder="例如：code-version-xxxx" />
          </Form.Item>
          <Form.Item name="codeAssetName" label="代码名称（可选）">
            <Input />
          </Form.Item>
          <Form.Item name="fileName" label="文件名（可选）">
            <Input />
          </Form.Item>
          <Form.Item
            name="trainingProfile"
            label="训练方案"
            initialValue={CONSISTENCY_TRAINING_PROFILE}
          >
            <Input />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="撤销已批准版本"
        open={revokeOpen}
        onCancel={() => setRevokeOpen(false)}
        onOk={() => void handleRevoke()}
        confirmLoading={revoking}
        okText="确认撤销"
        okButtonProps={{ danger: true }}
        destroyOnClose
      >
        <Form form={revokeForm} layout="vertical">
          <Form.Item
            name="reason"
            label="撤销原因"
            rules={[{ required: true, message: '请填写撤销原因' }]}
          >
            <Input.TextArea rows={4} placeholder="说明撤销理由（必填）" />
          </Form.Item>
        </Form>
      </Modal>

      <Drawer
        title={`审核预览 · ${reviewDetail?.codeAssetName || reviewDetail?.codeVersionId || ''}`}
        open={reviewOpen}
        onClose={() => setReviewOpen(false)}
        width={1080}
        destroyOnClose
      >
        <Spin spinning={reviewLoading}>
          {reviewDetail ? (
            <>
              <Descriptions
                size="small"
                column={2}
                style={{ marginBottom: 16 }}
              >
                <Descriptions.Item label="版本编号" span={2}>
                  <Typography.Text copyable code style={{ fontSize: 12 }}>
                    {reviewDetail.codeVersionId}
                  </Typography.Text>
                </Descriptions.Item>
                <Descriptions.Item label="审核">
                  {approvalTag(reviewDetail.approvalStatus)}
                </Descriptions.Item>
                <Descriptions.Item label="校验">
                  {reviewDetail.validationStatus || '-'}
                </Descriptions.Item>
                <Descriptions.Item label="风险">
                  {riskLevelTag(reviewDetail.riskLevel)}
                </Descriptions.Item>
                <Descriptions.Item label="扫描">
                  {reviewDetail.riskStatus || '-'}
                </Descriptions.Item>
                <Descriptions.Item label="artifactSha256" span={2}>
                  <Typography.Text code style={{ fontSize: 11 }}>
                    {reviewDetail.artifactSha256 || '-'}
                  </Typography.Text>
                </Descriptions.Item>
                {reviewRaw?.riskAssessment ? (
                  <>
                    <Descriptions.Item label="validationRunId">
                      <Typography.Text code style={{ fontSize: 11 }}>
                        {reviewRaw.riskAssessment.validationRunId || '-'}
                      </Typography.Text>
                    </Descriptions.Item>
                    <Descriptions.Item label="riskAssessmentId">
                      <Typography.Text code style={{ fontSize: 11 }}>
                        {reviewRaw.riskAssessment.id || '-'}
                      </Typography.Text>
                    </Descriptions.Item>
                    <Descriptions.Item label="policyVersion" span={2}>
                      <Typography.Text code style={{ fontSize: 11 }}>
                        {reviewRaw.riskAssessment.riskPolicyVersion ||
                          reviewDetail.riskPolicyVersion ||
                          '-'}
                      </Typography.Text>
                    </Descriptions.Item>
                  </>
                ) : null}
              </Descriptions>
              {reviewFiles.length === 0 ? (
                <Empty description="暂无文件" />
              ) : (
                <Row gutter={16}>
                  <Col span={8}>
                    <Typography.Text strong>审核目录树</Typography.Text>
                    <div
                      style={{
                        marginTop: 8,
                        maxHeight: '65vh',
                        overflow: 'auto',
                      }}
                    >
                      <Tree
                        treeData={reviewTreeData as DataNode[]}
                        selectedKeys={
                          reviewSelectedPath ? [reviewSelectedPath] : []
                        }
                        expandedKeys={reviewExpandedKeys}
                        onExpand={(keys) => setReviewExpandedKeys(keys)}
                        onSelect={(keys, info) => {
                          if (!reviewDetail?.codeVersionId || !info.node.isLeaf)
                            return;
                          const path = String(keys[0] || '');
                          if (path) {
                            void loadReviewPreview(
                              reviewDetail.codeVersionId,
                              path,
                            );
                          }
                        }}
                      />
                    </div>
                  </Col>
                  <Col span={16}>
                    <Space
                      style={{
                        width: '100%',
                        justifyContent: 'space-between',
                        marginBottom: 8,
                      }}
                    >
                      <Typography.Text strong>
                        预览
                        {reviewSelectedPath ? ` · ${reviewSelectedPath}` : ''}
                      </Typography.Text>
                      {reviewSelectedPath ? (
                        <Button
                          size="small"
                          icon={<DownloadOutlined />}
                          loading={reviewDownloading}
                          onClick={() => void handleReviewDownloadFile()}
                        >
                          下载
                        </Button>
                      ) : null}
                    </Space>
                    <Spin spinning={reviewPreviewLoading}>
                      <pre
                        style={{
                          marginTop: 0,
                          padding: 12,
                          background: '#fafafa',
                          border: '1px solid #f0f0f0',
                          borderRadius: 6,
                          maxHeight: '65vh',
                          overflow: 'auto',
                          whiteSpace: 'pre-wrap',
                          wordBreak: 'break-word',
                          minHeight: 200,
                        }}
                      >
                        {reviewPreviewContent || '选择左侧文件查看内容'}
                      </pre>
                    </Spin>
                  </Col>
                </Row>
              )}
            </>
          ) : (
            !reviewLoading && <Empty description="无审核详情" />
          )}
        </Spin>
      </Drawer>

      <Drawer
        title={`风险 Findings · ${findingsTitle}`}
        open={findingsOpen}
        onClose={() => setFindingsOpen(false)}
        width={720}
      >
        <Table<FindingRow>
          size="small"
          loading={findingsLoading}
          rowKey={(r, idx) => r.id || `${r.ruleId}-${idx}`}
          dataSource={findings}
          pagination={false}
          locale={{ emptyText: '暂无 findings' }}
          columns={[
            { title: '规则', dataIndex: 'ruleId', width: 140, ellipsis: true },
            { title: '等级', dataIndex: 'severity', width: 90 },
            {
              title: '类别',
              dataIndex: 'category',
              width: 100,
              ellipsis: true,
            },
            {
              title: '位置',
              key: 'loc',
              ellipsis: true,
              render: (_, r) =>
                `${r.filePath || '-'}${
                  r.lineStart != null ? `:${r.lineStart}` : ''
                }`,
            },
            {
              title: '说明',
              dataIndex: 'description',
              ellipsis: true,
            },
          ]}
        />
      </Drawer>
    </PageContainer>
  );
};

export default TrainingCodePending;
