package com.tss.platform.module1.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.tss.platform.module1.dto.*;
import com.tss.platform.module1.entity.User;
import java.util.List;
import java.util.Map;

public interface UserService extends IService<User> {
    boolean addUser(User user);
    boolean resetPassword(Integer userId, String newPassword);
    List<Map<String, Object>> getUserListWithRole(Integer requiredRoleId);
    boolean softDeleteUser(Integer userId);

    /** 恢复软删用户（须显式将 deleted_at 置空，updateById 默认跳过 null 字段） */
    boolean restoreDeletedUser(User user, String username, String mobile, Integer roleId, Boolean status, String passwordHash, String email);
    boolean registerByUsername(UserRegisterDTO dto);
    boolean registerByMobile(UserRegisterDTO dto);
    boolean forgetPassword(ForgetPasswordDTO dto);
    Map<String, Object> login(LoginDTO dto);
    boolean promoteToNormalAdmin(Integer targetUserId);

    IPage<Map<String, Object>> getUserPage(UserQueryDTO queryDTO, Integer requiredRoleId);
    Map<String, Object> getUserDetail(Integer userId, Integer requiredRoleId);
    boolean updateUser(UserUpdateDTO updateDTO);
    boolean toggleUserStatus(Integer userId, Boolean status);
}
