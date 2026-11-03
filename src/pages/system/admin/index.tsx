import { DeleteOutlined, EditOutlined } from '@ant-design/icons';
import type { ActionType, ProColumns } from '@ant-design/pro-components';
import { PageContainer, ProTable } from '@ant-design/pro-components';
import { history, useAccess, useModel } from '@umijs/max';
import {
  Button,
  Form,
  Input,
  Modal,
  message,
  Popconfirm,
  Select,
  Space,
} from 'antd';
import dayjs from 'dayjs';
import React, { useEffect, useMemo, useRef, useState } from 'react';
import {
  SYSTEM_ADMIN_ROLE_OPTIONS,
  SYSTEM_ROLES,
  SYSTEM_STATUS,
  SYSTEM_STATUS_OPTIONS,
} from '@/constants/systemLabels';
import {
  ADMIN_ROLE_NAMES,
  type AdminItem,
  type AdminListParams,
  addAdmin,
  checkAdminUsername,
  deleteAdmin,
  editAdmin,
  fetchAdminList,
  toggleAdminStatus,
} from '@/services/system';
import { toProTableFail, toProTableSuccess, withIndex } from '@/utils/proTable';
import {
  guardDeleteCurrentAccount,
  guardDisableCurrentAccount,
  isCurrentLoginAccount,
} from '../guardSelfAccount';
import { notifyRequestError } from '../notifyRequestError';
import { showTemporaryPasswordNotice } from '../TemporaryPasswordNotice';

/**
 * 管理员列表页
 * 权限：需 canAccessSystemAdmin，否则跳转 403
 * 能力：管理员账号的新增 / 编辑 / 启用禁用 / 删除；列表走 fetchAdminList（基于用户接口筛管理员）
 * 约束：不可禁用或删除当前登录账号；表单角色选项不含「新建超管」时仅回显已有超管
 */

// 前端不提供“超管”选项；若数据中存在超管，仅用于回显（禁用）
const ADMIN_ROLE_OPTIONS = SYSTEM_ADMIN_ROLE_OPTIONS;

