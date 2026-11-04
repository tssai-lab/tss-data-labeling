package com.tss.platform.module1.dto;

import lombok.Data;

@Data
public class UserQueryDTO {
    private Integer page = 1;
    private Integer size = 10;
    private String username;
    private String mobile;
    private Integer roleId;
    private Boolean status;
}
