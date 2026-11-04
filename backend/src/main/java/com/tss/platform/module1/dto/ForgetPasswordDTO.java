package com.tss.platform.module1.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.EqualsAndHashCode;

// 继承SmsCodeDTO，复用手机号校验规则
@Data
@EqualsAndHashCode(callSuper = true)
public class ForgetPasswordDTO extends SmsCodeDTO {

    // 新密码（复用注册的密码校验规则）
    @NotBlank(message = "新密码不能为空")
    @Pattern(regexp = "^\\w{6,16}$", message = "密码6-16位字母/数字/下划线")
    private String newPassword;

    // 验证码（复用注册的验证码字段）
    @NotBlank(message = "验证码不能为空")
    @Pattern(regexp = "^\\d{6}$", message = "验证码应为6位数字")
    private String smsCode;
}
