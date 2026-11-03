package com.tss.platform.module1.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tss.platform.module1.common.AuditActionType;
import com.tss.platform.module1.common.AuditObjectType;
import com.tss.platform.module1.common.Result;
import com.tss.platform.module1.entity.User;
import com.tss.platform.module1.security.UserAdministrationForbiddenException;
import com.tss.platform.module1.security.UserAdministrationPolicy;
import com.tss.platform.module1.security.UserSessionInvalidator;
import com.tss.platform.module1.security.TemporaryPasswordGenerator;
import com.tss.platform.module1.service.AuditRecordService;
import com.tss.platform.module1.service.UserService;
import com.tss.platform.module1.util.DesensitizationUtil;
import com.tss.platform.module1.util.UserRoleUtil;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.mindrot.jbcrypt.BCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api/system/user")
public class SystemUserController {

    private static final Logger SYSTEM_LOG = LoggerFactory.getLogger("SYSTEM_LOG");
    @Resource
    private UserService userService;

    @Resource
    private AuditRecordService auditRecordService;

    @Resource
    private UserAdministrationPolicy userAdministrationPolicy;

    @Resource
    private UserSessionInvalidator userSessionInvalidator;

    @Resource
    private TemporaryPasswordGenerator temporaryPasswordGenerator;

    @GetMapping("/list")
    public Result<Map<String, Object>> getUserList() {
        final List<Map<String, Object>> list;
        try {
            list = userService.getUserListWithRole(
                    userAdministrationPolicy.requiredVisibleRoleId());
        } catch (UserAdministrationForbiddenException exception) {
            return Result.noAuth(exception.getMessage());
        }
        
        // 转换数据格式，确保与前端一致
        for (Map<String, Object> item : list) {
            // 将 mobile 复制到 phone 字段（前端可能使用 phone）
            if (item.containsKey("mobile") && !item.containsKey("phone")) {
                item.put("phone", item.get("mobile"));
            }
            
            // role_id 是权限判断使用的权威字段；role_name 仅用于兼容旧数据和旧查询结果。
            item.put("role", resolveRoleDisplay(item));
            
            // 将 created_at 复制到 createdAt 字段
            if (item.containsKey("created_at") && !item.containsKey("createdAt")) {
                item.put("createdAt", item.get("created_at"));
            }
        }
        
        // 返回前端期望的格式：{ list: [], total: number }
        Map<String, Object> result = new HashMap<>();
        result.put("list", list);
        result.put("total", list.size());
        
        return Result.success(result, "查询成功");
    }

    private String resolveRoleDisplay(Map<String, Object> item) {
        Object roleIdValue = item.get("role_id");
        if (roleIdValue instanceof Number roleId) {
            return switch (roleId.intValue()) {
                case 1 -> "超管";
                case 2 -> "普通管理员";
                default -> "普通用户";
            };
        }

        String roleName = Objects.toString(item.get("role_name"), "");
        return switch (roleName) {
            case "super_admin", "超级管理员", "超管" -> "超管";
            case "admin", "normal_admin", "普通管理员" -> "普通管理员";
            default -> "普通用户";
        };
    }

