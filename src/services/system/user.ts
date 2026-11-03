/**
 * 系统管理-用户管理模块 - Services 层
 * 封装用户管理相关接口，供 Page 层调用
 */
import { request } from '@umijs/max';
import { SYSTEM_STATUS, SYSTEM_ROLES } from '@/constants/systemLabels';
import { SYSTEM_API_CONFIG } from '@/constants/system';

/** 当前登录用户角色（用于后端按权限返回数据范围） */
export type CurrentUserRoleForApi = 'super_admin' | 'normal_admin' | 'user';

/** 查询用户列表请求参数 */
export interface UserListParams {
  pageNum?: number;
  pageSize?: number;
  username?: string;
  phone?: string;
  role?: string;
  status?: string;
  createTime?: string; // 单个日期，格式：YYYY-MM-DD
  /** 当前登录用户角色，普通管理员时仅返回普通用户列表 */
  currentUserRole?: CurrentUserRoleForApi;
}

/** 查询用户列表响应 */
export interface UserListResponse {
  code: number;
  message: string;
  data: {
    list: UserItem[];
    total: number;
  };
}

/** 用户项（role 为展示名：超管 | 普通管理员 | 普通用户） */
export interface UserItem {
  id: number;
  username: string;
  phone: string;
  role: string; // SystemRoleLabel（见 constants/systemLabels.ts）
  status: string; // SystemStatusLabel（见 constants/systemLabels.ts）
  createTime: string; // YYYY-MM-DD
}

/** 新增用户请求参数 */
export interface AddUserParams {
  username: string;
  phone: string;
  role: string;
  status: string;
}

/** 编辑用户请求参数 */
export interface EditUserParams {
  id: number;
  username: string;
  phone: string;
  role: string;
  status: string;
}

/** 超级管理员重置指定账号密码 */
export interface ResetUserPasswordParams {
  userId: number;
  newPassword: string;
}

/** 通用响应结构 */
export interface CommonResponse<T = any> {
  code: number;
  message: string;
  data?: T;
}

export interface TemporaryPasswordData {
  temporaryPassword: string;
}

export type UserApiFeatureGroup =
  | 'MODEL_ASSET'
  | 'DATASET_ASSET'
  | 'TRAINING_DEFINITION'
  | 'TRAINING_TASK'
  | 'INFERENCE_TASK'
  | 'SYSTEM_ADMIN_AUDIT';

export interface UserApiPolicy {
  userId: number;
  featureGroup: UserApiFeatureGroup;
  displayName: string;
  enabled: boolean;
  maxConcurrentRequests: number | null;
  inherited: boolean;
  version: number | null;
  updatedAt?: string | null;
}

export interface UserApiPolicyUpdate {
  enabled: boolean;
  maxConcurrentRequests: number | null;
  version?: number | null;
}

/** 列表角色展示：仅按 roleId / role_id 映射（1 超管 2 普管 3 普通用户） */
function pickRoleLabel(roleId: unknown): string {
  const n = Number(roleId);
  if (n === 1) return SYSTEM_ROLES.SUPER_ADMIN;
  if (n === 2) return SYSTEM_ROLES.NORMAL_ADMIN;
  if (n === 3) return SYSTEM_ROLES.USER;
  return SYSTEM_ROLES.USER;
}

/** 接口 status → 页面展示（启用 / 禁用） */
function mapStatusFromApi(raw: unknown): string {
  if (
    raw === true ||
    raw === 'true' ||
    raw === 't' ||
    raw === 1 ||
    raw === '1' ||
    raw === 'enabled' ||
    raw === 'ENABLED' ||
    raw === SYSTEM_STATUS.ENABLED ||
    raw === '启用'
  ) {
    return SYSTEM_STATUS.ENABLED;
  }
  return SYSTEM_STATUS.DISABLED;
}

