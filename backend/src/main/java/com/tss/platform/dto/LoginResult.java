package com.tss.platform.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 与前端 API.LoginResult 一致 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginResult {
    private String status;
    private String type;
    private String currentAuthority;
}
