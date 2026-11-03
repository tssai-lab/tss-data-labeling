import {
  DeleteOutlined,
  EditOutlined,
  KeyOutlined,
  SafetyCertificateOutlined,
  UserSwitchOutlined,
} from '@ant-design/icons';
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
import React, { useEffect, useRef, useState } from 'react';
import {
  SYSTEM_ROLE_OPTIONS_NORMAL_ADMIN,
  SYSTEM_ROLE_OPTIONS_SUPER,
  SYSTEM_ROLES,
  SYSTEM_STATUS,
  SYSTEM_STATUS_OPTIONS,
} from '@/constants/systemLabels';
import {
  addUser,
  checkUsername,
  deleteUser,
  editUser,
  fetchUserList as fetchUserListService,
  promoteUserToNormalAdmin,
  resetUserPassword,
  toggleUserStatus,
  type UserItem,
  type UserListParams,
} from '@/services/system';
import { toProTableFail, toProTableSuccess, withIndex } from '@/utils/proTable';
import {
  guardDeleteCurrentAccount,
  guardDisableCurrentAccount,
  isCurrentLoginAccount,
} from '../guardSelfAccount';
import { notifyRequestError } from '../notifyRequestError';
import { showTemporaryPasswordNotice } from '../TemporaryPasswordNotice';
import ApiPolicyModal from './ApiPolicyModal';

/**
 * 用户管理页
 * 超管：可管理全部用户，可修改用户名、重置密码并指定普通管理员
 * 普管：仅管理普通用户；无权指定/调整管理员
 */
