package com.tss.platform.module1.util;

import java.util.regex.Pattern;

/**
 * 前后端角色文案与 role_id 映射
 */
public final class UserRoleUtil {

    private static final Pattern MAINLAND_MOBILE = Pattern.compile("^1[3-9]\\d{9}$");

    private UserRoleUtil() {}

    public static Integer parseRoleId(String roleStr) {
        if (roleStr == null || roleStr.isBlank() || "null".equals(roleStr)) {
            return 3;
        }
        String r = roleStr.trim();
        if ("super_admin".equals(r) || "超级管理员".equals(r) || "超管".equals(r)) {
            return 1;
        }
        if ("normal_admin".equals(r) || "普通管理员".equals(r)) {
            return 2;
        }
        return 3;
    }

    public static boolean isEnabledStatus(String statusStr) {
        if (statusStr == null || statusStr.isBlank() || "null".equals(statusStr)) {
            return true;
        }
        return "enabled".equals(statusStr) || "启用".equals(statusStr);
    }

    public static String safeString(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) {
            return null;
        }
        return s;
    }

    /** 完整手机号格式保留给手机号登录标识，避免与用户名产生歧义。 */
    public static boolean isMainlandMobile(String value) {
        return value != null && MAINLAND_MOBILE.matcher(value.trim()).matches();
    }
}
