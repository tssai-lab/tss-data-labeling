package com.tss.platform.module1.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tss.platform.module1.entity.Role;
import org.apache.ibatis.annotations.Mapper;

@Mapper  // 标记为MyBatis Mapper接口
public interface RoleMapper extends BaseMapper<Role> {
    // 无需额外方法，BaseMapper已提供CRUD
}