const AdminListPage: React.FC = () => {
  const access = useAccess();
  const { initialState } = useModel('@@initialState');
  const currentUser = initialState?.currentUser;
  const actionRef = useRef<ActionType>(null);
  const [form] = Form.useForm();
  const [modalVisible, setModalVisible] = useState(false);
  const [editingUser, setEditingUser] = useState<AdminItem | null>(null);
  const [_usernameChecking, setUsernameChecking] = useState(false);

  useEffect(() => {
    if (!access.canAccessSystemAdmin) {
      history.replace('/403');
    }
  }, [access.canAccessSystemAdmin]);

  const validateUsername = async (_: unknown, value: string) => {
    if (!value) return Promise.reject(new Error('用户名不能为空'));
    if (editingUser && value === editingUser.username) return Promise.resolve();
    setUsernameChecking(true);
    try {
      const response = await checkAdminUsername(value);
      setUsernameChecking(false);
      if (response.code === 200 && response.data?.available !== false) {
        return Promise.resolve();
      }
      return Promise.reject(new Error('用户名已存在'));
    } catch (e) {
      setUsernameChecking(false);
      const err = e as {
        response?: { data?: { message?: string } };
        message?: string;
      };
      if (
        err?.response?.data?.message?.includes('已存在') ||
        err?.message?.includes('已存在')
      ) {
        return Promise.reject(new Error('用户名已存在'));
      }
      return Promise.resolve();
    }
  };

  const handleAdd = () => {
    setEditingUser(null);
    form.resetFields();
    form.setFieldsValue({
      status: SYSTEM_STATUS.ENABLED,
      role: SYSTEM_ROLES.NORMAL_ADMIN,
    });
    setModalVisible(true);
  };

  const handleEdit = (record: AdminItem) => {
    setEditingUser(record);
    form.setFieldsValue({
      id: record.id,
      username: record.username,
      phone: record.phone,
      role: record.role,
      status: record.status,
    });
    setModalVisible(true);
  };

  const isEditingSuperAdmin = editingUser?.role === SYSTEM_ROLES.SUPER_ADMIN;

  const adminRoleOptionsForForm = useMemo(() => {
    if (isEditingSuperAdmin) {
      return [
        { label: SYSTEM_ROLES.SUPER_ADMIN, value: SYSTEM_ROLES.SUPER_ADMIN },
      ];
    }
    if (editingUser) {
      return [
        ...ADMIN_ROLE_OPTIONS,
        { label: SYSTEM_ROLES.USER, value: SYSTEM_ROLES.USER },
      ];
    }
    return [...ADMIN_ROLE_OPTIONS];
  }, [editingUser, isEditingSuperAdmin]);

  const handleSubmit = async (values: Record<string, unknown>) => {
    try {
      const username = values.username as string;
      const phone = values.phone as string;
      const role = values.role as string;
      const status = values.status as string;

      if (!editingUser && !ADMIN_ROLE_NAMES.includes(role as any)) {
        message.error('新增管理员时角色仅支持：超管 / 普通管理员');
        return;
      }

      if (editingUser) {
        if (!guardDisableCurrentAccount(editingUser, status, currentUser)) {
          return;
        }
        const submitRole = isEditingSuperAdmin
          ? SYSTEM_ROLES.SUPER_ADMIN
          : role;
        const response = await editAdmin({
          id: editingUser.id,
          username,
          phone,
          role: submitRole,
          status,
        });
        if (response.code === 200) {
          message.success('编辑成功');
          setModalVisible(false);
          form.resetFields();
          actionRef.current?.reload();
        }
      } else {
        const response = await addAdmin({
          username,
          phone,
          role,
          status,
        });
        if (response.code === 200) {
          setModalVisible(false);
          form.resetFields();
          actionRef.current?.reloadAndRest?.();
          showTemporaryPasswordNotice(response.data?.temporaryPassword);
        }
      }
    } catch (error: unknown) {
      notifyRequestError(error, '操作失败');
    }
  };

  const handleDelete = async (record: AdminItem) => {
    if (!guardDeleteCurrentAccount(record, currentUser)) {
      return;
    }
    if (record.role === SYSTEM_ROLES.SUPER_ADMIN) {
      message.warning('敏感操作：删除超管账号需二次确认');
    }
    try {
      const response = await deleteAdmin(record.id);
      if (response.code === 200) {
        message.success('删除成功');
        actionRef.current?.reload();
      }
    } catch (error: unknown) {
      notifyRequestError(error, '删除失败');
    }
  };

  const handleToggleStatus = async (record: AdminItem, newStatus: string) => {
    if (!guardDisableCurrentAccount(record, newStatus, currentUser)) {
      return;
    }
    if (record.role === SYSTEM_ROLES.SUPER_ADMIN) {
      message.warning('敏感操作：禁用超管账号需二次确认');
    }
    try {
      const response = await toggleAdminStatus(record.id, newStatus);
      if (response.code === 200) {
        message.success(
          newStatus === SYSTEM_STATUS.DISABLED ? '已禁用' : '已启用',
        );
        actionRef.current?.reload();
      }
    } catch (error: unknown) {
      notifyRequestError(error, '操作失败');
    }
  };

  const columns = useMemo<ProColumns<AdminItem>[]>(() => {
    return [
      {
        title: '序号',
        dataIndex: '_index',
        key: '_index',
        width: 80,
        align: 'center',
        hideInSearch: true,
      },
      {
        title: '用户名',
        dataIndex: 'username',
        align: 'center',
        fieldProps: {
          placeholder: '请输入用户名',
          onPressEnter: () => actionRef.current?.reload(),
        },
      },
      {
        title: '手机号',
        dataIndex: 'phone',
        align: 'center',
        fieldProps: {
          placeholder: '请输入手机号',
          onPressEnter: () => actionRef.current?.reload(),
        },
      },
      {
        title: '角色',
        dataIndex: 'role',
        align: 'center',
        hideInSearch: true,
        renderText: (t) => t,
      },
      {
        title: '状态',
        dataIndex: 'status',
        align: 'center',
        valueType: 'select',
        valueEnum: {
          [SYSTEM_STATUS.ENABLED]: {
            text: SYSTEM_STATUS.ENABLED,
            status: 'Success',
          },
          [SYSTEM_STATUS.DISABLED]: {
            text: SYSTEM_STATUS.DISABLED,
            status: 'Error',
          },
        },
        fieldProps: { placeholder: '请选择状态' },
      },
      {
        title: '创建时间',
        dataIndex: 'createTime',
        align: 'center',
        valueType: 'date',
        search: { transform: (value: string) => ({ createTime: value }) },
        renderText: (t) => t,
      },
      {
        title: '操作',
        key: 'action',
        width: 240,
        align: 'center',
        hideInSearch: true,
        render: (_, record) => {
          const isSelf = isCurrentLoginAccount(record, currentUser);
          return (
            <Space>
              <Button
                type="link"
                size="small"
                icon={<EditOutlined />}
                onClick={() => handleEdit(record)}
              >
                编辑
              </Button>
              <Button
                type="link"
                size="small"
                disabled={isSelf && record.status === SYSTEM_STATUS.ENABLED}
                title={
                  isSelf && record.status === SYSTEM_STATUS.ENABLED
                    ? '不能禁用当前登录账号'
                    : undefined
                }
                onClick={() =>
                  handleToggleStatus(
                    record,
                    record.status === SYSTEM_STATUS.ENABLED
                      ? SYSTEM_STATUS.DISABLED
                      : SYSTEM_STATUS.ENABLED,
                  )
                }
              >
                {record.status === SYSTEM_STATUS.ENABLED
                  ? SYSTEM_STATUS.DISABLED
                  : SYSTEM_STATUS.ENABLED}
              </Button>
              <Popconfirm
                title={`确定删除管理员「${record.username}」吗？`}
                description="删除后不可恢复，请谨慎操作。"
                onConfirm={() => handleDelete(record)}
                okText="确认"
                cancelText="取消"
                okButtonProps={{ danger: true }}
                disabled={isSelf}
              >
                <Button
                  type="link"
                  size="small"
                  danger
                  icon={<DeleteOutlined />}
                  disabled={isSelf}
                  title={isSelf ? '不能删除当前登录账号' : undefined}
                >
                  删除
                </Button>
              </Popconfirm>
            </Space>
          );
        },
      },
    ];
  }, [currentUser]);

  if (!access.canAccessSystemAdmin) return null;

  return (
    <PageContainer
      title="管理员列表"
      subTitle="管理系统管理员账号，支持新增、编辑、启用/禁用等操作。"
      extra={
        <Space>
          <Button type="primary" onClick={handleAdd}>
            新增管理员
          </Button>
        </Space>
      }
    >
      <ProTable<AdminItem>
        actionRef={actionRef}
        columns={columns}
        rowKey="id"
        toolBarRender={false}
        request={async (params, _sort, _filter) => {
          try {
            const {
              current = 1,
              pageSize = 10,
              username,
              phone,
              status,
              createTime,
            } = params as {
              current?: number;
              pageSize?: number;
              username?: string;
              phone?: string;
              status?: string;
              createTime?: string;
            };
            const requestParams: AdminListParams = {
              pageNum: current,
              pageSize,
              username: username ?? '',
              phone: phone ?? '',
              role: '',
              status: status ?? '',
              createTime: createTime
                ? dayjs(createTime).format('YYYY-MM-DD')
                : '',
            };

            const res = await fetchAdminList(requestParams);
            if (res.code !== 200) {
              return toProTableFail<AdminItem>();
            }
            const list = withIndex(res.data?.list ?? [], current, pageSize);
            return toProTableSuccess(list, res.data?.total ?? list.length);
          } catch (_e: any) {
            return toProTableFail<AdminItem>();
          }
        }}
        pagination={{
          defaultPageSize: 10,
          showSizeChanger: true,
          showQuickJumper: true,
        }}
      />

      <Modal
        title={
          editingUser ? `编辑管理员 - ${editingUser.username}` : '新增管理员'
        }
        open={modalVisible}
        onCancel={() => {
          setModalVisible(false);
          form.resetFields();
          setEditingUser(null);
        }}
        onOk={() => form.submit()}
        okText="确定"
        cancelText="取消"
        destroyOnClose
      >
        <Form
          form={form}
          onFinish={handleSubmit}
          layout="vertical"
          preserve={false}
        >
          <Form.Item
            name="username"
            label="用户名"
            rules={[{ validator: validateUsername }]}
            validateTrigger="onBlur"
          >
            <Input placeholder="请输入用户名" />
          </Form.Item>
          <Form.Item
            name="phone"
            label="手机号"
            rules={[
              { required: true, message: '手机号不能为空' },
              { pattern: /^1[3-9]\d{9}$/, message: '手机号格式错误' },
            ]}
          >
            <Input placeholder="请输入手机号" maxLength={11} />
          </Form.Item>
          <Form.Item
            name="role"
            label="角色"
            rules={[{ required: true, message: '请选择角色' }]}
          >
            <Select
              placeholder="请选择角色"
              disabled={isEditingSuperAdmin}
              options={adminRoleOptionsForForm as any}
            />
          </Form.Item>
          <Form.Item
            name="status"
            label="状态"
            rules={[{ required: true, message: '请选择状态' }]}
            initialValue={SYSTEM_STATUS.ENABLED}
            extra={
              editingUser && isCurrentLoginAccount(editingUser, currentUser)
                ? '不能将当前登录账号改为禁用'
                : undefined
            }
          >
            <Select
              placeholder="请选择状态"
              options={SYSTEM_STATUS_OPTIONS as any}
              disabled={
                !!(
                  editingUser && isCurrentLoginAccount(editingUser, currentUser)
                )
              }
            />
          </Form.Item>
        </Form>
      </Modal>
    </PageContainer>
  );
};

export default AdminListPage;
