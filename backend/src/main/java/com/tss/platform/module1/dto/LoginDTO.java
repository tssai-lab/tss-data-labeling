package com.tss.platform.module1.dto;

import lombok.Data;

@Data
public class LoginDTO {

    /** 密码登录标识：用户名或手机号。为保持 API 兼容继续使用 username 字段名。 */
    private String username;

    private String password;

    private String mobile;

    private String smsCode;

    private Integer roleId = 3;

    private String type; // account 或 mobile
}
