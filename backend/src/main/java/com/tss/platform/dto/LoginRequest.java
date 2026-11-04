package com.tss.platform.dto;

import lombok.Data;

@Data
public class LoginRequest {
    private String username;
    private String password;
    private Boolean autoLogin;
    private String type;
}
