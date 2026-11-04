package com.tss.platform.dto;

import lombok.Data;

/** 与前端 API.CurrentUser 一致，仅包含布局所需字段 */
@Data
public class CurrentUserDto {
    private String name;
    private String avatar;
    private String userid;
    private String access;
}
