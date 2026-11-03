/**
 * 系统管理模块 - 文案/枚举常量
 * 统一维护，避免页面/服务层硬编码字符串导致维护困难
 */

export const SYSTEM_ROLES = {
  SUPER_ADMIN: '超管',
  NORMAL_ADMIN: '普通管理员',
  USER: '普通用户',
} as const;

export type SystemRoleLabel = (typeof SYSTEM_ROLES)[keyof typeof SYSTEM_ROLES];

export const SYSTEM_STATUS = {
  ENABLED: '启用',
  DISABLED: '禁用',
} as const;

export type SystemStatusLabel =
  (typeof SYSTEM_STATUS)[keyof typeof SYSTEM_STATUS];

export const SYSTEM_ROLE_OPTIONS_SUPER = [
  { label: SYSTEM_ROLES.NORMAL_ADMIN, value: SYSTEM_ROLES.NORMAL_ADMIN },
  { label: SYSTEM_ROLES.USER, value: SYSTEM_ROLES.USER },
] as const;

export const SYSTEM_ROLE_OPTIONS_NORMAL_ADMIN = [
  { label: SYSTEM_ROLES.USER, value: SYSTEM_ROLES.USER },
] as const;

export const SYSTEM_ADMIN_ROLE_OPTIONS = [
  { label: SYSTEM_ROLES.NORMAL_ADMIN, value: SYSTEM_ROLES.NORMAL_ADMIN },
] as const;

export const SYSTEM_STATUS_OPTIONS = [
  { label: SYSTEM_STATUS.ENABLED, value: SYSTEM_STATUS.ENABLED },
  { label: SYSTEM_STATUS.DISABLED, value: SYSTEM_STATUS.DISABLED },
] as const;
