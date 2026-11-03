package com.tss.platform.module1.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

class UserSessionInvalidatorTest {

    @AfterEach
    void clearTransactionState() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void activeTransactionInvalidatesOnlyAfterCommit() {
        UserSessionInvalidator invalidator = spy(new UserSessionInvalidator());
        doNothing().when(invalidator).invalidateNow(7);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();

        invalidator.invalidateAfterCommit(7);

        verify(invalidator, never()).invalidateNow(7);
        for (TransactionSynchronization synchronization
                : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
        }
        verify(invalidator).invalidateNow(7);
    }

    @Test
    void noTransactionInvalidatesImmediately() {
        UserSessionInvalidator invalidator = spy(new UserSessionInvalidator());
        doNothing().when(invalidator).invalidateNow(8);

        invalidator.invalidateAfterCommit(8);

        verify(invalidator).invalidateNow(8);
    }
}
