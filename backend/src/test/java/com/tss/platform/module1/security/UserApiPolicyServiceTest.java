package com.tss.platform.module1.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.tss.platform.module1.entity.User;
import com.tss.platform.module1.entity.UserApiPolicy;
import com.tss.platform.module1.mapper.UserApiPolicyMapper;
import com.tss.platform.module1.service.UserService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserApiPolicyServiceTest {

    @Test
    void missingRowInheritsEnabledAndUnlimitedWithoutWritingHistoryUsers() {
        Fixture fixture = fixture(3);
        when(fixture.mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        UserApiPolicyDecision decision = fixture.service.resolve(7, UserApiFeatureGroup.MODEL_ASSET);

        assertThat(decision.inherited()).isTrue();
        assertThat(decision.enabled()).isTrue();
        assertThat(decision.maxConcurrentRequests()).isNull();
        verify(fixture.mapper, never()).insert(any());
    }

    @Test
    void firstOverrideIsInsertedWithUniqueUserAndGroupIdentity() {
        Fixture fixture = fixture(3);
        when(fixture.mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(fixture.mapper.insert(any(UserApiPolicy.class))).thenAnswer(invocation -> {
            UserApiPolicy row = invocation.getArgument(0);
            row.setId(11L);
            return 1;
        });

        UserApiPolicyDecision decision = fixture.service.save(
                7, UserApiFeatureGroup.TRAINING_TASK, false, 2, null, 1);

        assertThat(decision.enabled()).isFalse();
        assertThat(decision.maxConcurrentRequests()).isEqualTo(2);
        assertThat(decision.version()).isZero();
        verify(fixture.mapper).insert(any(UserApiPolicy.class));
    }

    @Test
    void failedInsertIsNotReportedAsAStoredPolicy() {
        Fixture fixture = fixture(3);
        when(fixture.mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(fixture.mapper.insert(any(UserApiPolicy.class))).thenReturn(0);

        assertThatThrownBy(() -> fixture.service.save(
                7, UserApiFeatureGroup.TRAINING_TASK, false, 2, null, 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("保存失败");
    }

    @Test
    void duplicateRetryWithSameValueIsIdempotentEvenWhenVersionIsStale() {
        Fixture fixture = fixture(3);
        UserApiPolicy existing = policy(true, 2, 4L);
        when(fixture.mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);

        UserApiPolicyDecision decision = fixture.service.save(
                7, UserApiFeatureGroup.MODEL_ASSET, true, 2, 3L, 1);

        assertThat(decision.version()).isEqualTo(4L);
        verify(fixture.mapper, never()).update(any(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void staleDifferentUpdateIsRejectedInsteadOfOverwritingAnotherAdministrator() {
        Fixture fixture = fixture(3);
        when(fixture.mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(policy(true, 2, 4L));

        assertThatThrownBy(() -> fixture.service.save(
                7, UserApiFeatureGroup.MODEL_ASSET, false, 1, 3L, 1))
                .isInstanceOf(UserApiPolicyConflictException.class)
                .hasMessageContaining("刷新后重试");
        verify(fixture.mapper, never()).update(any(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void resetWithoutExistingOverrideIsIdempotent() {
        Fixture fixture = fixture(3);
        when(fixture.mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        UserApiPolicyDecision decision = fixture.service.reset(
                7, UserApiFeatureGroup.DATASET_ASSET, null);

        assertThat(decision.inherited()).isTrue();
        verify(fixture.mapper, never()).delete(any(LambdaQueryWrapper.class));
    }

    @Test
    void superAdministratorCannotBeLockedOutByUserLevelPolicy() {
        Fixture fixture = fixture(1);

        assertThatThrownBy(() -> fixture.service.save(
                7, UserApiFeatureGroup.SYSTEM_ADMIN_AUDIT, false, null, null, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("超级管理员不适用");
        verify(fixture.mapper, never()).insert(any());
    }

    @Test
    void zeroConcurrencyLimitIsRejected() {
        Fixture fixture = fixture(3);

        assertThatThrownBy(() -> fixture.service.save(
                7, UserApiFeatureGroup.MODEL_ASSET, true, 0, null, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("大于等于 1");
    }

    private static Fixture fixture(int roleId) {
        UserApiPolicyMapper mapper = mock(UserApiPolicyMapper.class);
        UserService userService = mock(UserService.class);
        User user = new User();
        user.setId(7);
        user.setRoleId(roleId);
        when(userService.getById(7)).thenReturn(user);
        return new Fixture(new UserApiPolicyService(mapper, userService), mapper);
    }

    private static UserApiPolicy policy(boolean enabled, Integer maxConcurrent, long version) {
        UserApiPolicy policy = new UserApiPolicy();
        policy.setId(11L);
        policy.setUserId(7);
        policy.setFeatureGroup(UserApiFeatureGroup.MODEL_ASSET.name());
        policy.setEnabled(enabled);
        policy.setMaxConcurrentRequests(maxConcurrent);
        policy.setVersion(version);
        return policy;
    }

    private record Fixture(UserApiPolicyService service, UserApiPolicyMapper mapper) {
    }
}