    @PostMapping("/add")
    public Result<?> addUser(@RequestBody Map<String, Object> params, HttpServletRequest request) {
        SYSTEM_LOG.info("管理员新增用户请求: username={}",
                DesensitizationUtil.maskUsername(UserRoleUtil.safeString(params.get("username"))));

        try {
            String username = UserRoleUtil.safeString(params.get("username"));
            if (username == null) {
                return Result.fail("用户名不能为空");
            }
            if (UserRoleUtil.isMainlandMobile(username)) {
                return Result.fail("用户名不能使用手机号格式");
            }

            String mobile = UserRoleUtil.safeString(params.get("phone"));
            if (mobile == null) {
                return Result.fail("手机号不能为空");
            }

            Integer roleId = userAdministrationPolicy.requireAssignableRole(
                    UserRoleUtil.parseRoleId(UserRoleUtil.safeString(params.get("role"))),
                    null
            );

            LambdaQueryWrapper<User> checkWrapper = new LambdaQueryWrapper<>();
            checkWrapper.eq(User::getUsername, username);
            checkWrapper.isNull(User::getDeletedAt);
            if (userService.count(checkWrapper) > 0) {
                return Result.fail("用户名已存在");
            }

            LambdaQueryWrapper<User> mobileCheckWrapper = new LambdaQueryWrapper<>();
            mobileCheckWrapper.eq(User::getMobile, mobile);
            mobileCheckWrapper.isNull(User::getDeletedAt);
            if (userService.count(mobileCheckWrapper) > 0) {
                return Result.fail("该手机号码已被使用");
            }

            // 按用户名恢复已软删账号
            LambdaQueryWrapper<User> deletedByUsernameWrapper = new LambdaQueryWrapper<>();
            deletedByUsernameWrapper.eq(User::getUsername, username);
            deletedByUsernameWrapper.isNotNull(User::getDeletedAt);
            User deletedUser = userService.getOne(deletedByUsernameWrapper);
            if (deletedUser != null) {
                userAdministrationPolicy.requireCanRestore(deletedUser);
                Result<?> mobileConflict = checkMobileAvailable(mobile, deletedUser.getId());
                if (mobileConflict != null) {
                    return mobileConflict;
                }
                return restoreDeletedUser(deletedUser, username, mobile, roleId, params);
            }

            // 手机号被已软删账号占用（唯一索引含 deleted 记录），恢复该账号而非 INSERT
            LambdaQueryWrapper<User> deletedByMobileWrapper = new LambdaQueryWrapper<>();
            deletedByMobileWrapper.eq(User::getMobile, mobile);
            deletedByMobileWrapper.isNotNull(User::getDeletedAt);
            User deletedByMobile = userService.getOne(deletedByMobileWrapper);
            if (deletedByMobile != null) {
                userAdministrationPolicy.requireCanRestore(deletedByMobile);
                LambdaQueryWrapper<User> usernameActiveWrapper = new LambdaQueryWrapper<>();
                usernameActiveWrapper.eq(User::getUsername, username);
                usernameActiveWrapper.isNull(User::getDeletedAt);
                usernameActiveWrapper.ne(User::getId, deletedByMobile.getId());
                if (userService.count(usernameActiveWrapper) > 0) {
                    return Result.fail("用户名已存在");
                }
                return restoreDeletedUser(deletedByMobile, username, mobile, roleId, params);
            }

            User user = new User();
            user.setUsername(username);
            user.setMobile(mobile);
            user.setRoleId(roleId);
            user.setStatus(UserRoleUtil.isEnabledStatus(UserRoleUtil.safeString(params.get("status"))));
            String temporaryPassword = temporaryPasswordGenerator.generate();
            user.setPassword(BCrypt.hashpw(temporaryPassword, BCrypt.gensalt()));
            user.setEmail(username + "@default.com");
            user.setCreatedAt(LocalDateTime.now());
            user.setUpdatedAt(LocalDateTime.now());

            if (userService.save(user)) {
                SYSTEM_LOG.info("管理员新增用户成功: username={}", DesensitizationUtil.maskUsername(username));
                return Result.success(
                        Map.of("temporaryPassword", temporaryPassword),
                        "新增用户成功"
                );
            }
            return Result.fail("新增失败");
        } catch (UserAdministrationForbiddenException e) {
            return Result.noAuth(e.getMessage());
        } catch (Exception e) {
            SYSTEM_LOG.error("管理员新增用户异常: error={}", e.getMessage());
            String errMsg = e.getMessage() != null ? e.getMessage() : "";
            if (errMsg.contains("uk_users_mobile")) {
                return Result.fail("该手机号码已被使用（含历史已删除账号），请更换手机号或联系管理员");
            }
            if (errMsg.contains("uk_users_username") || errMsg.contains("users_username")) {
                return Result.fail("用户名已存在");
            }
            return Result.fail("新增失败，请检查用户名和手机号是否已被占用");
        }
    }

    private Result<?> restoreDeletedUser(User deletedUser, String username, String mobile, Integer roleId,
                                       Map<String, Object> params) {
        boolean status = UserRoleUtil.isEnabledStatus(UserRoleUtil.safeString(params.get("status")));
        String temporaryPassword = temporaryPasswordGenerator.generate();
        String passwordHash = BCrypt.hashpw(temporaryPassword, BCrypt.gensalt());
        String email = username + "@default.com";

        boolean ok = userService.restoreDeletedUser(
                deletedUser, username, mobile, roleId, status, passwordHash, email);
        if (!ok) {
            return Result.fail("恢复用户失败");
        }
        SYSTEM_LOG.info("管理员恢复已删除用户: username={}, userId={}",
                DesensitizationUtil.maskUsername(username), deletedUser.getId());
        return Result.success(
                Map.of("temporaryPassword", temporaryPassword),
                "用户已恢复"
        );
    }

