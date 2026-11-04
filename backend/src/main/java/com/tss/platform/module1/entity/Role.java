package com.tss.platform.module1.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 角色表实体 - 对应数据库roles表
 * @Data：自动生成get/set/toString等方法，简化代码
 * @TableName：指定映射的数据库表名
 * @TableId：指定主键，type=AUTO适配PostgreSQL SERIAL自增
 */
@Data
@TableName("roles")
public class Role {
    // 主键id，对应roles.id
    @TableId(type = IdType.AUTO)
    private Integer id;
    // 角色名称，对应roles.role_name（超级管理员/普通用户）
    private String roleName;
    // 角色描述，对应roles.description
    private String description;
    // 创建时间，对应roles.created_at
    private LocalDateTime createdAt;
}