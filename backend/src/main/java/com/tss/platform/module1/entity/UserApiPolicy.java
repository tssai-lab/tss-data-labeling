package com.tss.platform.module1.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("user_api_policies")
public class UserApiPolicy {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Integer userId;
    private String featureGroup;
    private Boolean enabled;
    private Integer maxConcurrentRequests;
    private Integer updatedBy;
    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
