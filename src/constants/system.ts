/**
 * 系统管理配置常量
 * 避免硬编码，便于维护
 */
export const SYSTEM_API_CONFIG = {
  ENDPOINTS: {
    USER_LIST: '/system/user/list',
    USER_ADD: '/system/user/add',
    USER_EDIT: '/system/user/edit',
    USER_DELETE: '/system/user/delete',
    USER_TOGGLE_STATUS: '/system/user/toggleStatus',
    USER_CHECK_USERNAME: '/system/user/checkUsername',
    /** 超级管理员重置指定账号密码 */
    USER_RESET_PASSWORD: '/user/reset-password',
    /** 将普通用户晋升为普通管理员（仅超管） */
    USER_PROMOTE_TO_ADMIN: '/user/promote-to-admin',

    /** 操作日志主链路 GET /api/system/log/* */
    LOG_LIST: '/system/log/list',
    LOG_EXPORT: '/system/log/export',
    LOG_OPERATION_TYPES: '/system/log/types',
    LOG_OBJECTS: '/system/log/objects',

    /** 系统配置 */
    CONFIG_GET: '/system/config/get',
    CONFIG_UPDATE: '/system/config/update',
    RESOURCE_POLICY_GET: '/system/config/resource-policy/get',
    RESOURCE_POLICY_UPDATE: '/system/config/resource-policy/update',
  },
} as const;
