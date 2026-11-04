package com.tss.platform.module1.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.tss.platform.module1.dto.LoginDTO;
import com.tss.platform.module1.entity.User;
import com.tss.platform.module1.mapper.UserMapper;
import com.tss.platform.module1.service.impl.UserServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserLoginIdentifierTest {

    @BeforeAll
    static void initializeMybatisMetadata() {
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        assistant.setCurrentNamespace(UserMapper.class.getName());
        TableInfoHelper.initTableInfo(assistant, User.class);
    }

    @Test
    void phoneShapedPasswordLoginQueriesOnlyTheMobileColumn() {
        Wrapper<User> query = failedLoginQuery("13800000000");

        assertThat(query.getSqlSegment()).contains("mobile").doesNotContain("username");
    }

    @Test
    void ordinaryPasswordLoginQueriesOnlyTheUsernameColumn() {
        Wrapper<User> query = failedLoginQuery("test-user");

        assertThat(query.getSqlSegment()).contains("username").doesNotContain("mobile");
    }

    private static Wrapper<User> failedLoginQuery(String loginIdentifier) {
        UserMapper mapper = mock(UserMapper.class);
        when(mapper.selectOne(any(), eq(true))).thenReturn(null);
        UserServiceImpl service = new UserServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        LoginDTO request = new LoginDTO();
        request.setType("account");
        request.setUsername(loginIdentifier);
        request.setPassword("password123");

        assertThatThrownBy(() -> service.login(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("用户名/手机号或密码错误");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<User>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectOne(captor.capture(), eq(true));
        return captor.getValue();
    }
}
