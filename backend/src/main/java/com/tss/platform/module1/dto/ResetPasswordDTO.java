package com.tss.platform.module1.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class ResetPasswordDTO {
    
    private Integer userId;
    
    @NotBlank(message = "新密码不能为空")
    @Pattern(regexp = "^\\w{6,16}$", message = "密码6-16位字母/数字/下划线")
    private String newPassword;
}