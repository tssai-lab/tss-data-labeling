package com.tss.platform.module1.common;

import com.tss.platform.module1.entity.User;

public class UserContext {
    // 模拟当前登录用户（实际项目从Token/会话中获取）
    public static User getCurrentUser() {
        User user = new User();
        user.setId(1);  // 超级管理员ID
        user.setRoleId(1);  // 超级管理员角色ID=1
        return user;
    }
}
