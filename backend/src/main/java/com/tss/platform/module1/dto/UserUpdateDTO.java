package com.tss.platform.module1.dto;

import lombok.Data;

@Data
public class UserUpdateDTO {
    private Integer userId;
    private String username;
    private String mobile;
    private String email;
    private Integer roleId;
    private Boolean status;
}