/** 拉取并筛选用户（不含分页，供列表与管理员列表复用） */
export async function fetchFilteredUserRows(
  params: UserListParams,
): Promise<{ code: number; message: string; rows: UserItem[] }> {
  const res = await request<{
    code?: number;
    message?: string;
    data?: { list: Record<string, unknown>[]; total: number };
  }>(SYSTEM_API_CONFIG.ENDPOINTS.USER_LIST, {
    method: 'GET',
  });

  if (res.code !== 200 || !res.data?.list || !Array.isArray(res.data.list)) {
    return {
      code: res.code ?? 500,
      message: (res as any).message ?? '查询失败',
      rows: [],
    };
  }

  let rows: UserItem[] = res.data.list.map((row) => {
    const id = Number(row.id);
    const username = String(row.username ?? '');
    const phone = String(row.mobile ?? row.phone ?? '');
    const role = pickRoleLabel(row.role_id ?? row.roleId);
    const status = mapStatusFromApi(row.status);
    const createdRaw = row.created_at ?? row.createdAt;
    let createTime = '';
    if (createdRaw != null) {
      const s = String(createdRaw);
      createTime = s.length >= 10 ? s.slice(0, 10) : s;
    }
    return {
      id,
      username,
      phone,
      role,
      status,
      createTime,
    };
  });

  if (params.currentUserRole === 'normal_admin') {
    rows = rows.filter((r) => r.role === SYSTEM_ROLES.USER);
  }

  const { username, phone, role, status, createTime } = params;

  if (username) {
    rows = rows.filter((r) => r.username.includes(String(username)));
  }
  if (phone) {
    rows = rows.filter((r) => r.phone.includes(String(phone)));
  }
  if (role) {
    rows = rows.filter((r) => r.role === role);
  }
  if (status) {
    rows = rows.filter((r) => r.status === status);
  }
  if (createTime) {
    rows = rows.filter((r) => r.createTime === createTime);
  }

  return {
    code: 200,
    message: 'ok',
    rows,
  };
}

/** 查询用户列表（前端分页，供用户管理页 ProTable） */
export async function fetchUserList(params: UserListParams): Promise<UserListResponse> {
  const { code, message, rows } = await fetchFilteredUserRows(params);
  if (code !== 200) {
    return { code, message, data: { list: [], total: 0 } };
  }

  const { pageNum = 1, pageSize = 10 } = params;
  const total = rows.length;
  const start = (pageNum - 1) * pageSize;
  const list = rows.slice(start, start + pageSize);

  return {
    code: 200,
    message: message || 'ok',
    data: { list, total },
  };
}

/** 新增用户 POST /api/system/user/add */
export async function addUser(params: AddUserParams) {
  return request<CommonResponse<TemporaryPasswordData>>(SYSTEM_API_CONFIG.ENDPOINTS.USER_ADD, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    data: params,
  });
}

export async function fetchUserApiPolicies(userId: number) {
  return request<CommonResponse<UserApiPolicy[]>>(
    `/system/user/${userId}/api-policies`,
    { method: 'GET' },
  );
}

export async function updateUserApiPolicy(
  userId: number,
  featureGroup: UserApiFeatureGroup,
  data: UserApiPolicyUpdate,
) {
  return request<CommonResponse<UserApiPolicy>>(
    `/system/user/${userId}/api-policies/${featureGroup}`,
    {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      data,
    },
  );
}

export async function resetUserApiPolicy(
  userId: number,
  featureGroup: UserApiFeatureGroup,
  version?: number | null,
) {
  return request<CommonResponse<UserApiPolicy>>(
    `/system/user/${userId}/api-policies/${featureGroup}`,
    {
      method: 'DELETE',
      params: version == null ? undefined : { version },
    },
  );
}

/** 编辑用户 PUT /api/system/user/edit */
export async function editUser(params: EditUserParams) {
  return request<CommonResponse<UserItem>>(SYSTEM_API_CONFIG.ENDPOINTS.USER_EDIT, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    data: params,
  });
}

/** 重置指定账号密码；权限由后端再次校验，成功后该账号现有会话失效。 */
export async function resetUserPassword(params: ResetUserPasswordParams) {
  return request<CommonResponse>(
    SYSTEM_API_CONFIG.ENDPOINTS.USER_RESET_PASSWORD,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      data: params,
    },
  );
}

/** 删除用户 DELETE /api/system/user/delete */
export async function deleteUser(id: number) {
  return request<CommonResponse<UserItem>>(SYSTEM_API_CONFIG.ENDPOINTS.USER_DELETE, {
    method: 'DELETE',
    params: { id },
  });
}

/** 启用/禁用用户 PUT /api/system/user/toggleStatus */
export async function toggleUserStatus(id: number, status: string) {
  return request<CommonResponse<UserItem>>(SYSTEM_API_CONFIG.ENDPOINTS.USER_TOGGLE_STATUS, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    data: { id, status },
  });
}

/** 校验用户名唯一性 POST /api/system/user/checkUsername（由调用方自行处理错误提示） */
export async function checkUsername(username: string) {
  return request<CommonResponse<{ available: boolean }>>(SYSTEM_API_CONFIG.ENDPOINTS.USER_CHECK_USERNAME, {
    method: 'POST',
    skipErrorHandler: true,
    headers: { 'Content-Type': 'application/json' },
    data: { username },
  }).catch((error) => {
    throw error;
  });
}

/** 将普通用户设为普通管理员 POST /api/user/promote-to-admin（仅超管） */
export async function promoteUserToNormalAdmin(body: { userId: number }) {
  return request<CommonResponse>(SYSTEM_API_CONFIG.ENDPOINTS.USER_PROMOTE_TO_ADMIN, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    data: body,
  });
}

