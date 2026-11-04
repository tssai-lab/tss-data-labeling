package com.tss.platform.module1.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class LogListQueryDTO {
    private Integer pageNum = 1;
    private Integer pageSize = 10;
    private String username;
    private String operateType;
    private LocalDateTime operateTimeStart;
    private LocalDateTime operateTimeEnd;
    private String ip;
    private String result;
    /** system | operation */
    private String logType;
    /** super_admin | normal_admin | user —— 仅作兼容，实际以服务端 Token 角色为准 */
    private String currentUserRole;
    /** 个人中心：仅查当前用户（服务端会再与 Token 校验） */
    private String currentUsername;
    private String content;

    /** 服务端注入：强制仅查该用户 ID（普通用户本人） */
    private Integer forceUserId;
    /** 服务端注入：是否仅普通用户操作人（普管） */
    private Boolean forceNormalUsersOnly;
    /** 服务端注入：响应是否隐藏 IP */
    private Boolean hideIp;
}
