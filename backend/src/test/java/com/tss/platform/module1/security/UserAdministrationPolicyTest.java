package com.tss.platform.module1.security;

import com.tss.platform.module1.entity.User;
import com.tss.platform.security.AuthContext;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserAdministrationPolicyTest {

    private final AuthContext authContext = mock(AuthContext.class);
    private final UserAdministrationPolicy policy = new UserAdministrationPolicy(authContext);

    @Test
    void superAdministratorCanSeeAllRolesButCannotCreateAnotherSuperAdministrator() {
        when(authContext.currentRoleId()).thenReturn(1);
        when(authContext.isSuperAdmin()).thenReturn(true);

        assertThat(policy.requiredVisibleRoleId()).isNull();
        policy.requireCanManage(user(2));
        assertThat(policy.requireAssignableRole(2, null)).isEqualTo(2);
        assertThat(policy.requireAssignableRole(1, 1)).isEqualTo(1);
        assertThatThrownBy(() -> policy.requireAssignableRole(1, null))
                .isInstanceOf(UserAdministrationForbiddenException.class)
                .hasMessageContaining("超级管理员");
    }

    @Test
    void normalAdministratorCanOnlySeeAndManageOrdinaryUsers() {
        when(authContext.currentRoleId()).thenReturn(2);

        assertThat(policy.requiredVisibleRoleId()).isEqualTo(3);
        policy.requireCanManage(user(3));
        assertThat(policy.requireAssignableRole(null, null)).isEqualTo(3);
        assertThatThrownBy(() -> policy.requireCanManage(user(2)))
                .isInstanceOf(UserAdministrationForbiddenException.class)
                .hasMessageContaining("普通用户");
        assertThatThrownBy(() -> policy.requireAssignableRole(2, 3))
                .isInstanceOf(UserAdministrationForbiddenException.class)
                .hasMessageContaining("普通用户");

        User deletedOrdinaryUser = user(3);
        deletedOrdinaryUser.setDeletedAt(LocalDateTime.now());
        policy.requireCanRestore(deletedOrdinaryUser);

        User deletedAdministrator = user(2);
        deletedAdministrator.setDeletedAt(LocalDateTime.now());
        assertThatThrownBy(() -> policy.requireCanRestore(deletedAdministrator))
                .isInstanceOf(UserAdministrationForbiddenException.class)
                .hasMessageContaining("普通用户");
    }

    @Test
    void ordinaryUserCannotEnterUserAdministrationPolicy() {
        when(authContext.currentRoleId()).thenReturn(3);

        assertThatThrownBy(policy::requiredVisibleRoleId)
                .isInstanceOf(UserAdministrationForbiddenException.class)
                .hasMessageContaining("管理员");
    }

    @Test
    void unsupportedRoleIdsAreRejectedInsteadOfStored() {
        when(authContext.currentRoleId()).thenReturn(1);

        assertThatThrownBy(() -> policy.requireAssignableRole(99, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持");
    }

    private static User user(int roleId) {
        User user = new User();
        user.setId(roleId * 10);
        user.setRoleId(roleId);
        return user;
    }
}