const UserManagement: React.FC = () => {
  const access = useAccess();
  const { initialState } = useModel('@@initialState');
  const currentUser = initialState?.currentUser;
  const [form] = Form.useForm();
  const [passwordForm] = Form.useForm();
  const [modalVisible, setModalVisible] = useState(false);
  const [editingUser, setEditingUser] = useState<UserItem | null>(null);
  const [passwordTarget, setPasswordTarget] = useState<UserItem | null>(null);
  const [_usernameChecking, setUsernameChecking] = useState(false);
  const [submitLoading, setSubmitLoading] = useState(false);
  const [passwordLoading, setPasswordLoading] = useState(false);
  const [apiPolicyTarget, setApiPolicyTarget] = useState<UserItem | null>(null);
  const actionRef = useRef<ActionType>(null);

  useEffect(() => {
    if (!access.canAccessSystemUser) {
      history.replace('/403');
    }
  }, [access.canAccessSystemUser]);

  /** 新增弹窗打开后重置表单并写入默认值（须在 Modal 内 Form 挂载后调用） */
  useEffect(() => {
    if (modalVisible && !editingUser) {
      form.resetFields();
      form.setFieldsValue({
        status: SYSTEM_STATUS.ENABLED,
        role: SYSTEM_ROLES.USER,
      });
    }
  }, [modalVisible, editingUser, form]);

  useEffect(() => {
    if (passwordTarget) passwordForm.resetFields();
  }, [passwordTarget, passwordForm]);

  if (!access.canAccessSystemUser) return null;

  const isSuperAdmin = access.isSuperAdmin;
  const canDelete = access.canUserDelete;
  const canRoleFilterAndAssignAdmin = access.canUserRoleFilterAndAssignAdmin;

  const fetchUserList = async (
    params: Record<string, unknown>,
    _sort: Record<string, any>,
    _filter: Record<string, any>,
  ) => {
    try {
      const {
        current = 1,
        pageSize = 10,
        username,
        phone,
        role,
        status,
        createTime,
      } = params as {
        current?: number;
        pageSize?: number;
        username?: string;
        phone?: string;
        role?: string;
        status?: string;
        createTime?: string;
      };

      const requestParams: UserListParams = {
        pageNum: current,
        pageSize,
        username: username ?? '',
        phone: phone ?? '',
        role: canRoleFilterAndAssignAdmin ? (role ?? '') : '',
        status: status ?? '',
        createTime: createTime ? dayjs(createTime).format('YYYY-MM-DD') : '',
        currentUserRole: isSuperAdmin ? 'super_admin' : 'normal_admin',
      };

      const response = await fetchUserListService(requestParams);
      if (response.code !== 200) {
        return toProTableFail<UserItem>();
      }
      const list = withIndex(response.data?.list ?? [], current, pageSize);
      return toProTableSuccess(list, response.data?.total ?? 0);
    } catch (_error: unknown) {
      return toProTableFail<UserItem>();
    }
  };

  const handleAdd = () => {
    setEditingUser(null);
    setModalVisible(true);
  };

  const handleEdit = (record: UserItem) => {
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

  const handleResetPassword = async () => {
    if (!passwordTarget || passwordLoading) return;
    try {
      const values = await passwordForm.validateFields();
      setPasswordLoading(true);
      const response = await resetUserPassword({
        userId: passwordTarget.id,
        newPassword: values.newPassword,
      });
      if (response.code !== 200) {
        message.error(response.message || '密码修改失败');
        return;
      }

      const changedSelf = isCurrentLoginAccount(passwordTarget, currentUser);
      setPasswordTarget(null);
      passwordForm.resetFields();
      message.success(
        changedSelf
          ? '密码修改成功，请使用新密码重新登录'
          : '密码修改成功，该账号需要重新登录',
      );
      if (changedSelf) history.replace('/user/login');
    } catch (error: unknown) {
      const err = error as { errorFields?: unknown[] };
      if (!err?.errorFields?.length) notifyRequestError(error, '密码修改失败');
    } finally {
      setPasswordLoading(false);
    }
  };

  const validateUsername = async (_: unknown, value: string) => {
    if (!value) return Promise.reject(new Error('用户名不能为空'));
    if (editingUser && value === editingUser.username) return Promise.resolve();
    setUsernameChecking(true);
    try {
      const response = await checkUsername(value);
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

  const handleSubmit = async (values: Record<string, unknown>) => {
    try {
      const username = values.username as string;
      const phone = values.phone as string;
      const role = values.role as string;
      const status = values.status as string;

      if (editingUser) {
        const submitRole = isEditingSuperAdmin
          ? SYSTEM_ROLES.SUPER_ADMIN
          : role;
        if (
          !canRoleFilterAndAssignAdmin &&
          (submitRole === SYSTEM_ROLES.SUPER_ADMIN ||
            submitRole === SYSTEM_ROLES.NORMAL_ADMIN)
        ) {
          message.error('无权限分配管理员角色');
          return;
        }
        // 用户管理中不允许把非超管提升为“超管”
        if (
          submitRole === SYSTEM_ROLES.SUPER_ADMIN &&
          editingUser.role !== SYSTEM_ROLES.SUPER_ADMIN
        ) {
          message.error('不支持在用户管理中将用户设置为超管');
          return;
        }
        if (!guardDisableCurrentAccount(editingUser, status, currentUser)) {
          return;
        }
        const response = await editUser({
          id: editingUser.id,
          username,
          phone,
          role: submitRole,
          status,
        });
        if (response.code === 200) {
          const changedSelfUsername =
            isCurrentLoginAccount(editingUser, currentUser) &&
            username !== editingUser.username;
          message.success(
            changedSelfUsername
              ? '用户名修改成功，请使用新用户名重新登录'
              : '编辑成功',
          );
          setModalVisible(false);
          form.resetFields();
          if (changedSelfUsername) {
            history.replace('/user/login');
            return;
          }
          actionRef.current?.reload();
          return;
        }
        message.error(response.message || '编辑失败');
      } else {
        if (
          !canRoleFilterAndAssignAdmin &&
          (role === SYSTEM_ROLES.SUPER_ADMIN ||
            role === SYSTEM_ROLES.NORMAL_ADMIN)
        ) {
          message.error('无权限分配管理员角色');
          return;
        }
        if (role === SYSTEM_ROLES.SUPER_ADMIN) {
          message.error('不支持在用户管理中创建超管账号');
          return;
        }
        const response = await addUser({
          username,
          phone,
          role,
          status,
        });
        if (response.code === 200) {
          setModalVisible(false);
          form.resetFields();
          actionRef.current?.reload();
          showTemporaryPasswordNotice(response.data?.temporaryPassword);
          return;
        }
        message.error(response.message || '新增失败');
      }
    } catch (error: unknown) {
      notifyRequestError(error, '操作失败');
      // BizError 已由 app.tsx 全局 errorHandler 提示
    }
  };

  const handleModalOk = async () => {
    if (submitLoading) return;
    try {
      const values = await form.validateFields();
      setSubmitLoading(true);
      await handleSubmit(values);
    } catch (error: unknown) {
      const err = error as { errorFields?: unknown[] };
      if (err?.errorFields?.length) {
        message.warning('请完善表单后再提交');
        return;
      }
    } finally {
      setSubmitLoading(false);
    }
  };

  const handleDelete = async (record: UserItem) => {
    if (!canDelete) {
      message.warning('无权限执行该操作');
      return;
    }
    if (!guardDeleteCurrentAccount(record, currentUser)) {
      return;
    }
    if (
      record.role === SYSTEM_ROLES.SUPER_ADMIN ||
      record.role === SYSTEM_ROLES.NORMAL_ADMIN
    ) {
      message.warning('敏感操作：删除管理员账号需二次确认');
    }
    try {
      const response = await deleteUser(record.id);
      if (response.code === 200) {
        message.success('删除成功');
        actionRef.current?.reload();
        return;
      }
      message.error(response.message || '删除失败');
    } catch (error: unknown) {
      notifyRequestError(error, '删除失败');
    }
  };

  const handleToggleStatus = async (record: UserItem, newStatus: string) => {
    if (!guardDisableCurrentAccount(record, newStatus, currentUser)) {
      return;
    }
    if (
      record.role === SYSTEM_ROLES.SUPER_ADMIN ||
      record.role === SYSTEM_ROLES.NORMAL_ADMIN
    ) {
      message.warning('敏感操作：禁用管理员账号需二次确认');
    }
    try {
      const response = await toggleUserStatus(record.id, newStatus);
      if (response.code === 200) {
        message.success(
          newStatus === SYSTEM_STATUS.DISABLED ? '已禁用' : '已启用',
        );
        actionRef.current?.reload();
        return;
      }
      message.error(response.message || '状态切换失败');
    } catch (error: unknown) {
      notifyRequestError(error, '状态切换失败');
    }
  };

  const handlePromoteToAdmin = async (record: UserItem) => {
    try {
      const res = await promoteUserToNormalAdmin({ userId: record.id });
      if (res.code === 200) {
        message.success(
          (res as any).msg ?? (res as any).message ?? '已设为普通管理员',
        );
        actionRef.current?.reload();
        return;
      }
      message.error((res as any).message ?? (res as any).msg ?? '操作失败');
    } catch (error: unknown) {
      notifyRequestError(error, '操作失败');
    }
  };

  const isEditingSuperAdmin = editingUser?.role === SYSTEM_ROLES.SUPER_ADMIN;

  const roleOptions = canRoleFilterAndAssignAdmin
    ? SYSTEM_ROLE_OPTIONS_SUPER
    : SYSTEM_ROLE_OPTIONS_NORMAL_ADMIN;

  const roleOptionsForForm = isEditingSuperAdmin
    ? [{ label: SYSTEM_ROLES.SUPER_ADMIN, value: SYSTEM_ROLES.SUPER_ADMIN }]
    : roleOptions;

  const columns: ProColumns<UserItem>[] = [
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
      key: 'username',
      align: 'center',
      fieldProps: {
        placeholder: '请输入用户名',
        onPressEnter: () => actionRef.current?.reload(),
      },
    },
    {
      title: '手机号',
      dataIndex: 'phone',
      key: 'phone',
      align: 'center',
      fieldProps: {
        placeholder: '请输入手机号',
        onPressEnter: () => actionRef.current?.reload(),
      },
    },
    ...(canRoleFilterAndAssignAdmin
      ? [
          {
            title: '角色',
            dataIndex: 'role',
            key: 'role',
            align: 'center' as const,
            valueType: 'select' as const,
            valueEnum: {
              [SYSTEM_ROLES.SUPER_ADMIN]: { text: SYSTEM_ROLES.SUPER_ADMIN },
              [SYSTEM_ROLES.NORMAL_ADMIN]: { text: SYSTEM_ROLES.NORMAL_ADMIN },
              [SYSTEM_ROLES.USER]: { text: SYSTEM_ROLES.USER },
            },
            fieldProps: { placeholder: '请选择角色' },
          } as ProColumns<UserItem>,
        ]
      : []),
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
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
      key: 'createTime',
      align: 'center',
      valueType: 'date',
      search: { transform: (value: string) => ({ createTime: value }) },
    },
    {
      title: '操作',
      key: 'action',
      width: 280,
      align: 'center',
      hideInSearch: true,
      render: (_, record) => {
        const isSelf = isCurrentLoginAccount(record, currentUser);
        return (
          <Space>
            {isSuperAdmin && record.role !== SYSTEM_ROLES.SUPER_ADMIN && (
              <Button
                type="link"
                size="small"
                icon={<SafetyCertificateOutlined />}
                onClick={() => setApiPolicyTarget(record)}
              >
                API 权限
              </Button>
            )}
            {isSuperAdmin && record.role === SYSTEM_ROLES.USER && (
              <Popconfirm
                title={`将「${record.username}」设为普通管理员？`}
                description="该用户将获得与普通管理员相同的管理权限（仍低于超级管理员）。"
                okText="确定"
                cancelText="取消"
                onConfirm={() => handlePromoteToAdmin(record)}
              >
                <Button type="link" size="small" icon={<UserSwitchOutlined />}>
                  设为管理员
                </Button>
              </Popconfirm>
            )}
            {isSuperAdmin && (
              <Button
                type="link"
                size="small"
                icon={<KeyOutlined />}
                onClick={() => setPasswordTarget(record)}
              >
                改密码
              </Button>
            )}
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
            {canDelete && (
              <Popconfirm
                title={`确定删除用户「${record.username}」吗？`}
                description={
                  record.role === SYSTEM_ROLES.SUPER_ADMIN ||
                  record.role === SYSTEM_ROLES.NORMAL_ADMIN
                    ? '该账号为管理员，删除后不可恢复，请谨慎操作。'
                    : '删除后不可恢复'
                }
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
            )}
          </Space>
        );
      },
    },
  ];

  return (
    <PageContainer
      title="用户管理"
      subTitle="管理平台用户账号，支持搜索筛选、新增/编辑、启用/禁用等操作。"
      extra={
        <Button type="primary" onClick={handleAdd}>
          新增用户
        </Button>
      }
    >
      <ProTable<UserItem>
        actionRef={actionRef}
        columns={columns}
        request={fetchUserList}
        rowKey="id"
        search={{ labelWidth: 'auto' }}
        pagination={{
          defaultPageSize: 10,
          showSizeChanger: true,
        }}
        toolBarRender={false}
        dateFormatter="string"
      />

      <Modal
        title={editingUser ? `编辑用户 - ${editingUser.username}` : '新增用户'}
        open={modalVisible}
        onCancel={() => {
          setModalVisible(false);
          form.resetFields();
          setEditingUser(null);
        }}
        onOk={handleModalOk}
        confirmLoading={submitLoading}
        okText="确定"
        cancelText="取消"
        destroyOnClose
      >
        <Form form={form} layout="vertical" preserve={false}>
          <Form.Item
            name="username"
            label="用户名"
            rules={[
              { required: true, message: '用户名不能为空' },
              { validator: validateUsername },
            ]}
            validateTrigger={['onBlur', 'onSubmit']}
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
              options={roleOptionsForForm as any}
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
      <Modal
        title={passwordTarget ? `修改密码 - ${passwordTarget.username}` : '修改密码'}
        open={!!passwordTarget}
        onCancel={() => {
          setPasswordTarget(null);
          passwordForm.resetFields();
        }}
        onOk={handleResetPassword}
        confirmLoading={passwordLoading}
        okText="确定修改"
        cancelText="取消"
        destroyOnClose
      >
        <Form form={passwordForm} layout="vertical" preserve={false}>
          <Form.Item
            name="newPassword"
            label="新密码"
            rules={[
              { required: true, message: '请输入新密码' },
              {
                pattern: /^\w{6,16}$/,
                message: '密码须为6-16位字母、数字或下划线',
              },
            ]}
          >
            <Input.Password
              placeholder="请输入新密码"
              autoComplete="new-password"
            />
          </Form.Item>
          <Form.Item
            name="confirmPassword"
            label="确认新密码"
            dependencies={['newPassword']}
            rules={[
              { required: true, message: '请再次输入新密码' },
              ({ getFieldValue }) => ({
                validator(_, value) {
                  if (!value || getFieldValue('newPassword') === value) {
                    return Promise.resolve();
                  }
                  return Promise.reject(new Error('两次输入的密码不一致'));
                },
              }),
            ]}
          >
            <Input.Password
              placeholder="请再次输入新密码"
              autoComplete="new-password"
            />
          </Form.Item>
        </Form>
      </Modal>
      <ApiPolicyModal
        target={apiPolicyTarget}
        onClose={() => setApiPolicyTarget(null)}
      />
    </PageContainer>
  );
};

export default UserManagement;
