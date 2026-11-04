package com.tss.platform.module1.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("operation_logs")
public class OperationLog {
    @TableId(type = IdType.AUTO)
    private Integer id;
    
    @TableField("user_id")
    private Integer userId;
    
    @TableField("user_name")
    private String userName;
    
    @TableField("operation_type")
    private String operationType;
    
    @TableField("operation_obj")
    private String operationObj;
    
    @TableField("ip_address")
    private String ipAddress;
    
    @TableField("operation_time")
    private LocalDateTime operationTime;
    
    @TableField("remarks")
    private String remarks;
    
    @TableField("status")
    private String status;
}
