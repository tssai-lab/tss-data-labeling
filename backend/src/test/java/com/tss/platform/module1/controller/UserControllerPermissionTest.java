package com.tss.platform.module1.controller;

import com.tss.platform.module1.common.Result;
import com.tss.platform.module1.dto.ResetPasswordDTO;
import com.tss.platform.module1.entity.User;
import com.tss.platform.module1.security.UserAdministrationForbiddenException;
import com.tss.platform.module1.security.UserAdministrationPolicy;
import com.tss.platform.module1.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserControllerPermissionTest {

    @Test
    void legacyListPassesNormalUserScopeToDatabaseQuery() {
        UserService userService = mock(UserService.class);
        UserAdministrationPolicy policy = mock(UserAdministrationPolicy.class);
        when(policy.requiredVisibleRoleId()).thenReturn(3);
        when(userService.getUserListWithRole(3)).thenReturn(List.of());
        UserController controller = controller(userService, policy);

        Result<List<Map<String, Object>>> result = controller.getUserListWithRole();

        assertThat(result.getCode()).isEqualTo(Result.SUCCESS_CODE);
        verify(userService).getUserListWithRole(3);
    }

    @Test
    void onlySuperAdministratorCanResetAnotherUsersPassword() {
        UserService userService = mock(UserService.class);
        UserAdministrationPolicy policy = mock(UserAdministrationPolicy.class);
        doThrow(new UserAdministrationForbiddenException("仅超级管理员可操作"))
                .when(policy).requireSuperAdministrator();
        UserController controller = controller(userService, policy);
        ResetPasswordDTO request = new ResetPasswordDTO();
        request.setUserId(20);
        request.setNewPassword("Replacement_123");

        Result<?> result = controller.resetPassword(request, new MockHttpServletRequest());

        assertThat(result.getCode()).isEqualTo(Result.NO_AUTH_CODE);
        verify(userService, never()).getById(20);
        verify(userService, never()).resetPassword(20, "Replacement_123");
    }

    @Test
    void currentSystemListAlsoPassesNormalUserScopeToDatabaseQuery() {
        UserService userService = mock(UserService.class);
        UserAdministrationPolicy policy = mock(UserAdministrationPolicy.class);
        when(policy.requiredVisibleRoleId()).thenReturn(3);
        when(userService.getUserListWithRole(3)).thenReturn(List.of());
        SystemUserController controller = new SystemUserController();
        ReflectionTestUtils.setField(controller, "userService", userService);
        ReflectionTestUtils.setField(controller, "userAdministrationPolicy", policy);

        Result<Map<String, Object>> result = controller.getUserList();

        assertThat(result.getCode()).isEqualTo(Result.SUCCESS_CODE);
        assertThat(result.getData()).containsEntry("total", 0);
        verify(userService).getUserListWithRole(3);
    }

    @Test
    void currentSystemListDisplaysDatabaseAdminRoleAsNormalAdministrator() {
        UserService userService = mock(UserService.class);
        UserAdministrationPolicy policy = mock(UserAdministrationPolicy.class);
        Map<String, Object> administrator = new HashMap<>();
        administrator.put("id", 20);
        administrator.put("username", "normal-admin");
        administrator.put("role_id", 2);
        administrator.put("role_name", "admin");
        when(policy.requiredVisibleRoleId()).thenReturn(null);
        when(userService.getUserListWithRole(null)).thenReturn(List.of(administrator));
        SystemUserController controller = new SystemUserController();
        ReflectionTestUtils.setField(controller, "userService", userService);
        ReflectionTestUtils.setField(controller, "userAdministrationPolicy", policy);

        Result<Map<String, Object>> result = controller.getUserList();

        assertThat(result.getCode()).isEqualTo(Result.SUCCESS_CODE);
        assertThat(result.getData()).containsEntry("total", 1);
        assertThat(administrator).containsEntry("role", "普通管理员");
    }

    @Test
    void normalAdministratorCannotRestoreDeletedAdministratorThroughAddEndpoint() {
        UserService userService = mock(UserService.class);
        UserAdministrationPolicy policy = mock(UserAdministrationPolicy.class);
        User deletedAdministrator = new User();
        deletedAdministrator.setId(20);
        deletedAdministrator.setRoleId(2);
        deletedAdministrator.setDeletedAt(java.time.LocalDateTime.now());
        when(policy.requireAssignableRole(3, null)).thenReturn(3);
        when(userService.count(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(userService.getOne(any(LambdaQueryWrapper.class))).thenReturn(deletedAdministrator);
        doThrow(new UserAdministrationForbiddenException("普通管理员只能管理普通用户"))
                .when(policy).requireCanRestore(deletedAdministrator);
        SystemUserController controller = new SystemUserController();
        ReflectionTestUtils.setField(controller, "userService", userService);
        ReflectionTestUtils.setField(controller, "userAdministrationPolicy", policy);

        Result<?> result = controller.addUser(
                Map.of(
                        "username", "deleted-admin",
                        "phone", "13800000000",
                        "role", "USER",
                        "status", "enabled"
                ),
                new MockHttpServletRequest()
        );

        assertThat(result.getCode()).isEqualTo(Result.NO_AUTH_CODE);
        verify(userService, never()).restoreDeletedUser(
                any(), any(), any(), any(), any(), any(), any());
    }

    private static UserController controller(
            UserService userService,
            UserAdministrationPolicy policy
    ) {
        UserController controller = new UserController();
        ReflectionTestUtils.setField(controller, "userService", userService);
        ReflectionTestUtils.setField(controller, "userAdministrationPolicy", policy);
        return controller;
    }
}
