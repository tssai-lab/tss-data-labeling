package com.tss.platform.module1.service;

import com.tss.platform.module1.entity.User;
import com.tss.platform.module1.mapper.UserMapper;
import com.tss.platform.module1.security.UserSessionInvalidator;
import com.tss.platform.module1.service.impl.UserServiceImpl;
import org.junit.jupiter.api.Test;
import org.mindrot.jbcrypt.BCrypt;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServiceSessionInvalidationTest {

    @Test
    void successfulPasswordChangeRevokesSessionButFailedDatabaseUpdateDoesNot() {
        UserMapper mapper = mock(UserMapper.class);
        UserSessionInvalidator invalidator = mock(UserSessionInvalidator.class);
        User user = activeUser(7);
        when(mapper.selectById(7)).thenReturn(user);
        when(mapper.updateById(any(User.class))).thenReturn(1);
        UserServiceImpl service = service(mapper, invalidator);

        assertThat(service.resetPassword(7, "Replacement_123")).isTrue();
        var updatedUser = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(mapper).updateById(updatedUser.capture());
        assertThat(updatedUser.getValue().getPassword()).isNotEqualTo("Replacement_123");
        assertThat(BCrypt.checkpw("Replacement_123", updatedUser.getValue().getPassword())).isTrue();
        verify(invalidator).invalidateAfterCommit(7);

        UserSessionInvalidator failedInvalidator = mock(UserSessionInvalidator.class);
        UserMapper failedMapper = mock(UserMapper.class);
        when(failedMapper.selectById(8)).thenReturn(activeUser(8));
        when(failedMapper.updateById(any(User.class))).thenReturn(0);
        UserServiceImpl failedService = service(failedMapper, failedInvalidator);

        assertThat(failedService.resetPassword(8, "Replacement_456")).isFalse();
        verify(failedInvalidator, never()).invalidateAfterCommit(8);
    }

    private static UserServiceImpl service(
            UserMapper mapper,
            UserSessionInvalidator invalidator
    ) {
        UserServiceImpl service = new UserServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "userSessionInvalidator", invalidator);
        return service;
    }

    private static User activeUser(int id) {
        User user = new User();
        user.setId(id);
        user.setRoleId(3);
        user.setStatus(true);
        user.setPassword("old-hash");
        return user;
    }
}
