package com.tss.platform.module1.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("audit_records")
public class AuditRecord {
    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Integer userId;

    private String username;

    @TableField("operator_role")
    private String operatorRole;

    @TableField("action_type")
    private String actionType;

    @TableField("object_type")
    private String objectType;

    @TableField("object_id")
    private String objectId;

    private String result;

    @TableField("fail_reason")
    private String failReason;

    @TableField("ip_address")
    private String ipAddress;

    @TableField("request_id")
    private String requestId;

    private String detail;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
