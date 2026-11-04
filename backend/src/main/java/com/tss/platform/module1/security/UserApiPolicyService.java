package com.tss.platform.module1.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.tss.platform.module1.entity.User;
import com.tss.platform.module1.entity.UserApiPolicy;
import com.tss.platform.module1.mapper.UserApiPolicyMapper;
import com.tss.platform.module1.service.UserService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

@Service
public class UserApiPolicyService {

    private final UserApiPolicyMapper policyMapper;
    private final UserService userService;

    public UserApiPolicyService(UserApiPolicyMapper policyMapper, UserService userService) {
        this.policyMapper = policyMapper;
        this.userService = userService;
    }

    public UserApiPolicyDecision resolve(Integer userId, UserApiFeatureGroup featureGroup) {
        UserApiPolicy policy = find(userId, featureGroup);
        return policy == null ? UserApiPolicyDecision.inherited(userId, featureGroup) : decision(policy);
    }

    public List<UserApiPolicyDecision> listForTarget(Integer userId) {
        requireManageableTarget(userId);
        return Arrays.stream(UserApiFeatureGroup.values())
                .map(group -> resolve(userId, group))
                .toList();
    }

    @Transactional
    public UserApiPolicyDecision save(
            Integer userId,
            UserApiFeatureGroup featureGroup,
            boolean enabled,
            Integer maxConcurrentRequests,
            Long expectedVersion,
            Integer operatorUserId
    ) {
        requireManageableTarget(userId);
        validateConcurrency(maxConcurrentRequests);
        UserApiPolicy existing = find(userId, featureGroup);
        if (existing == null) {
            if (expectedVersion != null && expectedVersion > 0) {
                throw conflict();
            }
            UserApiPolicy created = new UserApiPolicy();
            created.setUserId(userId);
            created.setFeatureGroup(featureGroup.name());
            created.setEnabled(enabled);
            created.setMaxConcurrentRequests(maxConcurrentRequests);
            created.setUpdatedBy(operatorUserId);
            created.setVersion(0L);
            LocalDateTime now = LocalDateTime.now();
            created.setCreatedAt(now);
            created.setUpdatedAt(now);
            try {
                if (policyMapper.insert(created) != 1) {
                    throw new IllegalStateException("API 策略保存失败");
                }
                return decision(created);
            } catch (DuplicateKeyException exception) {
                UserApiPolicy concurrent = find(userId, featureGroup);
                if (sameValue(concurrent, enabled, maxConcurrentRequests)) {
                    return decision(concurrent);
                }
                throw conflict();
            }
        }

        if (!Objects.equals(existing.getVersion(), expectedVersion)) {
            if (sameValue(existing, enabled, maxConcurrentRequests)) {
                return decision(existing);
            }
            throw conflict();
        }
        if (sameValue(existing, enabled, maxConcurrentRequests)) {
            return decision(existing);
        }

        LocalDateTime now = LocalDateTime.now();
        LambdaUpdateWrapper<UserApiPolicy> update = new LambdaUpdateWrapper<UserApiPolicy>()
                .eq(UserApiPolicy::getId, existing.getId())
                .eq(UserApiPolicy::getVersion, expectedVersion)
                .set(UserApiPolicy::getEnabled, enabled)
                .set(UserApiPolicy::getMaxConcurrentRequests, maxConcurrentRequests)
                .set(UserApiPolicy::getUpdatedBy, operatorUserId)
                .set(UserApiPolicy::getUpdatedAt, now)
                .set(UserApiPolicy::getVersion, expectedVersion + 1);
        if (policyMapper.update(null, update) != 1) {
            throw conflict();
        }
        existing.setEnabled(enabled);
        existing.setMaxConcurrentRequests(maxConcurrentRequests);
        existing.setUpdatedBy(operatorUserId);
        existing.setUpdatedAt(now);
        existing.setVersion(expectedVersion + 1);
        return decision(existing);
    }

    @Transactional
    public UserApiPolicyDecision reset(
            Integer userId,
            UserApiFeatureGroup featureGroup,
            Long expectedVersion
    ) {
        requireManageableTarget(userId);
        UserApiPolicy existing = find(userId, featureGroup);
        if (existing == null) {
            return UserApiPolicyDecision.inherited(userId, featureGroup);
        }
        if (!Objects.equals(existing.getVersion(), expectedVersion)) {
            throw conflict();
        }
        LambdaQueryWrapper<UserApiPolicy> delete = new LambdaQueryWrapper<UserApiPolicy>()
                .eq(UserApiPolicy::getId, existing.getId())
                .eq(UserApiPolicy::getVersion, expectedVersion);
        if (policyMapper.delete(delete) != 1) {
            throw conflict();
        }
        return UserApiPolicyDecision.inherited(userId, featureGroup);
    }

    private UserApiPolicy find(Integer userId, UserApiFeatureGroup featureGroup) {
        if (userId == null || featureGroup == null) {
            return null;
        }
        return policyMapper.selectOne(new LambdaQueryWrapper<UserApiPolicy>()
                .eq(UserApiPolicy::getUserId, userId)
                .eq(UserApiPolicy::getFeatureGroup, featureGroup.name()));
    }

    private void requireManageableTarget(Integer userId) {
        if (userId == null) {
            throw new IllegalArgumentException("用户ID不能为空");
        }
        User user = userService.getById(userId);
        if (user == null || user.getDeletedAt() != null) {
            throw new IllegalArgumentException("用户不存在");
        }
        if (Integer.valueOf(1).equals(user.getRoleId())) {
            throw new IllegalArgumentException("超级管理员不适用用户级 API 策略");
        }
    }

    private static void validateConcurrency(Integer maxConcurrentRequests) {
        if (maxConcurrentRequests != null && maxConcurrentRequests < 1) {
            throw new IllegalArgumentException("并发上限必须大于等于 1；留空表示不限流");
        }
    }

    private static boolean sameValue(UserApiPolicy policy, boolean enabled, Integer maxConcurrentRequests) {
        return policy != null
                && Boolean.valueOf(enabled).equals(policy.getEnabled())
                && Objects.equals(maxConcurrentRequests, policy.getMaxConcurrentRequests());
    }

    private static UserApiPolicyDecision decision(UserApiPolicy policy) {
        UserApiFeatureGroup group = UserApiFeatureGroup.parse(policy.getFeatureGroup());
        return UserApiPolicyDecision.explicit(
                policy.getUserId(),
                group,
                Boolean.TRUE.equals(policy.getEnabled()),
                policy.getMaxConcurrentRequests(),
                policy.getVersion(),
                policy.getUpdatedAt()
        );
    }

    private static UserApiPolicyConflictException conflict() {
        return new UserApiPolicyConflictException("策略已被其他操作修改，请刷新后重试");
    }
}
