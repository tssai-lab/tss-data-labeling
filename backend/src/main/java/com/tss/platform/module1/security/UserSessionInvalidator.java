package com.tss.platform.module1.security;

import cn.dev33.satoken.stp.StpUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class UserSessionInvalidator {

    private static final Logger LOG = LoggerFactory.getLogger(UserSessionInvalidator.class);

    public void invalidateAfterCommit(Integer userId) {
        if (userId == null) {
            return;
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    invalidateNow(userId);
                }
            });
            return;
        }
        invalidateNow(userId);
    }

    public void invalidateNow(Integer userId) {
        if (userId == null) {
            return;
        }
        try {
            StpUtil.logout(userId);
        } catch (RuntimeException exception) {
            // 数据已经提交，撤销会话失败不能伪装成数据库回滚；记录后交由监控告警。
            LOG.error("Failed to invalidate user session after account change: userId={}", userId, exception);
        }
    }
}
