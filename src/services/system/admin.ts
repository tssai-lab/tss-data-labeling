/**
 * 系统管理-管理员列表模块 - Services 层
 * 管理员列表基于用户管理接口复用实现（仅做管理员范围的约束）
 */
import type {
  CommonResponse,
  TemporaryPasswordData,
  UserItem,
  UserListParams,
  UserListResponse,
} from './user';
import {
  addUser,
  checkUsername,
  deleteUser,
  editUser,
  fetchFilteredUserRows,
  toggleUserStatus,
} from './user';
import { SYSTEM_ROLES } from '@/constants/systemLabels';

/** 管理员展示角色（与页面/后端约定保持一致） */
export const ADMIN_ROLE_NAMES = [SYSTEM_ROLES.SUPER_ADMIN, SYSTEM_ROLES.NORMAL_ADMIN] as const;
export type AdminRoleName = (typeof ADMIN_ROLE_NAMES)[number];

export type AdminItem = UserItem;
export type AdminListParams = UserListParams;
export type AdminListResponse = UserListResponse;

function assertAdminRole(role: string) {
  if (!ADMIN_ROLE_NAMES.includes(role as AdminRoleName)) {
    throw new Error('管理员角色不合法');
  }
}

/** 获取管理员列表（先筛管理员再分页，避免在用户分页结果里漏掉管理员） */
export async function fetchAdminList(params: AdminListParams): Promise<AdminListResponse> {
  const { code, message, rows } = await fetchFilteredUserRows({
    ...params,
    currentUserRole: 'super_admin',
  });
  if (code !== 200) {
    return { code, message, data: { list: [], total: 0 } };
  }

  const adminRows = rows.filter((u) =>
    ADMIN_ROLE_NAMES.includes(u.role as AdminRoleName),
  );

  const { pageNum = 1, pageSize = 10 } = params;
  const total = adminRows.length;
  const start = (pageNum - 1) * pageSize;
  const list = adminRows.slice(start, start + pageSize);

  return {
    code: 200,
    message: 'ok',
    data: { list, total },
  };
}

/** 校验管理员用户名唯一性 */
export async function checkAdminUsername(username: string) {
  return checkUsername(username);
}

/** 新增管理员 */
export async function addAdmin(params: {
  username: string;
  phone: string;
  role: AdminRoleName | string;
  status: string;
}): Promise<CommonResponse<TemporaryPasswordData>> {
  assertAdminRole(params.role);
  return addUser({
    username: params.username,
    phone: params.phone,
    role: params.role,
    status: params.status,
  });
}

/** 编辑管理员（支持降为普通用户） */
export async function editAdmin(params: {
  id: number;
  username: string;
  phone: string;
  role: AdminRoleName | string;
  status: string;
}): Promise<CommonResponse<UserItem>> {
  if (params.role !== SYSTEM_ROLES.USER) {
    assertAdminRole(params.role);
  }
  return editUser({
    id: params.id,
    username: params.username,
    phone: params.phone,
    role: params.role,
    status: params.status,
  });
}

/** 删除管理员 */
export async function deleteAdmin(id: number): Promise<CommonResponse<UserItem>> {
  return deleteUser(id);
}

/** 启用/禁用管理员 */
export async function toggleAdminStatus(id: number, status: string): Promise<CommonResponse<UserItem>> {
  return toggleUserStatus(id, status);
}

