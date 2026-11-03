package com.tss.platform.security;

import cn.dev33.satoken.stp.StpUtil;
import org.springframework.stereotype.Component;

@Component
public class AuthContext {

    /**
     * 系统/演示资产归属账户 ID。is_demo 资产的 ownerUserId 迁移到该值，
     * 表示"全局共享、只读"，任何登录用户可读但不可写。
     */
    public static final Integer SYSTEM_USER_ID = 0;

    public Integer currentUserId() {
        return StpUtil.getLoginIdAsInt();
    }

    public String currentUsername() {
        Object value = StpUtil.getTokenSession().get("username");
        if (value == null) {
            return null;
        }
        String username = String.valueOf(value).trim();
        return username.isEmpty() ? null : username;
    }

    public boolean isAdmin() {
        Integer roleId = currentRoleId();
        return roleId != null && (roleId == 1 || roleId == 2);
    }

    public boolean isSuperAdmin() {
        return Integer.valueOf(1).equals(currentRoleId());
    }

    public Integer currentRoleId() {
        Object roleValue = StpUtil.getTokenSession().get("roleId");
        if (roleValue instanceof Integer roleId) {
            return roleId;
        }
        if (roleValue instanceof Number number) {
            return number.intValue();
        }
        return null;
    }

    public boolean canAccessOwner(Integer ownerUserId) {
        return isAdmin()
                || SYSTEM_USER_ID.equals(ownerUserId)
                || (ownerUserId != null && ownerUserId.equals(currentUserId()));
    }

    /** 演示资产对所有用户可读；普通用户写演示资产一律拒绝（仅管理员可管理） */
    public void rejectDemoWrite(Boolean isDemo) {
        if (Boolean.TRUE.equals(isDemo) && !isAdmin()) {
            throw new IllegalArgumentException("演示资产只读，不可修改");
        }
    }

    public void requireOwnerAccess(Integer ownerUserId, String message) {
        if (!canAccessOwner(ownerUserId)) {
            throw new IllegalArgumentException(message);
        }
    }

    public boolean canAccessObjectName(String objectName, Integer ownerUserId) {
        if (isAdmin()) {
            return true;
        }
        if (objectName == null || ownerUserId == null) {
            return false;
        }
        if (SYSTEM_USER_ID.equals(ownerUserId)) {
            // 演示资产对象全局可读。owner 0 仅由服务端为演示资产设置（标记时归属迁移），
            // 对象物理路径保留原归属前缀（users/{原owner}/...），故不做前缀约束；
            // 下载端点的 objectName 取自资产/版本 DB 的 storagePath，非客户端注入。
            return objectName != null && !objectName.isBlank();
        }
        return ownerUserId.equals(currentUserId())
                && objectName.startsWith(userPrefix(ownerUserId));
    }

    public void requireObjectAccess(String objectName, Integer ownerUserId, String message) {
        if (!canAccessObjectName(objectName, ownerUserId)) {
            throw new IllegalArgumentException(message);
        }
    }

    public String userPrefix(Integer ownerUserId) {
        if (ownerUserId == null) {
            throw new IllegalArgumentException("ownerUserId cannot be null");
        }
        return "users/" + ownerUserId + "/";
    }
}
