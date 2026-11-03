package com.tss.platform.module1.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserRoleUtilTest {

    @Test
    void reservesOnlyCompleteMainlandMobileNumbersForMobileLogin() {
        assertThat(UserRoleUtil.isMainlandMobile("13800000000")).isTrue();
        assertThat(UserRoleUtil.isMainlandMobile(" 13800000000 ")).isTrue();
        assertThat(UserRoleUtil.isMainlandMobile("admin13800000000")).isFalse();
        assertThat(UserRoleUtil.isMainlandMobile("12800000000")).isFalse();
        assertThat(UserRoleUtil.isMainlandMobile(null)).isFalse();
    }
}
