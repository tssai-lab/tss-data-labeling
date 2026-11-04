package com.tss.platform.module1.sms;

import com.tss.platform.config.SmsProperties;
import com.tss.platform.module1.util.SmsCodeUtil;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SmsVerificationServiceTest {

    @Test
    void localModeExposesCodeAndConsumesItOnlyAfterSuccessfulCheck() {
        SmsCodeUtil codeUtil = new SmsCodeUtil();
        SmsVerificationService service = new SmsVerificationService(
                new LocalSmsSender(), codeUtil, localProperties());

        SmsVerificationService.IssueResult result = service.issue(" 13800001001 ");

        assertThat(result.exposedCode()).matches("\\d{6}");
        assertThat(service.verify("13800001001", result.exposedCode())).isTrue();
        assertThat(service.verifyAndConsume("13800001001", result.exposedCode())).isTrue();
        assertThat(service.verify("13800001001", result.exposedCode())).isFalse();
    }

    @Test
    void remoteProviderOwnsVerificationAndPlainCodeIsNeverExposed() {
        AtomicInteger verifies = new AtomicInteger();
        SmsVerificationProvider provider = new StubProvider() {
            @Override
            public IssueReceipt issue(String mobile, SmsPurpose purpose, int expireSeconds, int resendSeconds) {
                return IssueReceipt.remote();
            }

            @Override
            public boolean verify(String mobile, String code) {
                verifies.incrementAndGet();
                return "654321".equals(code);
            }
        };
        SmsProperties properties = new SmsProperties();
        properties.setProvider("aliyun");
        SmsVerificationService service = new SmsVerificationService(provider, new SmsCodeUtil(), properties);

        SmsVerificationService.IssueResult result = service.issue("13800001002");

        assertThat(result.exposedCode()).isNull();
        assertThat(service.verify("13800001002", "111111")).isFalse();
        assertThat(service.verifyAndConsume("13800001002", "654321")).isTrue();
        assertThat(service.verify("13800001002", "654321")).isFalse();
        assertThat(verifies).hasValue(2);
    }

    @Test
    void providerFailureDoesNotCreateAResendCooldown() {
        SmsCodeUtil codeUtil = new SmsCodeUtil();
        SmsVerificationService service = new SmsVerificationService(new StubProvider() {
            @Override
            public IssueReceipt issue(String mobile, SmsPurpose purpose, int expireSeconds, int resendSeconds) {
                throw new SmsProviderException("provider rejected");
            }
        }, codeUtil, localProperties());

        assertThatThrownBy(() -> service.issue("13800001003"))
                .isInstanceOf(SmsProviderException.class);
        assertThat(codeUtil.isLimited("13800001003")).isFalse();
    }

    @Test
    void concurrentRequestsForOneMobileSendOnlyOnce() throws Exception {
        SmsCodeUtil codeUtil = new SmsCodeUtil();
        AtomicInteger sends = new AtomicInteger();
        CountDownLatch providerEntered = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        SmsVerificationService service = new SmsVerificationService(new StubProvider() {
            @Override
            public IssueReceipt issue(String mobile, SmsPurpose purpose, int expireSeconds, int resendSeconds) {
                sends.incrementAndGet();
                providerEntered.countDown();
                try {
                    releaseProvider.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new SmsProviderException("interrupted", exception);
                }
                return IssueReceipt.local("123456");
            }
        }, codeUtil, localProperties());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> service.issue("13800001004"));
            providerEntered.await();
            Future<?> second = executor.submit(() -> service.issue("13800001004"));
            releaseProvider.countDown();
            first.get();
            assertThatThrownBy(second::get).hasCauseInstanceOf(SmsRateLimitException.class);
            assertThat(sends).hasValue(1);
        } finally {
            releaseProvider.countDown();
            executor.shutdownNow();
            codeUtil.consume("13800001004");
        }
    }

    @Test
    void refusesToExposeCodeForARealProvider() {
        SmsProperties properties = new SmsProperties();
        properties.setProvider("aliyun");
        properties.setExposeCode(true);

        assertThatThrownBy(() -> new SmsVerificationService(
                new StubProvider(), new SmsCodeUtil(), properties))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void consumesOnlyAfterDatabaseTransactionCommits() {
        SmsVerificationService service = new SmsVerificationService(
                new LocalSmsSender(), new SmsCodeUtil(), localProperties());
        SmsVerificationService.IssueResult issued = service.issue("13800001005");
        TransactionSynchronizationManager.initSynchronization();
        try {
            assertThat(service.verifyAndConsumeAfterCommit("13800001005", issued.exposedCode())).isTrue();
            assertThat(service.verify("13800001005", issued.exposedCode())).isTrue();

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
            assertThat(service.verify("13800001005", issued.exposedCode())).isFalse();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void rollbackKeepsCodeAvailableForAUserRetry() {
        SmsVerificationService service = new SmsVerificationService(
                new LocalSmsSender(), new SmsCodeUtil(), localProperties());
        SmsVerificationService.IssueResult issued = service.issue("13800001006");
        TransactionSynchronizationManager.initSynchronization();
        try {
            assertThat(service.verifyAndConsumeAfterCommit("13800001006", issued.exposedCode())).isTrue();
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            assertThat(service.verify("13800001006", issued.exposedCode())).isTrue();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void serviceRestartFailsClosedAndRequiresANewCode() {
        AtomicInteger providerChecks = new AtomicInteger();
        SmsVerificationProvider provider = new StubProvider() {
            @Override
            public IssueReceipt issue(String mobile, SmsPurpose purpose, int expireSeconds, int resendSeconds) {
                return IssueReceipt.remote();
            }

            @Override
            public boolean verify(String mobile, String code) {
                providerChecks.incrementAndGet();
                return true;
            }
        };
        SmsProperties properties = new SmsProperties();
        properties.setProvider("aliyun");
        SmsVerificationService beforeRestart = new SmsVerificationService(provider, new SmsCodeUtil(), properties);
        beforeRestart.issue("13800001007");

        SmsVerificationService afterRestart = new SmsVerificationService(provider, new SmsCodeUtil(), properties);

        assertThat(afterRestart.verify("13800001007", "123456")).isFalse();
        assertThat(providerChecks).hasValue(0);
    }

    @Test
    void concurrentTransactionCannotReuseAReservedCode() {
        SmsVerificationService service = new SmsVerificationService(
                new LocalSmsSender(), new SmsCodeUtil(), localProperties());
        SmsVerificationService.IssueResult issued = service.issue("13800001008");
        TransactionSynchronizationManager.initSynchronization();
        try {
            assertThat(service.verifyAndConsumeAfterCommit("13800001008", issued.exposedCode())).isTrue();
            assertThat(service.verifyAndConsumeAfterCommit("13800001008", issued.exposedCode())).isFalse();

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            assertThat(service.verifyAndConsumeAfterCommit("13800001008", issued.exposedCode())).isTrue();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void codeCanOnlyBeUsedForThePurposeItWasIssuedFor() {
        SmsVerificationService service = new SmsVerificationService(
                new LocalSmsSender(), new SmsCodeUtil(), localProperties());
        SmsVerificationService.IssueResult issued =
                service.issue("13800001009", SmsPurpose.RESET_PASSWORD);

        assertThat(service.verify(
                "13800001009", issued.exposedCode(), SmsPurpose.LOGIN_REGISTER)).isFalse();
        assertThat(service.verify(
                "13800001009", issued.exposedCode(), SmsPurpose.RESET_PASSWORD)).isTrue();
    }

    @Test
    void optionalDailyLimitCountsOnlySuccessfulRemoteSends() {
        AtomicInteger calls = new AtomicInteger();
        SmsVerificationProvider provider = new StubProvider() {
            @Override
            public IssueReceipt issue(String mobile, SmsPurpose purpose, int expireSeconds, int resendSeconds) {
                if (calls.incrementAndGet() == 1) {
                    throw new SmsProviderException("temporary failure");
                }
                return IssueReceipt.remote();
            }
        };
        SmsProperties properties = new SmsProperties();
        properties.setProvider("aliyun");
        properties.setMaxDailySends(1);
        SmsVerificationService service = new SmsVerificationService(provider, new SmsCodeUtil(), properties);

        assertThatThrownBy(() -> service.issue("13800001010"))
                .isInstanceOf(SmsProviderException.class);
        service.issue("13800001011");
        assertThatThrownBy(() -> service.issue("13800001012"))
                .isInstanceOf(SmsRateLimitException.class)
                .hasMessageContaining("今日验证码发送量已达");
    }

    @Test
    void legacyZeroDailyLimitFallsBackToFiftySuccessfulSends() {
        AtomicInteger sends = new AtomicInteger();
        SmsVerificationProvider provider = new StubProvider() {
            @Override
            public IssueReceipt issue(String mobile, SmsPurpose purpose, int expireSeconds, int resendSeconds) {
                sends.incrementAndGet();
                return IssueReceipt.remote();
            }
        };
        SmsProperties properties = new SmsProperties();
        properties.setProvider("aliyun");
        properties.setMaxDailySends(0);
        SmsVerificationService service = new SmsVerificationService(provider, new SmsCodeUtil(), properties);

        for (int index = 0; index < 50; index++) {
            service.issue(String.format("1380001%04d", index));
        }

        assertThatThrownBy(() -> service.issue("13800019999"))
                .isInstanceOf(SmsRateLimitException.class)
                .hasMessageContaining("今日验证码发送量已达");
        assertThat(sends).hasValue(50);
    }

    private static SmsProperties localProperties() {
        SmsProperties properties = new SmsProperties();
        properties.setProvider("local");
        properties.setExposeCode(true);
        return properties;
    }

    private static class StubProvider implements SmsVerificationProvider {
        @Override
        public IssueReceipt issue(String mobile, SmsPurpose purpose, int expireSeconds, int resendSeconds) {
            return IssueReceipt.local("123456");
        }

        @Override
        public boolean verify(String mobile, String code) {
            return false;
        }
    }
}
