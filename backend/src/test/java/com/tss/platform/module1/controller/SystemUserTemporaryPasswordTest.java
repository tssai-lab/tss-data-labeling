package com.tss.platform.module1.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tss.platform.module1.common.Result;
import com.tss.platform.module1.entity.User;
import com.tss.platform.module1.security.TemporaryPasswordGenerator;
import com.tss.platform.module1.security.UserAdministrationPolicy;
import com.tss.platform.module1.service.UserService;
import org.junit.jupiter.api.Test;
import org.mindrot.jbcrypt.BCrypt;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SystemUserTemporaryPasswordTest {

    private static final String TEMPORARY_PASSWORD = "T3mp!Password_X7";

    @Test
    void newUserReturnsGeneratedPasswordOnceAndStoresOnlyBcryptHash() {
        UserService userService = mock(UserService.class);
        UserAdministrationPolicy policy = mock(UserAdministrationPolicy.class);
        TemporaryPasswordGenerator generator = mock(TemporaryPasswordGenerator.class);
        when(policy.requireAssignableRole(3, null)).thenReturn(3);
        when(userService.count(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(userService.getOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(userService.save(any(User.class))).thenReturn(true);
        when(generator.generate()).thenReturn(TEMPORARY_PASSWORD);
        SystemUserController controller = controller(userService, policy, generator);

        Result<?> result = controller.addUser(Map.of(
                "username", "delivery-user",
                "phone", "13800000001",
                "role", "USER",
                "status", "enabled"
        ), new MockHttpServletRequest());

        assertThat(result.getCode()).isEqualTo(Result.SUCCESS_CODE);
        assertThat(result.getData()).isEqualTo(Map.of("temporaryPassword", TEMPORARY_PASSWORD));
        var captor = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(userService).save(captor.capture());
        assertThat(captor.getValue().getPassword()).isNotEqualTo(TEMPORARY_PASSWORD);
        assertThat(BCrypt.checkpw(TEMPORARY_PASSWORD, captor.getValue().getPassword())).isTrue();
    }

    @Test
    void restoredUserAlsoGetsAFreshGeneratedPassword() {
        UserService userService = mock(UserService.class);
        UserAdministrationPolicy policy = mock(UserAdministrationPolicy.class);
        TemporaryPasswordGenerator generator = mock(TemporaryPasswordGenerator.class);
        User deleted = new User();
        deleted.setId(9);
        deleted.setRoleId(3);
        deleted.setDeletedAt(LocalDateTime.now());
        when(policy.requireAssignableRole(3, null)).thenReturn(3);
        when(userService.count(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(userService.getOne(any(LambdaQueryWrapper.class))).thenReturn(deleted);
        when(userService.restoreDeletedUser(any(), any(), any(), any(), any(), any(), any())).thenReturn(true);
        when(generator.generate()).thenReturn(TEMPORARY_PASSWORD);
        SystemUserController controller = controller(userService, policy, generator);

        Result<?> result = controller.addUser(Map.of(
                "username", "restored-user",
                "phone", "13800000002",
                "role", "USER",
                "status", "enabled"
        ), new MockHttpServletRequest());

        assertThat(result.getCode()).isEqualTo(Result.SUCCESS_CODE);
        assertThat(result.getData()).isEqualTo(Map.of("temporaryPassword", TEMPORARY_PASSWORD));
        var hashCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(userService).restoreDeletedUser(
                org.mockito.ArgumentMatchers.eq(deleted),
                org.mockito.ArgumentMatchers.eq("restored-user"),
                org.mockito.ArgumentMatchers.eq("13800000002"),
                org.mockito.ArgumentMatchers.eq(3),
                org.mockito.ArgumentMatchers.eq(true),
                hashCaptor.capture(),
                org.mockito.ArgumentMatchers.eq("restored-user@default.com")
        );
        assertThat(BCrypt.checkpw(TEMPORARY_PASSWORD, hashCaptor.getValue())).isTrue();
    }

    private static SystemUserController controller(
            UserService userService,
            UserAdministrationPolicy policy,
            TemporaryPasswordGenerator generator
    ) {
        SystemUserController controller = new SystemUserController();
        ReflectionTestUtils.setField(controller, "userService", userService);
        ReflectionTestUtils.setField(controller, "userAdministrationPolicy", policy);
        ReflectionTestUtils.setField(controller, "temporaryPasswordGenerator", generator);
        return controller;
    }
}
