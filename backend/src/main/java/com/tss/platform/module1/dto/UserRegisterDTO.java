package com.tss.platform.module1.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UserRegisterDTO {

    @NotBlank(message = "用户名不能为空")
    @Size(min = 6, max = 20, message = "用户名6-20位字符")
    @Pattern(regexp = "^(?!1[3-9]\\d{9}$).*$", message = "用户名不能使用手机号格式")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Pattern(regexp = "^\\w{6,16}$", message = "密码6-16位字母/数字/下划线")
    private String password;

    @NotBlank(message = "确认密码不能为空")
    private String confirmPassword;

    private String mobile;

    private String smsCode;

    private Integer roleId = 3;
}