    @PutMapping("/edit")
    public Result<?> editUser(@RequestBody Map<String, Object> params, HttpServletRequest request) {
        Integer userId = params.get("id") != null ? Integer.parseInt(String.valueOf(params.get("id"))) : null;
        if (userId == null) {
            return Result.fail("用户ID不能为空");
        }

        SYSTEM_LOG.info("管理员编辑用户请求: userId={}, params={}", userId, params);

        try {
            User user = userService.getById(userId);
            if (user == null || user.getDeletedAt() != null) {
                throw new IllegalArgumentException("用户不存在");
            }

            Integer targetRoleId = user.getRoleId();
            userAdministrationPolicy.requireCanManage(user);

            String newUsername = UserRoleUtil.safeString(params.get("username"));
            if (newUsername != null) {
                if (UserRoleUtil.isMainlandMobile(newUsername)) {
                    return Result.fail("用户名不能使用手机号格式");
                }
                Result<?> dup = checkUsernameAvailable(newUsername, userId);
                if (dup != null) {
                    return dup;
                }
                user.setUsername(newUsername);
            }

            String newMobile = UserRoleUtil.safeString(params.get("phone"));
            if (newMobile != null) {
                Result<?> dup = checkMobileAvailable(newMobile, userId);
                if (dup != null) {
                    return dup;
                }
                user.setMobile(newMobile);
            }

            boolean roleChanged = false;
            boolean statusChanged = false;
            Integer newRoleId = null;
            Boolean newStatus = null;

            if (params.get("role") != null) {
                newRoleId = userAdministrationPolicy.requireAssignableRole(
                        UserRoleUtil.parseRoleId(UserRoleUtil.safeString(params.get("role"))),
                        targetRoleId
                );
                roleChanged = !Objects.equals(newRoleId, targetRoleId);
                user.setRoleId(newRoleId);
            }

            if (params.get("status") != null) {
                newStatus = UserRoleUtil.isEnabledStatus(UserRoleUtil.safeString(params.get("status")));
                statusChanged = user.getStatus() == null || !user.getStatus().equals(newStatus);
                user.setStatus(newStatus);
            }

            user.setUpdatedAt(LocalDateTime.now());

            boolean success = userService.updateById(user);
            if (success) {
                userSessionInvalidator.invalidateNow(userId);
                SYSTEM_LOG.info("管理员编辑用户成功: userId={}", userId);
                if (roleChanged) {
                    auditRecordService.recordSuccess(
                            AuditActionType.PERMISSION_CHANGE,
                            AuditObjectType.USER,
                            String.valueOf(userId),
                            "ROLE_CHANGE:from=" + targetRoleId + ",to=" + newRoleId
                    );
                }
                if (statusChanged) {
                    auditRecordService.recordSuccess(
                            AuditActionType.PERMISSION_CHANGE,
                            AuditObjectType.USER,
                            String.valueOf(userId),
                            Boolean.TRUE.equals(newStatus) ? "USER_ENABLE" : "USER_DISABLE"
                    );
                }
                return Result.success(null, "更新成功");
            } else {
                SYSTEM_LOG.error("管理员编辑用户失败: userId={}", userId);
                return Result.fail("更新失败");
            }
        } catch (UserAdministrationForbiddenException e) {
            return Result.noAuth(e.getMessage());
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        } catch (Exception e) {
            SYSTEM_LOG.error("管理员编辑用户异常: userId={}, error={}", userId, e.getMessage());
            return Result.fail("更新失败: " + e.getMessage());
        }
    }

    @DeleteMapping("/delete")
    public Result<?> deleteUser(@RequestParam Integer id, HttpServletRequest request) {
        SYSTEM_LOG.info("管理员删除用户请求: userId={}", id);

        try {
            User targetUser = userService.getById(id);
            if (targetUser == null || targetUser.getDeletedAt() != null) {
                return Result.fail("用户不存在");
            }

            int currentUserId = userAdministrationPolicy.currentUserId();
            if (currentUserId == id) {
                return Result.fail("不能删除当前登录账号");
            }
            userAdministrationPolicy.requireCanManage(targetUser);

            boolean success = userService.softDeleteUser(id);
            if (success) {
                SYSTEM_LOG.info("管理员删除用户成功: userId={}", id);
                auditRecordService.recordSuccess(
                        AuditActionType.DELETE,
                        AuditObjectType.USER,
                        String.valueOf(id),
                        "USER_DELETE"
                );
                return Result.success(null, "删除成功");
            } else {
                SYSTEM_LOG.error("管理员删除用户失败: userId={}", id);
                auditRecordService.recordFailed(
                        AuditActionType.DELETE,
                        AuditObjectType.USER,
                        String.valueOf(id),
                        "删除失败",
                        "USER_DELETE"
                );
                return Result.fail("删除失败");
            }
        } catch (UserAdministrationForbiddenException e) {
            return Result.noAuth(e.getMessage());
        } catch (Exception e) {
            SYSTEM_LOG.error("管理员删除用户异常: userId={}, error={}", id, e.getMessage());
            auditRecordService.recordFailed(
                    AuditActionType.DELETE,
                    AuditObjectType.USER,
                    String.valueOf(id),
                    e.getMessage(),
                    "USER_DELETE"
            );
            return Result.fail("删除失败: " + e.getMessage());
        }
    }

