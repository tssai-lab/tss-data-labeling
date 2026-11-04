package com.tss.platform.module1.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户表实体 - 对应数据库users表
 */
@Data
@TableName("users")
public class User {
    // 主键id，对应users.id
    @TableId(type = IdType.AUTO)
    private Integer id;
    // 用户名，对应users.username（唯一）
    private String username;
    // 邮箱，对应users.email（唯一）
    private String email;
    // 加密密码，对应users.password（非明文）
    private String password;
    // 角色id，对应users.role_id（外键关联roles.id）
    private Integer roleId;
    // 启用/禁用状态，对应users.status（true=启用）
    private Boolean status;
    // 创建时间，对应users.created_at
    private LocalDateTime createdAt;
    // 更新时间，对应users.updated_at
    private LocalDateTime updatedAt;
    // 软删除标记，对应users.deleted_at（null=未删除）
    private LocalDateTime deletedAt;
    private String mobile;
}
