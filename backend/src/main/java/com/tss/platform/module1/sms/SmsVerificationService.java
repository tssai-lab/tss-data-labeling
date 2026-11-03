package com.tss.platform.module1.sms;

import com.tss.platform.config.SmsProperties;
import com.tss.platform.module1.util.SmsCodeUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

@Service
public class SmsVerificationService {

    public static final int EXPIRE_SECONDS = 300;
    public static final int RESEND_SECONDS = 60;
    private static final int LOCK_STRIPES = 64;

    private final SmsVerificationProvider provider;
    private final SmsCodeUtil codeUtil;
    private final boolean exposeCode;
    private final boolean remoteProvider;
    private final int maxDailySends;
    private final Set<String> transactionalReservations = ConcurrentHashMap.newKeySet();
    private final Object dailyLimitLock = new Object();
    private final AtomicInteger dailySendCount = new AtomicInteger();
    private LocalDate dailySendDate = LocalDate.now();
    private final Object[] locks = IntStream.range(0, LOCK_STRIPES)
            .mapToObj(ignored -> new Object())
            .toArray(Object[]::new);

    public SmsVerificationService(SmsVerificationProvider provider, SmsCodeUtil codeUtil, SmsProperties properties) {
        this.provider = provider;
        this.codeUtil = codeUtil;
        this.exposeCode = properties.isExposeCode();
        this.remoteProvider = "aliyun".equalsIgnoreCase(properties.getProvider());
        int configuredDailyLimit = properties.getMaxDailySends();
        this.maxDailySends = configuredDailyLimit > 0 ? configuredDailyLimit : 50;
        if (exposeCode && !"local".equalsIgnoreCase(properties.getProvider())) {
            throw new IllegalStateException("sms.expose-code 只能与 local 短信供应商一起使用");
        }
    }

    public IssueResult issue(String rawMobile) {
        return issue(rawMobile, SmsPurpose.LOGIN_REGISTER);
    }

    public IssueResult issue(String rawMobile, SmsPurpose purpose) {
        String mobile = rawMobile == null ? "" : rawMobile.trim();
        SmsPurpose resolvedPurpose = purpose == null ? SmsPurpose.LOGIN_REGISTER : purpose;
        Object lock = locks[Math.floorMod(mobile.hashCode(), locks.length)];
        synchronized (lock) {
            if (codeUtil.isLimited(mobile)) {
                throw new SmsRateLimitException(RESEND_SECONDS + "秒内只能发送一次验证码");
            }
            boolean dailySlotReserved = reserveDailySlot();
            try {
                SmsVerificationProvider.IssueReceipt receipt =
                        provider.issue(mobile, resolvedPurpose, EXPIRE_SECONDS, RESEND_SECONDS);
                String localCode = receipt == null ? null : receipt.localCode();
                if (localCode == null) {
                    codeUtil.markIssued(mobile, resolvedPurpose);
                } else {
                    codeUtil.saveLocal(mobile, localCode, resolvedPurpose);
                }
                return new IssueResult(exposeCode ? localCode : null, EXPIRE_SECONDS);
            } catch (RuntimeException exception) {
                if (dailySlotReserved) {
                    dailySendCount.decrementAndGet();
                }
                throw exception;
            }
        }
    }

    /** 仅校验，不消费验证码。 */
    public boolean verify(String rawMobile, String rawCode) {
        return verify(rawMobile, rawCode, SmsPurpose.LOGIN_REGISTER);
    }

    public boolean verify(String rawMobile, String rawCode, SmsPurpose purpose) {
        String mobile = normalize(rawMobile);
        String code = rawCode == null ? "" : rawCode.trim();
        SmsPurpose resolvedPurpose = purpose == null ? SmsPurpose.LOGIN_REGISTER : purpose;
        if (mobile.isEmpty() || code.isEmpty() || !codeUtil.hasActiveIssue(mobile, resolvedPurpose)) {
            return false;
        }
        if (codeUtil.hasLocalCode(mobile)) {
            return codeUtil.verifyLocal(mobile, code, resolvedPurpose);
        }
        return provider.verify(mobile, code);
    }

    /**
     * 注册、改密使用：同一手机号一次只允许一个事务占用验证码。
     * 提交后消费；回滚时只释放占用，验证码仍可重试。
     */
    public boolean verifyAndConsumeAfterCommit(String rawMobile, String rawCode) {
        return verifyAndConsumeAfterCommit(rawMobile, rawCode, SmsPurpose.LOGIN_REGISTER);
    }

    public boolean verifyAndConsumeAfterCommit(String rawMobile, String rawCode, SmsPurpose purpose) {
        String mobile = normalize(rawMobile);
        Object lock = lockFor(mobile);
        synchronized (lock) {
            if (!transactionalReservations.add(mobile)) {
                return false;
            }
            try {
                if (!verify(mobile, rawCode, purpose)) {
                    transactionalReservations.remove(mobile);
                    return false;
                }
                if (TransactionSynchronizationManager.isSynchronizationActive()) {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            codeUtil.consume(mobile);
                        }

                        @Override
                        public void afterCompletion(int status) {
                            transactionalReservations.remove(mobile);
                        }
                    });
                } else {
                    codeUtil.consume(mobile);
                    transactionalReservations.remove(mobile);
                }
                return true;
            } catch (RuntimeException exception) {
                transactionalReservations.remove(mobile);
                throw exception;
            }
        }
    }

    /** 登录使用：同一进程内将“校验+消耗”串行化，避免一个验证码被并发使用两次。 */
    public boolean verifyAndConsume(String rawMobile, String rawCode) {
        return verifyAndConsume(rawMobile, rawCode, SmsPurpose.LOGIN_REGISTER);
    }

    public boolean verifyAndConsume(String rawMobile, String rawCode, SmsPurpose purpose) {
        String mobile = normalize(rawMobile);
        Object lock = lockFor(mobile);
        synchronized (lock) {
            if (!verify(mobile, rawCode, purpose)) {
                return false;
            }
            codeUtil.consume(mobile);
            return true;
        }
    }

    private Object lockFor(String mobile) {
        return locks[Math.floorMod(mobile.hashCode(), locks.length)];
    }

    private boolean reserveDailySlot() {
        if (!remoteProvider) {
            return false;
        }
        synchronized (dailyLimitLock) {
            LocalDate today = LocalDate.now();
            if (!today.equals(dailySendDate)) {
                dailySendDate = today;
                dailySendCount.set(0);
            }
            if (dailySendCount.get() >= maxDailySends) {
                throw new SmsRateLimitException("今日验证码发送量已达管理员设置的上限");
            }
            dailySendCount.incrementAndGet();
            return true;
        }
    }

    private static String normalize(String mobile) {
        return mobile == null ? "" : mobile.trim();
    }

    public record IssueResult(String exposedCode, int expireSeconds) {
    }
}
