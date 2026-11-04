package com.tss.platform.controller;

import com.tss.platform.dto.ApiResponse;
import com.tss.platform.dto.CurrentUserDto;
import com.tss.platform.dto.LoginRequest;
import com.tss.platform.dto.LoginResult;
import org.springframework.web.bind.annotation.*;

/**
 * 登录、当前用户、登出，与 Ant Design Pro 前端约定一致。
 * 演示账号：admin/ant.design 或 user/ant.design
 */
@RestController
public class AuthController {

    private static final String DEMO_PASSWORD = "ant.design";

    @PostMapping("/api/login/account")
    public LoginResult login(@RequestBody LoginRequest body) {
        String username = body.getUsername() != null ? body.getUsername().trim() : "";
        String password = body.getPassword() != null ? body.getPassword() : "";
        if (!DEMO_PASSWORD.equals(password)) {
            return new LoginResult("error", "account", "guest");
        }
        if ("admin".equals(username)) {
            return new LoginResult("ok", "account", "admin");
        }
        if ("user".equals(username)) {
            return new LoginResult("ok", "account", "user");
        }
        return new LoginResult("error", "account", "guest");
    }

    @GetMapping("/api/currentUser")
    public ApiResponse<CurrentUserDto> currentUser() {
        CurrentUserDto user = new CurrentUserDto();
        user.setName("当前用户");
        user.setUserid("demo");
        user.setAccess("admin");
        return ApiResponse.ok(user);
    }

    @PostMapping("/api/login/outLogin")
    public ApiResponse<Object> outLogin() {
        return ApiResponse.ok(null);
    }
}
