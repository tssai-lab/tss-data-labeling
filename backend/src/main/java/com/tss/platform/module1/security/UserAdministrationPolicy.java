package com.tss.platform.module1.security;

import com.tss.platform.module1.entity.User;
import com.tss.platform.security.AuthContext;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class UserAdministrationPolicy {

    public static final int SUPER_ADMIN_ROLE_ID = 1;
    public static final int NORMAL_ADMIN_ROLE_ID = 2;
    public static final int ORDINARY_USER_ROLE_ID = 3;

    private static final Set<Integer> SUPPORTED_ROLE_IDS = Set.of(
            SUPER_ADMIN_ROLE_ID,
            NORMAL_ADMIN_ROLE_ID,
            ORDINARY_USER_ROLE_ID
    );

    private final AuthContext authContext;

    public UserAdministrationPolicy(AuthContext authContext) {
        this.authContext = authContext;
    }

    /** 超管看全部；普通管理员只看普通用户。返回 null 表示不附加角色过滤。 */
    public Integer requiredVisibleRoleId() {
        Integer roleId = requireAdministratorRole();
        return roleId == SUPER_ADMIN_ROLE_ID ? null : ORDINARY_USER_ROLE_ID;
    }

    public Integer currentUserId() {
        return authContext.currentUserId();
    }

    public void requireCanManage(User target) {
        if (target == null || target.getDeletedAt() != null) {
            throw new IllegalArgumentException("用户不存在");
        }
        requireCanManageRole(target.getRoleId());
    }

    /** 恢复会复用原用户 ID，因此必须按软删除前的角色检查，避免身份与资产归属串用。 */
    public void requireCanRestore(User target) {
        if (target == null || target.getDeletedAt() == null) {
            throw new IllegalArgumentException("待恢复用户不存在");
        }
        requireCanManageRole(target.getRoleId());
    }

    private void requireCanManageRole(Integer targetRoleId) {
        Integer operatorRoleId = requireAdministratorRole();
        if (operatorRoleId == NORMAL_ADMIN_ROLE_ID
                && !Integer.valueOf(ORDINARY_USER_ROLE_ID).equals(targetRoleId)) {
            throw forbidden("普通管理员只能管理普通用户");
        }
    }

    public int requireAssignableRole(Integer requestedRoleId, Integer targetRoleId) {
        int nextRoleId = requestedRoleId == null ? ORDINARY_USER_ROLE_ID : requestedRoleId;
        if (!SUPPORTED_ROLE_IDS.contains(nextRoleId)) {
            throw new IllegalArgumentException("不支持的用户角色");
        }

        Integer operatorRoleId = requireAdministratorRole();
        if (operatorRoleId == NORMAL_ADMIN_ROLE_ID) {
            if (nextRoleId != ORDINARY_USER_ROLE_ID) {
                throw forbidden("普通管理员只能创建或编辑普通用户");
            }
            return nextRoleId;
        }
        if (nextRoleId == SUPER_ADMIN_ROLE_ID
                && !Integer.valueOf(SUPER_ADMIN_ROLE_ID).equals(targetRoleId)) {
            throw forbidden("不支持创建或提升为超级管理员");
        }
        return nextRoleId;
    }

    public void requireSuperAdministrator() {
        if (!authContext.isSuperAdmin()) {
            throw forbidden("仅超级管理员可操作");
        }
    }

    private Integer requireAdministratorRole() {
        Integer roleId = authContext.currentRoleId();
        if (roleId == null || (roleId != SUPER_ADMIN_ROLE_ID && roleId != NORMAL_ADMIN_ROLE_ID)) {
            throw forbidden("无权限访问，仅管理员可操作");
        }
        return roleId;
    }

    private static UserAdministrationForbiddenException forbidden(String message) {
        return new UserAdministrationForbiddenException(message);
    }
}
