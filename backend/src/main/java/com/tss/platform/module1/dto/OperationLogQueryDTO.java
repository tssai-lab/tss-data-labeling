package com.tss.platform.module1.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class OperationLogQueryDTO {
    private Integer page = 1;
    private Integer size = 10;
    private Integer userId;
    private String username;
    private String operationType;
    private String operationObj;
    private String status;
    @JsonAlias({"ip"})
    private String ipAddress;
    @JsonAlias({"content", "remarks"})
    private String remarksKeyword;
    private LocalDateTime startTime;
    private LocalDateTime endTime;

    /** 服务端注入：仅查询这些操作人 userId（普管裁剪） */
    private java.util.List<Integer> forceOperatorUserIds;
}
