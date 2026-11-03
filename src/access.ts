/**
 * 系统权限定义（基于 useAccess）
 * 角色：super_admin 超级管理员 | normal_admin 普通管理员 | user 普通用户
 */
export default function access(
  initialState: { currentUser?: API.CurrentUser } | undefined,
) {
  const { currentUser } = initialState ?? {};
  const role = currentUser?.role ?? '';

  const isSuperAdmin = role === 'super_admin';
  const isNormalAdmin = role === 'normal_admin';
  const isAdmin = isSuperAdmin || isNormalAdmin;

  return {
    /** 是否超级管理员（唯一，系统最高权限） */
    isSuperAdmin,
    /** 是否普通管理员（可管理普通用户，无角色/权限管理） */
    isNormalAdmin,
    /** 是否任意管理员（用于显示系统管理一级菜单） */
    isAdmin,

    /** 训练调度-算力状态：所有登录用户可见 */
    canViewResourceStatus: !!currentUser,
    /** 算力状态-纳管/删除服务器：仅超管 */
    canManageResourceNodes: isSuperAdmin,
    /** 算力状态-调整排队顺序、取消排队：仅超管 */
    canManageResourceQueue: isSuperAdmin,
    /** 算力状态-Kubernetes Pod 与镜像诊断：仅超管 */
    canViewKubernetesDiagnostics: isSuperAdmin,

    /** 系统管理-用户管理：超管+普管可见 */
    canAccessSystemUser: isAdmin,
    /** 系统管理-管理员列表：仅超管 */
    canAccessSystemAdmin: isSuperAdmin,
    /** 系统管理-日志管理：超管+普管可见 */
    canAccessSystemLog: isAdmin,
    /** 模型缓存：管理员可查看，只有超级管理员可清理或修改磁盘策略 */
    canAccessModelCache: isAdmin,
    canClearModelCache: isSuperAdmin,
    canManageModelCachePolicy: isSuperAdmin,

    /** 系统管理-训练方案管理：仅超管可上传、发布和停用 */
    canAccessTrainingPlans: isSuperAdmin,

    /** 系统管理-系统配置：仅超管 */
    canAccessSystemConfig: isSuperAdmin,

    /** 用户管理-删除：超管+普管（普管列表仅含普通用户） */
    canUserDelete: isAdmin,
    /** 用户管理-角色筛选与分配管理员角色：仅超管（普管只能分配非管理员角色） */
    canUserRoleFilterAndAssignAdmin: isSuperAdmin,
    /** 日志管理-导出：仅超管 */
    canLogExport: isSuperAdmin,
    /** 日志管理-查看管理员日志及IP：仅超管（普管仅看普通用户日志且隐藏IP） */
    canLogViewAdminAndIp: isSuperAdmin,

    /** 个人中心-我的操作记录：所有登录用户 */
    canViewMyOperationLogs: !!currentUser,
  };
}
