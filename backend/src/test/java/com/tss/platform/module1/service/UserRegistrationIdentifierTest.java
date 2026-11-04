package com.tss.platform.module1.service;

import com.tss.platform.module1.dto.UserRegisterDTO;
import com.tss.platform.module1.entity.User;
import com.tss.platform.module1.service.impl.UserServiceImpl;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserRegistrationIdentifierTest {

    @Test
    void usernameRegistrationRejectsAMobileNumberAsUsername() {
        UserRegisterDTO request = registration("13800000000");

        assertThatThrownBy(() -> new UserServiceImpl().registerByUsername(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("用户名不能使用手机号格式");
    }

    @Test
    void mobileRegistrationRequiresASeparateNonMobileUsername() {
        UserRegisterDTO missingUsername = registration(null);
        assertThatThrownBy(() -> new UserServiceImpl().registerByMobile(missingUsername))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("用户名不能为空");

        UserRegisterDTO mobileUsername = registration("13800000000");
        assertThatThrownBy(() -> new UserServiceImpl().registerByMobile(mobileUsername))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("用户名不能使用手机号格式");
    }

    @Test
    void administratorCreationAlsoRejectsAMobileNumberAsUsername() {
        User user = new User();
        user.setUsername("13800000000");
        user.setPassword("password123");

        assertThatThrownBy(() -> new UserServiceImpl().addUser(user))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("用户名不能使用手机号格式");
    }

    private static UserRegisterDTO registration(String username) {
        UserRegisterDTO request = new UserRegisterDTO();
        request.setUsername(username);
        request.setMobile("13800000000");
        request.setSmsCode("123456");
        request.setPassword("password123");
        request.setConfirmPassword("password123");
        return request;
    }
}
