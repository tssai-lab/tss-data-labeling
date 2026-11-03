package com.tss.platform.module1.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class SmsCodeDTO {
    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式错误")
    private String mobile;

    /** LOGIN_REGISTER（默认）或 RESET_PASSWORD。 */
    private String purpose = "LOGIN_REGISTER";
}