    @PutMapping("/toggleStatus")
    public Result<?> toggleUserStatus(@RequestBody Map<String, Object> params, HttpServletRequest request) {
        Integer userId = params.get("id") != null ? Integer.parseInt(String.valueOf(params.get("id"))) : null;
        String statusStr = params.get("status") != null ? String.valueOf(params.get("status")) : null;
        
        if (userId == null || statusStr == null) {
            return Result.fail("参数不完整");
        }

        SYSTEM_LOG.info("管理员切换用户状态请求: userId={}, status={}", userId, statusStr);

        try {
            User user = userService.getById(userId);
            if (user == null || user.getDeletedAt() != null) {
                throw new IllegalArgumentException("用户不存在");
            }

            userAdministrationPolicy.requireCanManage(user);

            boolean status = UserRoleUtil.isEnabledStatus(statusStr);
            user.setStatus(status);
            user.setUpdatedAt(LocalDateTime.now());
            
            boolean success = userService.updateById(user);
            if (success) {
                userSessionInvalidator.invalidateNow(userId);
                SYSTEM_LOG.info("管理员切换用户状态成功: userId={}", userId);
                auditRecordService.recordSuccess(
                        AuditActionType.PERMISSION_CHANGE,
                        AuditObjectType.USER,
                        String.valueOf(userId),
                        status ? "USER_ENABLE" : "USER_DISABLE"
                );
                return Result.success(null, "状态更新成功");
            } else {
                auditRecordService.recordFailed(
                        AuditActionType.PERMISSION_CHANGE,
                        AuditObjectType.USER,
                        String.valueOf(userId),
                        "状态更新失败",
                        "USER_STATUS_CHANGE"
                );
                return Result.fail("状态更新失败");
            }
        } catch (UserAdministrationForbiddenException e) {
            return Result.noAuth(e.getMessage());
        } catch (IllegalArgumentException e) {
            auditRecordService.recordFailed(
                    AuditActionType.PERMISSION_CHANGE,
                    AuditObjectType.USER,
                    String.valueOf(userId),
                    e.getMessage(),
                    "USER_STATUS_CHANGE"
            );
            return Result.fail(e.getMessage());
        } catch (Exception e) {
            SYSTEM_LOG.error("管理员切换用户状态异常: userId={}, error={}", userId, e.getMessage());
            auditRecordService.recordFailed(
                    AuditActionType.PERMISSION_CHANGE,
                    AuditObjectType.USER,
                    String.valueOf(userId),
                    "状态更新失败",
                    "USER_STATUS_CHANGE"
            );
            return Result.fail("状态更新失败: " + e.getMessage());
        }
    }

    @PostMapping("/checkUsername")
    public Result<Map<String, Object>> checkUsername(@RequestBody Map<String, Object> params) {
        String username = params.get("username") != null ? String.valueOf(params.get("username")) : null;
        if (username == null || username.trim().isEmpty()) {
            return Result.fail("用户名不能为空");
        }

        // 只检查未删除的用户
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername, username.trim());
        wrapper.isNull(User::getDeletedAt);
        long count = userService.count(wrapper);

        Map<String, Object> result = new HashMap<>();
        result.put("available", count == 0);
        return Result.success(result, "查询成功");
    }

    private Result<?> checkUsernameAvailable(String username, Integer excludeUserId) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername, username);
        wrapper.isNull(User::getDeletedAt);
        if (excludeUserId != null) {
            wrapper.ne(User::getId, excludeUserId);
        }
        if (userService.count(wrapper) > 0) {
            return Result.fail("用户名已存在");
        }
        return null;
    }

    private Result<?> checkMobileAvailable(String mobile, Integer excludeUserId) {
        LambdaQueryWrapper<User> activeWrapper = new LambdaQueryWrapper<>();
        activeWrapper.eq(User::getMobile, mobile);
        activeWrapper.isNull(User::getDeletedAt);
        if (excludeUserId != null) {
            activeWrapper.ne(User::getId, excludeUserId);
        }
        if (userService.count(activeWrapper) > 0) {
            return Result.fail("该手机号码已被使用");
        }
        // 唯一索引包含已软删记录，需排除「正在恢复」的账号本身
        LambdaQueryWrapper<User> anyWrapper = new LambdaQueryWrapper<>();
        anyWrapper.eq(User::getMobile, mobile);
        if (excludeUserId != null) {
            anyWrapper.ne(User::getId, excludeUserId);
        }
        if (userService.count(anyWrapper) > 0) {
            return Result.fail("该手机号码已被使用（含历史已删除账号）");
        }
        return null;
    }

}
