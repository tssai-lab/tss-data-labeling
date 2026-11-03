package com.tss.platform.module1.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tss.platform.module1.dto.*;
import com.tss.platform.module1.entity.User;
import com.tss.platform.module1.mapper.UserMapper;
import com.tss.platform.module1.security.UserSessionInvalidator;
import com.tss.platform.module1.service.UserService;
import com.tss.platform.module1.sms.SmsVerificationService;
import com.tss.platform.module1.sms.SmsPurpose;
import com.tss.platform.module1.util.DesensitizationUtil;
import com.tss.platform.module1.util.UserRoleUtil;
import jakarta.annotation.Resource;
import org.mindrot.jbcrypt.BCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Transactional
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    private static final Logger SYSTEM_LOG = LoggerFactory.getLogger("SYSTEM_LOG");
    private static final Logger USER_LOG = LoggerFactory.getLogger("USER_LOG");

    @Resource
    private SmsVerificationService smsVerificationService;

    @Resource
    private UserSessionInvalidator userSessionInvalidator;

    @Override
    public boolean addUser(User user) {
        String username = UserRoleUtil.safeString(user.getUsername());
        if (username == null) {
            throw new IllegalArgumentException("用户名不能为空");
        }
        if (UserRoleUtil.isMainlandMobile(username)) {
            throw new IllegalArgumentException("用户名不能使用手机号格式");
        }
        user.setUsername(username);
        String encryptedPwd = BCrypt.hashpw(user.getPassword(), BCrypt.gensalt());
        user.setPassword(encryptedPwd);
        user.setStatus(true);
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        return save(user);
    }

    @Override
    public boolean resetPassword(Integer userId, String newPassword) {
        if (newPassword == null || newPassword.isBlank()) {
            throw new IllegalArgumentException("新密码不能为空");
        }
        User user = getById(userId);
        if (user == null || user.getDeletedAt() != null) {
            return false;
        }
        user.setPassword(BCrypt.hashpw(newPassword, BCrypt.gensalt()));
        user.setUpdatedAt(LocalDateTime.now());
        boolean updated = updateById(user);
        if (updated) {
            userSessionInvalidator.invalidateAfterCommit(userId);
        }
        return updated;
    }

    @Override
    public List<Map<String, Object>> getUserListWithRole(Integer requiredRoleId) {
        return baseMapper.selectUserWithRole(requiredRoleId);
    }

    @Override
    public boolean softDeleteUser(Integer userId) {
        User user = getById(userId);
        if (user == null || user.getDeletedAt() != null) {
            return false;
        }
        user.setDeletedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        boolean updated = updateById(user);
        if (updated) {
            userSessionInvalidator.invalidateAfterCommit(userId);
        }
        return updated;
    }

    @Override
    public boolean restoreDeletedUser(User user, String username, String mobile, Integer roleId,
                                      Boolean status, String passwordHash, String email) {
        if (user == null || user.getId() == null) {
            return false;
        }
        LambdaUpdateWrapper<User> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(User::getId, user.getId())
                .set(User::getDeletedAt, null)
                .set(User::getUsername, username)
                .set(User::getMobile, mobile)
                .set(User::getRoleId, roleId)
                .set(User::getStatus, status)
                .set(User::getPassword, passwordHash)
                .set(User::getEmail, email)
                .set(User::getUpdatedAt, LocalDateTime.now());
        boolean updated = update(updateWrapper);
        if (updated) {
            userSessionInvalidator.invalidateAfterCommit(user.getId());
        }
        return updated;
    }

    @Override
    public IPage<Map<String, Object>> getUserPage(UserQueryDTO queryDTO, Integer requiredRoleId) {
        Page<Map<String, Object>> page = new Page<>(queryDTO.getPage(), queryDTO.getSize());
        return baseMapper.selectUserPage(page, queryDTO, requiredRoleId);
    }

    @Override
    public Map<String, Object> getUserDetail(Integer userId, Integer requiredRoleId) {
        return baseMapper.selectUserDetail(userId, requiredRoleId);
    }

    @Override
    public boolean updateUser(UserUpdateDTO updateDTO) {
        User user = getById(updateDTO.getUserId());
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        
        if (updateDTO.getUsername() != null && !updateDTO.getUsername().isBlank()) {
            if (UserRoleUtil.isMainlandMobile(updateDTO.getUsername())) {
                throw new IllegalArgumentException("用户名不能使用手机号格式");
            }
            user.setUsername(updateDTO.getUsername());
        }
        if (updateDTO.getMobile() != null && !updateDTO.getMobile().isBlank()) {
            user.setMobile(updateDTO.getMobile());
        }
        if (updateDTO.getEmail() != null && !updateDTO.getEmail().isBlank()) {
            user.setEmail(updateDTO.getEmail());
        }
        if (updateDTO.getRoleId() != null) {
            user.setRoleId(updateDTO.getRoleId());
        }
        if (updateDTO.getStatus() != null) {
            user.setStatus(updateDTO.getStatus());
        }
        user.setUpdatedAt(LocalDateTime.now());
        
        boolean updated = updateById(user);
        if (updated) {
            userSessionInvalidator.invalidateAfterCommit(updateDTO.getUserId());
        }
        return updated;
    }

    @Override
    public boolean toggleUserStatus(Integer userId, Boolean status) {
        User user = getById(userId);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        user.setStatus(status);
        user.setUpdatedAt(LocalDateTime.now());
        boolean updated = updateById(user);
        if (updated) {
            userSessionInvalidator.invalidateAfterCommit(userId);
        }
        return updated;
    }

    @Override
    public boolean registerByUsername(UserRegisterDTO dto) {
        SYSTEM_LOG.debug("注册请求入参: username={}", DesensitizationUtil.maskUsername(dto.getUsername()));

        if (dto.getUsername() == null || dto.getUsername().isBlank()) {
            SYSTEM_LOG.warn("用户名不能为空");
            USER_LOG.warn("注册失败: 用户名不能为空");
            throw new IllegalArgumentException("用户名不能为空");
        }

        if (dto.getPassword() == null || !dto.getPassword().equals(dto.getConfirmPassword())) {
            SYSTEM_LOG.warn("两次密码不一致");
            USER_LOG.warn("注册失败: 两次密码不一致, username={}", DesensitizationUtil.maskUsername(dto.getUsername()));
            throw new IllegalArgumentException("两次密码不一致");
        }

        String username = dto.getUsername().trim();
        if (UserRoleUtil.isMainlandMobile(username)) {
            throw new IllegalArgumentException("用户名不能使用手机号格式");
        }
        LambdaQueryWrapper<User> activeWrapper = new LambdaQueryWrapper<>();
        activeWrapper.eq(User::getUsername, username);
        activeWrapper.isNull(User::getDeletedAt);
        if (this.count(activeWrapper) > 0) {
            SYSTEM_LOG.warn("用户名已存在: {}", DesensitizationUtil.maskUsername(username));
            USER_LOG.warn("注册失败: 用户名已存在, username={}", DesensitizationUtil.maskUsername(username));
            throw new IllegalArgumentException("用户名已存在");
        }

        String passwordHash = BCrypt.hashpw(dto.getPassword(), BCrypt.gensalt());
        String email = username + "@default.com";

        LambdaQueryWrapper<User> deletedWrapper = new LambdaQueryWrapper<>();
        deletedWrapper.eq(User::getUsername, username);
        deletedWrapper.isNotNull(User::getDeletedAt);
        User deletedUser = this.getOne(deletedWrapper);
        if (deletedUser != null) {
            boolean ok = restoreDeletedUser(deletedUser, username, deletedUser.getMobile(), 3, true, passwordHash, email);
            if (ok) {
                SYSTEM_LOG.info("注册成功(恢复已注销账号): username={}", DesensitizationUtil.maskUsername(username));
                USER_LOG.info("注册成功(恢复已注销账号): username={}", DesensitizationUtil.maskUsername(username));
            }
            return ok;
        }

        try {
            User user = new User();
            user.setUsername(username);
            user.setPassword(passwordHash);
            user.setEmail(email);
            user.setRoleId(3);
            user.setStatus(true);
            user.setCreatedAt(LocalDateTime.now());
            user.setUpdatedAt(LocalDateTime.now());

            boolean result = this.save(user);
            if (result) {
                SYSTEM_LOG.info("注册成功: username={}", DesensitizationUtil.maskUsername(username));
                USER_LOG.info("注册成功: username={}", DesensitizationUtil.maskUsername(username));
            }
            return result;
        } catch (Exception e) {
            throw mapRegisterException(e, username, null);
        }
    }

    @Override
    public boolean registerByMobile(UserRegisterDTO dto) {
        if (dto.getMobile() == null || !dto.getMobile().trim().matches("^1[3-9]\\d{9}$")) {
            throw new IllegalArgumentException("手机号格式错误");
        }
        if (dto.getSmsCode() == null || !dto.getSmsCode().trim().matches("^\\d{6}$")) {
            throw new IllegalArgumentException("验证码应为6位数字");
        }
        String mobile = dto.getMobile().trim();
        SYSTEM_LOG.debug("手机号注册请求入参: mobile={}", DesensitizationUtil.maskMobile(mobile));

        if (dto.getPassword() == null || !dto.getPassword().equals(dto.getConfirmPassword())) {
            SYSTEM_LOG.warn("两次密码不一致");
            USER_LOG.warn("注册失败: 两次密码不一致, mobile={}", DesensitizationUtil.maskMobile(mobile));
            throw new IllegalArgumentException("两次密码不一致");
        }

        String username = UserRoleUtil.safeString(dto.getUsername());
        if (username == null) {
            throw new IllegalArgumentException("用户名不能为空");
        }
        if (UserRoleUtil.isMainlandMobile(username)) {
            throw new IllegalArgumentException("用户名不能使用手机号格式");
        }

        LambdaQueryWrapper<User> activeMobileWrapper = new LambdaQueryWrapper<>();
        activeMobileWrapper.eq(User::getMobile, mobile);
        activeMobileWrapper.isNull(User::getDeletedAt);
        if (this.count(activeMobileWrapper) > 0) {
            SYSTEM_LOG.warn("手机号已注册: {}", DesensitizationUtil.maskMobile(mobile));
            USER_LOG.warn("注册失败: 手机号已注册, mobile={}", DesensitizationUtil.maskMobile(mobile));
            throw new IllegalArgumentException("手机号已注册");
        }

        LambdaQueryWrapper<User> activeUsernameWrapper = new LambdaQueryWrapper<>();
        activeUsernameWrapper.eq(User::getUsername, username);
        activeUsernameWrapper.isNull(User::getDeletedAt);
        if (this.count(activeUsernameWrapper) > 0) {
            throw new IllegalArgumentException("用户名已存在");
        }

        if (!smsVerificationService.verifyAndConsumeAfterCommit(
                mobile, dto.getSmsCode(), SmsPurpose.LOGIN_REGISTER)) {
            SYSTEM_LOG.warn("验证码错误、已过期或正在使用: mobile={}", DesensitizationUtil.maskMobile(mobile));
            USER_LOG.warn("注册失败: 验证码错误、已过期或正在使用, mobile={}", DesensitizationUtil.maskMobile(mobile));
            throw new IllegalArgumentException("验证码错误或已过期");
        }

        String passwordHash = BCrypt.hashpw(dto.getPassword(), BCrypt.gensalt());
        String email = mobile + "@default.com";

        User deletedUser = findDeletedUserForMobileRegister(mobile, username);
        try {
            boolean ok;
            if (deletedUser != null) {
                ok = restoreDeletedUser(deletedUser, username, mobile, 3, true, passwordHash, email);
                if (ok) {
                    SYSTEM_LOG.info("注册成功(恢复已注销账号): mobile={}", DesensitizationUtil.maskMobile(mobile));
                    USER_LOG.info("注册成功(恢复已注销账号): mobile={}", DesensitizationUtil.maskMobile(mobile));
                }
            } else {
                User user = new User();
                user.setMobile(mobile);
                user.setUsername(username);
                user.setEmail(email);
                user.setPassword(passwordHash);
                user.setRoleId(3);
                user.setStatus(true);
                user.setCreatedAt(LocalDateTime.now());
                user.setUpdatedAt(LocalDateTime.now());
                ok = this.save(user);
                if (ok) {
                    SYSTEM_LOG.info("注册成功: mobile={}", DesensitizationUtil.maskMobile(mobile));
                    USER_LOG.info("注册成功: mobile={}", DesensitizationUtil.maskMobile(mobile));
                }
            }
            return ok;
        } catch (Exception e) {
            throw mapRegisterException(e, username, mobile);
        }
    }

    private User findDeletedUserForMobileRegister(String mobile, String username) {
        LambdaQueryWrapper<User> byMobile = new LambdaQueryWrapper<>();
        byMobile.eq(User::getMobile, mobile);
        byMobile.isNotNull(User::getDeletedAt);
        User deleted = this.getOne(byMobile);
        if (deleted != null) {
            return deleted;
        }
        LambdaQueryWrapper<User> byUsername = new LambdaQueryWrapper<>();
        byUsername.eq(User::getUsername, username);
        byUsername.isNotNull(User::getDeletedAt);
        return this.getOne(byUsername);
    }

    private RuntimeException mapRegisterException(Exception e, String username, String mobile) {
        String err = e.getMessage() != null ? e.getMessage() : "";
        SYSTEM_LOG.error("注册失败(数据库异常): username={}, mobile={}, error={}",
                DesensitizationUtil.maskUsername(username),
                mobile != null ? DesensitizationUtil.maskMobile(mobile) : "-",
                err);
        USER_LOG.error("注册失败(数据库异常): username={}, mobile={}",
                DesensitizationUtil.maskUsername(username),
                mobile != null ? DesensitizationUtil.maskMobile(mobile) : "-");
        if (err.contains("users_username_key") || err.contains("uk_users_username")) {
            return new IllegalArgumentException("用户名已存在，请直接登录或更换手机号");
        }
        if (err.contains("uk_users_mobile")) {
            return new IllegalArgumentException("手机号已注册，请直接登录");
        }
        return new RuntimeException("注册失败，请稍后重试");
    }

    @Override
    public boolean forgetPassword(ForgetPasswordDTO dto) {
        String mobile = dto.getMobile().trim();
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getMobile, mobile);
        wrapper.isNull(User::getDeletedAt);
        User user = this.getOne(wrapper);
        if (user == null) {
            SYSTEM_LOG.warn("手机号未注册: {}", DesensitizationUtil.maskMobile(mobile));
            USER_LOG.warn("密码重置失败: 手机号未注册, mobile={}", DesensitizationUtil.maskMobile(mobile));
            throw new IllegalArgumentException("该手机号未注册");
        }

        if (!smsVerificationService.verifyAndConsumeAfterCommit(
                mobile, dto.getSmsCode(), SmsPurpose.RESET_PASSWORD)) {
            SYSTEM_LOG.warn("验证码错误、已过期或正在使用: mobile={}", DesensitizationUtil.maskMobile(mobile));
            USER_LOG.warn("密码重置失败: 验证码错误、已过期或正在使用, mobile={}", DesensitizationUtil.maskMobile(mobile));
            throw new IllegalArgumentException("验证码错误或已过期");
        }

        try {
            LambdaUpdateWrapper<User> updateWrapper = new LambdaUpdateWrapper<>();
            updateWrapper.eq(User::getMobile, mobile)
                    .set(User::getPassword, BCrypt.hashpw(dto.getNewPassword(), BCrypt.gensalt()))
                    .set(User::getUpdatedAt, LocalDateTime.now());

            boolean result = this.update(updateWrapper);
            if (result) {
                userSessionInvalidator.invalidateAfterCommit(user.getId());
                SYSTEM_LOG.info("密码重置成功: mobile={}", DesensitizationUtil.maskMobile(mobile));
                USER_LOG.info("密码重置成功: mobile={}", DesensitizationUtil.maskMobile(mobile));
            }
            return result;
        } catch (Exception e) {
            SYSTEM_LOG.error("密码重置失败(数据库异常): mobile={}, error={}", DesensitizationUtil.maskMobile(mobile), e.getMessage());
            USER_LOG.error("密码重置失败(数据库异常): mobile={}", DesensitizationUtil.maskMobile(mobile));
            throw new RuntimeException("密码重置失败，请稍后重试");
        }
    }

    @Override
    public boolean promoteToNormalAdmin(Integer targetUserId) {
        if (targetUserId == null) {
            throw new IllegalArgumentException("用户ID不能为空");
        }
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getId, targetUserId).isNull(User::getDeletedAt);
        User user = getOne(wrapper);
        if (user == null) {
            SYSTEM_LOG.warn("晋升管理员失败: 用户不存在或已删除, userId={}", targetUserId);
            throw new IllegalArgumentException("用户不存在");
        }
        if (user.getRoleId() == null || user.getRoleId() != 3) {
            USER_LOG.warn("晋升管理员失败: 仅可将普通用户设为管理员, userId={}, roleId={}", targetUserId, user.getRoleId());
            throw new IllegalArgumentException("仅可将普通用户设为普通管理员");
        }
        user.setRoleId(2);
        user.setUpdatedAt(LocalDateTime.now());
        boolean ok = updateById(user);
        if (ok) {
            userSessionInvalidator.invalidateAfterCommit(targetUserId);
            USER_LOG.info("已设为普通管理员: userId={}", targetUserId);
        }
        return ok;
    }

    @Override
    public Map<String, Object> login(LoginDTO dto) {
        User user = null;

        if ("mobile".equals(dto.getType())) {
            SYSTEM_LOG.debug("手机号登录请求: mobile={}", DesensitizationUtil.maskMobile(dto.getMobile()));

            if (dto.getMobile() == null || dto.getMobile().isBlank()) {
                SYSTEM_LOG.warn("登录失败: 手机号为空");
                USER_LOG.warn("登录失败: 手机号为空");
                throw new IllegalArgumentException("手机号不能为空");
            }

            if (dto.getSmsCode() == null || dto.getSmsCode().isBlank()) {
                SYSTEM_LOG.warn("登录失败: 验证码为空, mobile={}", DesensitizationUtil.maskMobile(dto.getMobile()));
                USER_LOG.warn("登录失败: 验证码为空, mobile={}", DesensitizationUtil.maskMobile(dto.getMobile()));
                throw new IllegalArgumentException("验证码不能为空");
            }

            if (!dto.getMobile().trim().matches("^1[3-9]\\d{9}$")) {
                throw new IllegalArgumentException("手机号格式错误");
            }
            if (!dto.getSmsCode().trim().matches("^\\d{6}$")) {
                throw new IllegalArgumentException("验证码应为6位数字");
            }

            LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(User::getMobile, dto.getMobile().trim());
            wrapper.isNull(User::getDeletedAt);
            user = this.getOne(wrapper);

            if (user == null) {
                SYSTEM_LOG.warn("登录失败: 用户不存在, mobile={}", DesensitizationUtil.maskMobile(dto.getMobile()));
                USER_LOG.warn("登录失败: 用户不存在, mobile={}", DesensitizationUtil.maskMobile(dto.getMobile()));
                throw new IllegalArgumentException("用户不存在");
            }

            if (user.getStatus() != null && !user.getStatus()) {
                SYSTEM_LOG.warn("登录失败: 账号已被禁用, mobile={}", DesensitizationUtil.maskMobile(dto.getMobile()));
                USER_LOG.warn("登录失败: 账号已被禁用, mobile={}", DesensitizationUtil.maskMobile(dto.getMobile()));
                throw new IllegalArgumentException("账号已被禁用");
            }

            if (!smsVerificationService.verifyAndConsume(
                    dto.getMobile(), dto.getSmsCode(), SmsPurpose.LOGIN_REGISTER)) {
                SYSTEM_LOG.warn("登录失败: 验证码错误或已过期, mobile={}", DesensitizationUtil.maskMobile(dto.getMobile()));
                USER_LOG.warn("登录失败: 验证码错误或已过期, mobile={}", DesensitizationUtil.maskMobile(dto.getMobile()));
                throw new IllegalArgumentException("验证码错误或已过期");
            }

        } else if ("account".equals(dto.getType())) {
            SYSTEM_LOG.debug("登录请求: username={}", DesensitizationUtil.maskUsername(dto.getUsername()));

            if (dto.getUsername() == null || dto.getUsername().isBlank()) {
                SYSTEM_LOG.warn("登录失败: 用户名为空");
                USER_LOG.warn("登录失败: 用户名为空");
                throw new IllegalArgumentException("用户名不能为空");
            }

            if (dto.getPassword() == null || dto.getPassword().isBlank()) {
                SYSTEM_LOG.warn("登录失败: 密码为空, username={}", DesensitizationUtil.maskUsername(dto.getUsername()));
                USER_LOG.warn("登录失败: 密码为空, username={}", DesensitizationUtil.maskUsername(dto.getUsername()));
                throw new IllegalArgumentException("密码不能为空");
            }

            String loginId = dto.getUsername().trim();
            LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
            wrapper.isNull(User::getDeletedAt);
            if (UserRoleUtil.isMainlandMobile(loginId)) {
                wrapper.eq(User::getMobile, loginId);
            } else {
                wrapper.eq(User::getUsername, loginId);
            }
            user = this.getOne(wrapper);

            if (user == null) {
                SYSTEM_LOG.warn("登录失败: 用户不存在, loginId={}", DesensitizationUtil.maskUsername(loginId));
                USER_LOG.warn("登录失败: 用户不存在, loginId={}", DesensitizationUtil.maskUsername(loginId));
                throw new IllegalArgumentException("用户名/手机号或密码错误");
            }

            if (!BCrypt.checkpw(dto.getPassword(), user.getPassword())) {
                SYSTEM_LOG.warn("登录失败: 密码错误, username={}",
                        DesensitizationUtil.maskUsername(dto.getUsername()));
                USER_LOG.warn("登录失败: 密码错误, username={}", DesensitizationUtil.maskUsername(dto.getUsername()));
                throw new IllegalArgumentException("用户名/手机号或密码错误");
            }

            if (user.getStatus() != null && !user.getStatus()) {
                SYSTEM_LOG.warn("登录失败: 账号已被禁用, username={}", DesensitizationUtil.maskUsername(dto.getUsername()));
                USER_LOG.warn("登录失败: 账号已被禁用, username={}", DesensitizationUtil.maskUsername(dto.getUsername()));
                throw new IllegalArgumentException("账号已被禁用");
            }

        } else {
            SYSTEM_LOG.warn("登录失败: 登录类型不正确");
            USER_LOG.warn("登录失败: 登录类型不正确");
            throw new IllegalArgumentException("登录类型不正确，请使用 'account' 或 'mobile'");
        }

        StpUtil.login(user.getId());
        StpUtil.getTokenSession().set("roleId", user.getRoleId());
        StpUtil.getTokenSession().set("username", user.getUsername());

        SYSTEM_LOG.info("登录成功: username={}, userId={}", DesensitizationUtil.maskUsername(user.getUsername()), user.getId());
        USER_LOG.info("登录成功: username={}, userId={}", DesensitizationUtil.maskUsername(user.getUsername()), user.getId());

        Map<String, Object> result = new HashMap<>();
        result.put("userId", user.getId());
        result.put("username", user.getUsername());
        result.put("mobile", user.getMobile());
        result.put("roleId", user.getRoleId());
        result.put("status", user.getStatus());
        result.put("token", StpUtil.getTokenValue());

        return result;
    }
}
