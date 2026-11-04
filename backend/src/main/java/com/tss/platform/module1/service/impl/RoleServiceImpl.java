package com.tss.platform.module1.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tss.platform.module1.entity.Role;
import com.tss.platform.module1.mapper.RoleMapper;
import com.tss.platform.module1.service.RoleService;
import org.springframework.stereotype.Service;

@Service
public class RoleServiceImpl extends ServiceImpl<RoleMapper, Role> implements RoleService {
}
